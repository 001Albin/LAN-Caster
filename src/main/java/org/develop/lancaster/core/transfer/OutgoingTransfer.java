package org.develop.lancaster.core.transfer;

import org.develop.lancaster.core.discovery.PeerInfo;
import org.develop.lancaster.core.network.Protocol;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sender side of one transfer: offers the file over a control connection, then
 * pushes the blocks the receiver still needs over N parallel data connections
 * using zero-copy {@link FileChannel#transferTo}.
 */
public final class OutgoingTransfer implements Runnable {
    private static final Logger logger = Logger.getLogger(OutgoingTransfer.class.getName());
    private static final int MAX_ROUNDS = 3;

    private final TransferEngine engine;
    private final TransferSession session;
    private final File file;
    private final PeerInfo peer;
    private final ExecutorService pool;

    private volatile Socket control;
    private volatile DataOutputStream ctlOut;
    private final ConcurrentLinkedQueue<long[]> blocks = new ConcurrentLinkedQueue<>();
    private final Set<SocketChannel> dataChannels = ConcurrentHashMap.newKeySet();
    private final CountDownLatch confirmation = new CountDownLatch(1);

    private volatile boolean cancelled;
    private volatile boolean confirmed;
    private volatile String failure;

    public OutgoingTransfer(TransferEngine engine, TransferSession session, File file, PeerInfo peer, ExecutorService pool) {
        this.engine = engine;
        this.session = session;
        this.file = file;
        this.peer = peer;
        this.pool = pool;
    }

    public TransferSession getSession() { return session; }

    @Override
    public void run() {
        session.setCanceller(this::cancel);
        InetSocketAddress addr = new InetSocketAddress(peer.getIp(), peer.getPort());
        try {
            session.setState(TransferSession.State.CONNECTING, "Connecting to " + peer.displayName() + "…");
            Socket s = new Socket();
            control = s;
            s.setTcpNoDelay(true);
            s.connect(addr, Protocol.CONNECT_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            ctlOut = out;

            Offer offer = new Offer(session.getId(), file.getName(), file.length(), engine.getDeviceName(),
                    engine.getInstanceId(), Protocol.BLOCK_SIZE, file.lastModified());
            synchronized (out) {
                out.writeUTF(Protocol.MAGIC);
                out.writeInt(Protocol.VERSION);
                out.writeUTF(Protocol.OFFER);
                offer.write(out);
                out.flush();
            }

            session.setState(TransferSession.State.WAITING_ACCEPT, "Waiting for " + peer.displayName() + " to accept…");
            s.setSoTimeout(Protocol.ACCEPT_WAIT_MS + 10_000);
            String reply;
            try {
                reply = in.readUTF();
            } catch (SocketTimeoutException e) {
                session.setState(TransferSession.State.FAILED, peer.displayName() + " did not respond to the offer");
                return;
            }
            if (Protocol.DECLINE.equals(reply)) {
                String reason = in.readUTF();
                session.setState(TransferSession.State.DECLINED, reason.isEmpty() ? peer.displayName() + " declined" : reason);
                return;
            }
            if (Protocol.ERROR.equals(reply)) {
                session.setState(TransferSession.State.FAILED, in.readUTF());
                return;
            }
            if (!Protocol.ACCEPT.equals(reply)) {
                session.setState(TransferSession.State.FAILED, "Unexpected reply from peer: " + reply);
                return;
            }

            int streams = Math.max(1, Math.min(16, in.readInt()));
            int rangeCount = in.readInt();
            long missing = 0;
            for (int i = 0; i < rangeCount; i++) {
                long start = in.readLong();
                long len = in.readLong();
                missing += len;
                for (long off = start; off < start + len; off += Protocol.BLOCK_SIZE) {
                    blocks.add(new long[]{off, Math.min(Protocol.BLOCK_SIZE, start + len - off)});
                }
            }
            s.setSoTimeout(0);
            session.setBytesDone(file.length() - missing);
            session.setResumed(missing < file.length());
            session.setStreams(streams);
            session.setState(TransferSession.State.TRANSFERRING,
                    (missing < file.length() ? "Resuming to " : "Sending to ") + peer.displayName());

            Thread reader = new Thread(() -> controlReader(in), "control-reader-" + session.getId());
            reader.setDaemon(true);
            reader.start();

            if (!blocks.isEmpty()) {
                try (FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                    for (int round = 0; round < MAX_ROUNDS && !blocks.isEmpty() && !cancelled && failure == null; round++) {
                        int n = round == 0 ? streams : 1;
                        runStreams(fc, addr, n);
                    }
                }
            }
            if (cancelled) return;
            if (failure == null && !blocks.isEmpty()) failure = "Connection to " + peer.displayName() + " was lost";
            if (failure != null) {
                sendControl(Protocol.ERROR, failure);
                session.setState(TransferSession.State.FAILED, failure + ". Send again to resume.");
                return;
            }

            session.setState(TransferSession.State.FINALIZING, "Waiting for " + peer.displayName() + " to confirm…");
            confirmation.await(Protocol.STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (confirmed) {
                session.setBytesDone(file.length());
                session.setState(TransferSession.State.COMPLETED, "Sent to " + peer.displayName());
            } else if (!cancelled) {
                session.setState(TransferSession.State.FAILED,
                        failure != null ? failure : "No confirmation from " + peer.displayName());
            }
        } catch (ConnectException e) {
            session.setState(TransferSession.State.FAILED, "Cannot reach " + peer.displayName() + " on port "
                    + peer.getPort() + ". Is LAN-Caster running there and allowed through the firewall?");
        } catch (IOException e) {
            if (!cancelled) {
                logger.log(Level.FINE, "Outgoing transfer failed", e);
                session.setState(TransferSession.State.FAILED, describe(e));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeControl();
            closeDataChannels();
            engine.outgoingFinished(this);
        }
    }

    private void runStreams(FileChannel fc, InetSocketAddress addr, int count) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            final int idx = i;
            pool.execute(() -> {
                try { streamLoop(fc, addr, idx); } finally { latch.countDown(); }
            });
        }
        latch.await();
    }

    private void streamLoop(FileChannel fc, InetSocketAddress addr, int idx) {
        int attempts = 0;
        long[] current = null;
        while (!cancelled && failure == null) {
            SocketChannel sc = null;
            try {
                sc = SocketChannel.open();
                try { sc.setOption(StandardSocketOptions.SO_SNDBUF, Protocol.SOCKET_BUFFER_SIZE); } catch (IOException ignored) {}
                sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
                sc.socket().connect(addr, Protocol.CONNECT_TIMEOUT_MS);
                dataChannels.add(sc);

                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(sc), 512));
                out.writeUTF(Protocol.MAGIC);
                out.writeInt(Protocol.VERSION);
                out.writeUTF(Protocol.DATA);
                out.writeUTF(session.getId());
                out.writeInt(idx);
                out.flush();

                DataInputStream in = new DataInputStream(Channels.newInputStream(sc));
                String reply = in.readUTF();
                if (!Protocol.OK.equals(reply)) {
                    String reason = Protocol.ERROR.equals(reply) ? in.readUTF() : reply;
                    failure = "Peer refused data stream: " + reason;
                    return;
                }

                ByteBuffer header = ByteBuffer.allocate(Protocol.BLOCK_HEADER_SIZE);
                while (!cancelled && failure == null) {
                    if (current == null) current = blocks.poll();
                    if (current == null) break;
                    header.clear();
                    header.putLong(current[0]).putInt((int) current[1]).flip();
                    writeFully(sc, header);
                    sendBlock(fc, sc, current[0], current[1]);
                    current = null;
                }
                if (current == null && !cancelled && failure == null) {
                    header.clear();
                    header.putLong(Protocol.END_OF_STREAM).putInt(0).flip();
                    writeFully(sc, header);
                }
                return;
            } catch (IOException e) {
                if (cancelled || failure != null) return;
                attempts++;
                logger.log(Level.FINE, "Data stream " + idx + " failed (attempt " + attempts + ")", e);
                if (attempts > Protocol.MAX_STREAM_RETRIES) {
                    if (current != null) { blocks.add(current); current = null; }
                    return;
                }
                try { Thread.sleep(500L * attempts); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            } finally {
                if (sc != null) {
                    dataChannels.remove(sc);
                    try { sc.close(); } catch (IOException ignored) {}
                }
            }
        }
        if (current != null) blocks.add(current);
    }

    private void sendBlock(FileChannel fc, SocketChannel sc, long offset, long length) throws IOException {
        long sent = 0;
        int idleSpins = 0;
        while (sent < length) {
            if (cancelled || failure != null) throw new IOException("aborted");
            long piece = Math.min(Protocol.SEND_PIECE_SIZE, length - sent);
            long n = fc.transferTo(offset + sent, piece, sc);
            if (n <= 0) {
                // transferTo may legitimately return 0 on some platforms; fall back to a plain copy.
                if (++idleSpins > 3) {
                    n = copyPiece(fc, sc, offset + sent, (int) Math.min(1024 * 1024, piece));
                } else {
                    continue;
                }
            }
            idleSpins = 0;
            sent += n;
            session.addProgress(n);
        }
    }

    private static final ThreadLocal<ByteBuffer> COPY_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(1024 * 1024));

    private static long copyPiece(FileChannel fc, SocketChannel sc, long pos, int len) throws IOException {
        ByteBuffer buf = COPY_BUFFER.get();
        buf.clear();
        buf.limit(len);
        int read = 0;
        while (buf.hasRemaining()) {
            int r = fc.read(buf, pos + read);
            if (r < 0) break;
            read += r;
        }
        buf.flip();
        writeFully(sc, buf);
        return read;
    }

    private static void writeFully(SocketChannel sc, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) sc.write(buf);
    }

    private void controlReader(DataInputStream in) {
        try {
            while (!cancelled) {
                String msg = in.readUTF();
                if (Protocol.DONE.equals(msg)) {
                    confirmed = true;
                    confirmation.countDown();
                    return;
                } else if (Protocol.CANCEL.equals(msg) || Protocol.ERROR.equals(msg)) {
                    String reason = "";
                    try { reason = in.readUTF(); } catch (IOException ignored) {}
                    if (Protocol.CANCEL.equals(msg)) {
                        cancelled = true;
                        session.setState(TransferSession.State.CANCELLED,
                                peer.displayName() + " cancelled" + (reason.isEmpty() ? "" : " (" + reason + ")"));
                    } else {
                        failure = reason.isEmpty() ? "Peer reported an error" : reason;
                    }
                    closeDataChannels();
                    confirmation.countDown();
                    return;
                }
            }
        } catch (IOException e) {
            if (!confirmed && !cancelled && failure == null) {
                failure = "Connection to " + peer.displayName() + " was lost";
                closeDataChannels();
            }
            confirmation.countDown();
        }
    }

    /** User pressed cancel. */
    public void cancel() {
        if (cancelled || session.getState().isTerminal()) return;
        cancelled = true;
        sendControl(Protocol.CANCEL, "Cancelled by " + engine.getDeviceName());
        session.setState(TransferSession.State.CANCELLED, "Cancelled");
        closeDataChannels();
        closeControl();
        confirmation.countDown();
    }

    /** Watchdog: no progress for a long time. */
    public void fail(String reason) {
        if (cancelled || session.getState().isTerminal()) return;
        failure = reason;
        sendControl(Protocol.ERROR, reason);
        closeDataChannels();
        confirmation.countDown();
    }

    private void sendControl(String type, String message) {
        DataOutputStream out = ctlOut;
        if (out == null) return;
        try {
            synchronized (out) {
                out.writeUTF(type);
                out.writeUTF(message == null ? "" : message);
                out.flush();
            }
        } catch (IOException ignored) {}
    }

    private void closeDataChannels() {
        List<SocketChannel> copy = new ArrayList<>(dataChannels);
        for (SocketChannel sc : copy) {
            try { sc.close(); } catch (IOException ignored) {}
        }
    }

    private void closeControl() {
        Socket s = control;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        if (e instanceof SocketTimeoutException) return "Connection timed out";
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
