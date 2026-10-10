package org.develop.lancaster.core.transfer;

import org.develop.lancaster.core.network.Protocol;
import org.develop.lancaster.core.util.Config;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receiver side of one transfer. Lives on the server thread that accepted the
 * OFFER connection; data streams attach from other server threads.
 */
public final class IncomingTransfer {
    private static final Logger logger = Logger.getLogger(IncomingTransfer.class.getName());

    private final TransferEngine engine;
    private final TransferSession session;
    private final Offer offer;
    private final Socket control;
    private final DataInputStream ctlIn;
    private final DataOutputStream ctlOut;

    private volatile Path finalPath;
    private volatile Path partPath;
    private volatile Path statePath;
    private volatile RandomAccessFile raf;
    private volatile FileChannel fc;
    private volatile ResumeState state;

    private final Set<SocketChannel> streams = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeStreams = new AtomicInteger();
    private final AtomicBoolean finishOnce = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile boolean finished;

    public IncomingTransfer(TransferEngine engine, TransferSession session, Offer offer,
                            Socket control, DataInputStream ctlIn, DataOutputStream ctlOut) {
        this.engine = engine;
        this.session = session;
        this.offer = offer;
        this.control = control;
        this.ctlIn = ctlIn;
        this.ctlOut = ctlOut;
    }

    public TransferSession getSession() { return session; }

    /** Runs the control connection: decision, ACCEPT/DECLINE, then status messages. */
    public void runControl() {
        session.setCanceller(this::cancel);
        try {
            TransferSession.Decision decision = awaitDecision();
            if (!decision.accept) {
                sendControl(Protocol.DECLINE, "Declined by " + engine.getDeviceName());
                if (session.getState() == TransferSession.State.INCOMING_OFFER) {
                    session.setState(TransferSession.State.DECLINED, "You declined this file");
                }
                return;
            }

            try {
                prepare(decision.directory);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Cannot prepare download", e);
                sendControl(Protocol.DECLINE, "Receiver cannot write to its download folder: " + e.getMessage());
                session.setState(TransferSession.State.FAILED, "Cannot write to " + decision.directory + ": " + e.getMessage());
                return;
            }

            List<long[]> missing = state.missingRanges();
            int streamCount = Math.max(1, Math.min(Config.resolveParallelStreams(), Math.max(1, state.getTotalBlocks())));
            try {
                synchronized (ctlOut) {
                    ctlOut.writeUTF(Protocol.ACCEPT);
                    ctlOut.writeInt(streamCount);
                    ctlOut.writeInt(missing.size());
                    for (long[] r : missing) {
                        ctlOut.writeLong(r[0]);
                        ctlOut.writeLong(r[1]);
                    }
                    ctlOut.flush();
                }
                control.setSoTimeout(0);
            } catch (IOException e) {
                abort("Connection to " + session.getPeerName() + " was lost before the transfer started",
                        TransferSession.State.FAILED);
                return;
            }

            long already = state.doneBytes();
            session.setBytesDone(already);
            session.setResumed(already > 0);
            session.setStreams(streamCount);

            if (state.isComplete()) {
                finish();
                return;
            }
            session.setState(TransferSession.State.TRANSFERRING,
                    (already > 0 ? "Resuming from " : "Receiving from ") + session.getPeerName());

            // Status messages from the sender until the transfer ends.
            while (!finished && !cancelled) {
                String msg;
                try {
                    msg = ctlIn.readUTF();
                } catch (IOException e) {
                    if (!finished && !cancelled) {
                        abort("Connection to " + session.getPeerName() + " was lost. Send the file again to resume.",
                                TransferSession.State.FAILED);
                    }
                    break;
                }
                if (Protocol.CANCEL.equals(msg)) {
                    String reason = readReason();
                    abort(session.getPeerName() + " cancelled the transfer" + (reason.isEmpty() ? "" : " (" + reason + ")"),
                            TransferSession.State.CANCELLED);
                } else if (Protocol.ERROR.equals(msg)) {
                    String reason = readReason();
                    abort("Sender reported an error: " + reason, TransferSession.State.FAILED);
                }
            }
        } finally {
            closeControl();
            engine.incomingFinished(this);
        }
    }

    private String readReason() {
        try { return ctlIn.readUTF(); } catch (IOException e) { return ""; }
    }

    private TransferSession.Decision awaitDecision() {
        if (Config.isAutoAccept()) {
            session.accept(Config.getDownloadDir());
        }
        try {
            return session.decision().get(Protocol.ACCEPT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            session.setState(TransferSession.State.FAILED, "Not accepted in time");
            return TransferSession.Decision.decline();
        } catch (InterruptedException | ExecutionException e) {
            return TransferSession.Decision.decline();
        }
    }

    private void prepare(Path dir) throws IOException {
        Files.createDirectories(dir);
        String safeName = sanitizeFileName(offer.fileName);
        finalPath = dir.resolve(safeName);
        partPath = dir.resolve(safeName + ".lcpart");
        statePath = dir.resolve(safeName + ".lcstate");

        ResumeState loaded = ResumeState.load(statePath, offer.fileSize, offer.blockSize, offer.lastModified);
        boolean resume = loaded != null && Files.isRegularFile(partPath) && Files.size(partPath) == offer.fileSize;
        state = resume ? loaded : ResumeState.fresh(offer.fileSize, offer.blockSize, offer.lastModified);

        raf = new RandomAccessFile(partPath.toFile(), "rw");
        if (raf.length() != offer.fileSize) raf.setLength(offer.fileSize);
        fc = raf.getChannel();
        session.setLocalFile(finalPath);
        if (!resume) {
            try { state.save(statePath); } catch (IOException e) { logger.log(Level.FINE, "Could not save state", e); }
        }
    }

    /** Serves one data stream. Runs on the server thread that accepted it. */
    public void attachStream(SocketChannel sc, int streamIndex) throws IOException {
        DataOutputStream out = new DataOutputStream(sc.socket().getOutputStream());
        if (finished || cancelled || fc == null) {
            out.writeUTF(Protocol.ERROR);
            out.writeUTF("Transfer is not active");
            out.flush();
            return;
        }
        out.writeUTF(Protocol.OK);
        out.flush();

        streams.add(sc);
        activeStreams.incrementAndGet();
        ByteBuffer header = ByteBuffer.allocate(Protocol.BLOCK_HEADER_SIZE);
        ByteBuffer buf = ByteBuffer.allocateDirect(Protocol.IO_BUFFER_SIZE);
        try {
            while (!finished && !cancelled) {
                header.clear();
                if (!readFully(sc, header)) break; // orderly EOF
                header.flip();
                long offset = header.getLong();
                int length = header.getInt();
                if (offset == Protocol.END_OF_STREAM) break;
                if (offset < 0 || length <= 0 || offset + length > offer.fileSize || offset % offer.blockSize != 0) {
                    throw new IOException("Invalid block header " + offset + "/" + length);
                }
                int blockIdx = state.blockIndex(offset);
                if (length != state.blockLength(blockIdx)) {
                    throw new IOException("Block " + blockIdx + " has unexpected length " + length);
                }

                long remaining = length;
                long pos = offset;
                while (remaining > 0) {
                    buf.clear();
                    buf.limit((int) Math.min(buf.capacity(), remaining));
                    int n = sc.read(buf);
                    if (n < 0) throw new EOFException("Connection closed in the middle of a block");
                    if (n == 0) continue;
                    buf.flip();
                    while (buf.hasRemaining()) pos += fc.write(buf, pos);
                    remaining -= n;
                    session.addProgress(n);
                }
                if (state.markDone(blockIdx) && state.isComplete()) {
                    finish();
                }
            }
        } catch (IOException e) {
            if (!finished && !cancelled) {
                logger.log(Level.FINE, "Data stream " + streamIndex + " ended with error", e);
            }
        } finally {
            streams.remove(sc);
            activeStreams.decrementAndGet();
        }
    }

    private static boolean readFully(SocketChannel sc, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = sc.read(buf);
            if (n < 0) {
                if (buf.position() == 0) return false;
                throw new EOFException("Truncated block header");
            }
        }
        return true;
    }

    private void finish() {
        if (!finishOnce.compareAndSet(false, true)) return;
        session.setState(TransferSession.State.FINALIZING, "Saving file…");
        Path target = finalPath;
        try {
            fc.force(true);
            closeFile();
            target = uniqueTarget(finalPath);
            try {
                Files.move(partPath, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(partPath, target);
            }
            Files.deleteIfExists(statePath);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Finalizing download failed", e);
            finished = true;
            sendControl(Protocol.ERROR, "Could not save file: " + e.getMessage());
            session.setState(TransferSession.State.FAILED, "Could not save file: " + e.getMessage());
            closeStreams();
            return;
        }
        session.setLocalFile(target);
        session.setBytesDone(offer.fileSize);
        finished = true;
        sendControl(Protocol.DONE, "");
        session.setState(TransferSession.State.COMPLETED, "Saved to " + target.toAbsolutePath());
        closeStreams();
        closeControl();
    }

    private static Path uniqueTarget(Path wanted) {
        if (!Files.exists(wanted)) return wanted;
        String name = wanted.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path p = wanted.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(p)) return p;
        }
        return wanted.resolveSibling(base + "-" + System.currentTimeMillis() + ext);
    }

    static String sanitizeFileName(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        return n.isEmpty() ? "download" : n;
    }

    /** User pressed cancel. The partial file is kept so the transfer can resume. */
    public void cancel() {
        if (finished || cancelled) return;
        sendControl(Protocol.CANCEL, "Cancelled by " + engine.getDeviceName());
        abort("Cancelled. Send the file again to resume.", TransferSession.State.CANCELLED);
    }

    /** Watchdog / internal failure. */
    public void fail(String reason) {
        if (finished || cancelled) return;
        sendControl(Protocol.ERROR, reason);
        abort(reason, TransferSession.State.FAILED);
    }

    private synchronized void abort(String reason, TransferSession.State newState) {
        if (finished || cancelled) return;
        cancelled = true;
        closeStreams();
        flushState();
        closeFile();
        session.setState(newState, reason);
        closeControl();
    }

    public void flushState() {
        ResumeState s = state;
        Path p = statePath;
        if (s != null && p != null && s.isDirty() && !finished) {
            try { s.save(p); } catch (IOException e) { logger.log(Level.FINE, "Could not save resume state", e); }
        }
    }

    private void sendControl(String type, String message) {
        try {
            synchronized (ctlOut) {
                ctlOut.writeUTF(type);
                ctlOut.writeUTF(message == null ? "" : message);
                ctlOut.flush();
            }
        } catch (IOException ignored) {
            // the peer is gone; nothing to do
        }
    }

    private void closeStreams() {
        for (SocketChannel sc : streams) {
            try { sc.close(); } catch (IOException ignored) {}
        }
    }

    private void closeFile() {
        try { if (fc != null && fc.isOpen()) fc.close(); } catch (IOException ignored) {}
        try { if (raf != null) raf.close(); } catch (IOException ignored) {}
    }

    private void closeControl() {
        try { control.close(); } catch (IOException ignored) {}
    }
}
