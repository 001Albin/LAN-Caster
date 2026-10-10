package org.develop.lancaster.core.transfer;

import org.develop.lancaster.core.discovery.PeerInfo;
import org.develop.lancaster.core.network.PeerServer;
import org.develop.lancaster.core.network.Protocol;
import org.develop.lancaster.core.util.Config;
import org.develop.lancaster.core.util.NetworkUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the listening server and all running transfers. This is the only class
 * the UI talks to for sending and receiving.
 */
public final class TransferEngine implements PeerServer.Handler {
    private static final Logger logger = Logger.getLogger(TransferEngine.class.getName());

    private final String instanceId;
    private volatile String deviceName;
    private final ExecutorService pool;
    private final ScheduledExecutorService scheduler;
    private final PeerServer server;

    private final Map<String, TransferSession> sessions = new ConcurrentHashMap<>();
    private final List<TransferSession> ordered = new CopyOnWriteArrayList<>();
    private final Map<String, IncomingTransfer> incoming = new ConcurrentHashMap<>();
    private final Map<String, OutgoingTransfer> outgoing = new ConcurrentHashMap<>();
    private volatile Consumer<TransferSession> sessionCreatedListener;

    public TransferEngine(String instanceId, String deviceName) {
        this.instanceId = instanceId;
        this.deviceName = deviceName;
        this.pool = Executors.newCachedThreadPool(daemon("lancaster-io"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("lancaster-watchdog"));
        this.server = new PeerServer(this, pool);
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Starts listening. Returns the bound port. */
    public int start() throws IOException {
        int port = server.start(Protocol.DEFAULT_PORT);
        scheduler.scheduleWithFixedDelay(this::watchdog, 1, 1, TimeUnit.SECONDS);
        return port;
    }

    public int getPort() { return server.getPort(); }
    public String getInstanceId() { return instanceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String name) { if (name != null && !name.isBlank()) this.deviceName = name; }

    public void setSessionCreatedListener(Consumer<TransferSession> l) { this.sessionCreatedListener = l; }
    public List<TransferSession> getSessions() { return new ArrayList<>(ordered); }
    public TransferSession getSession(String id) { return sessions.get(id); }

    // ------------------------------------------------------------------ sending

    public List<TransferSession> sendFiles(Collection<File> files, Collection<PeerInfo> peers) {
        List<TransferSession> created = new ArrayList<>();
        for (File f : files) {
            for (PeerInfo p : peers) {
                created.add(sendFile(f, p));
            }
        }
        return created;
    }

    public TransferSession sendFile(File file, PeerInfo peer) {
        TransferSession session = new TransferSession(null, TransferSession.Direction.SEND, file.getName(),
                file.length(), peer.key(), peer.getIp(), peer.displayName(), TransferSession.State.CONNECTING);
        session.setLocalFile(file.toPath());
        session.setPeerPort(peer.getPort());
        register(session);
        OutgoingTransfer ot = new OutgoingTransfer(this, session, file, peer, pool);
        outgoing.put(session.getId(), ot);
        pool.execute(ot);
        return session;
    }

    void outgoingFinished(OutgoingTransfer ot) {
        outgoing.remove(ot.getSession().getId());
    }

    // ------------------------------------------------------------------ receiving (server callbacks)

    @Override
    public void onOffer(Socket socket, DataInputStream in, DataOutputStream out) throws IOException {
        Offer offer = Offer.read(in);
        String peerIp = socket.getInetAddress().getHostAddress();
        String name = Config.getPeerName(peerIp);
        if (name == null) name = offer.senderName.isEmpty() ? peerIp : offer.senderName;

        if (!Config.isPaired(offer.senderId)) {
            out.writeUTF(Protocol.DECLINE);
            out.writeUTF(deviceName + " is not paired with you. Pair the devices first (select the device and choose Pair).");
            out.flush();
            Consumer<String> l = unpairedOfferListener;
            if (l != null) l.accept(name + " (" + peerIp + ") tried to send \"" + offer.fileName
                    + "\" but is not paired. Right-click the device and choose Pair to allow it.");
            return;
        }

        TransferSession session = new TransferSession(offer.transferId, TransferSession.Direction.RECEIVE,
                offer.fileName, offer.fileSize, offer.senderId.isEmpty() ? peerIp : offer.senderId,
                peerIp, name, TransferSession.State.INCOMING_OFFER);
        session.setMessage(name + " wants to send you a file");
        IncomingTransfer it = new IncomingTransfer(this, session, offer, socket, in, out);
        incoming.put(session.getId(), it);
        register(session);
        it.runControl();
    }

    @Override
    public void onData(SocketChannel channel, String transferId, int streamIndex) throws IOException {
        IncomingTransfer it = incoming.get(transferId);
        if (it == null) {
            DataOutputStream out = new DataOutputStream(channel.socket().getOutputStream());
            out.writeUTF(Protocol.ERROR);
            out.writeUTF("Unknown transfer");
            out.flush();
            return;
        }
        it.attachStream(channel, streamIndex);
    }

    // ------------------------------------------------------------------ pairing

    /** An incoming request to pair, shown to the user for confirmation. */
    public static final class PairingRequest {
        public final String peerId;
        public final String peerName;
        public final String peerIp;
        public final int peerPort;
        public final String code;
        PairingRequest(String peerId, String peerName, String peerIp, int peerPort, String code) {
            this.peerId = peerId; this.peerName = peerName; this.peerIp = peerIp; this.peerPort = peerPort; this.code = code;
        }
    }

    public static final class PairResult {
        public final boolean ok;
        public final String message;
        public final String peerId;
        public final String peerName;
        PairResult(boolean ok, String message, String peerId, String peerName) {
            this.ok = ok; this.message = message; this.peerId = peerId; this.peerName = peerName;
        }
    }

    private volatile Function<PairingRequest, CompletableFuture<Boolean>> pairingHandler;
    private volatile Consumer<String> unpairedOfferListener;

    /** The UI decides whether an incoming pairing request is accepted. */
    public void setPairingHandler(Function<PairingRequest, CompletableFuture<Boolean>> h) { this.pairingHandler = h; }

    /** Notified when an unpaired device tried to send a file (so the UI can explain). */
    public void setUnpairedOfferListener(Consumer<String> l) { this.unpairedOfferListener = l; }

    @Override
    public void onPair(Socket socket, DataInputStream in, DataOutputStream out) throws IOException {
        String peerId = in.readUTF();
        String peerName = in.readUTF();
        String code = in.readUTF();
        int peerPort = in.readInt();
        String peerIp = socket.getInetAddress().getHostAddress();
        if (peerId.isBlank()) {
            reply(out, Protocol.DECLINE, "Missing device id");
            return;
        }
        if (peerName.isBlank()) peerName = peerIp;

        boolean ok = Config.isPaired(peerId);
        if (!ok) {
            Function<PairingRequest, CompletableFuture<Boolean>> h = pairingHandler;
            if (h == null) {
                reply(out, Protocol.DECLINE, deviceName + " cannot pair right now");
                return;
            }
            socket.setSoTimeout(0);
            try {
                ok = Boolean.TRUE.equals(h.apply(new PairingRequest(peerId, peerName, peerIp, peerPort, code))
                        .get(Protocol.ACCEPT_WAIT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                ok = false;
            }
        }
        if (ok) {
            Config.addPaired(peerId, peerName);
            out.writeUTF(Protocol.PAIR_OK);
            out.writeUTF(instanceId);
            out.writeUTF(deviceName);
            out.flush();
        } else {
            reply(out, Protocol.DECLINE, "Pairing was rejected on " + deviceName);
        }
    }

    private static void reply(DataOutputStream out, String type, String message) throws IOException {
        out.writeUTF(type);
        out.writeUTF(message);
        out.flush();
    }

    /**
     * Asks {@code peer} to pair with us. {@code onCode} receives the 6-digit code the
     * user must confirm on the other device.
     */
    public CompletableFuture<PairResult> requestPairing(PeerInfo peer, Consumer<String> onCode) {
        CompletableFuture<PairResult> result = new CompletableFuture<>();
        String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        if (onCode != null) onCode.accept(code);
        pool.execute(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(peer.getIp(), peer.getPort()), Protocol.CONNECT_TIMEOUT_MS);
                s.setSoTimeout(Protocol.ACCEPT_WAIT_MS + 10_000);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream());
                out.writeUTF(Protocol.MAGIC);
                out.writeInt(Protocol.VERSION);
                out.writeUTF(Protocol.PAIR);
                out.writeUTF(instanceId);
                out.writeUTF(deviceName);
                out.writeUTF(code);
                out.writeInt(getPort());
                out.flush();
                String reply = in.readUTF();
                if (Protocol.PAIR_OK.equals(reply)) {
                    String id = in.readUTF();
                    String name = in.readUTF();
                    if (name.isBlank()) name = peer.displayName();
                    Config.addPaired(id, name);
                    result.complete(new PairResult(true, "Paired with " + name, id, name));
                } else {
                    String reason = in.readUTF();
                    result.complete(new PairResult(false, reason, peer.getId(), peer.displayName()));
                }
            } catch (IOException e) {
                String m = e instanceof java.net.SocketTimeoutException ? "No answer from " + peer.displayName()
                        : "Could not reach " + peer.displayName() + " (" + e.getMessage() + ")";
                result.complete(new PairResult(false, m, peer.getId(), peer.displayName()));
            }
        });
        return result;
    }

    public void unpair(String peerId) { Config.removePaired(peerId); }

    @Override
    public PeerServer.PingInfo onPing() {
        return new PeerServer.PingInfo(instanceId, deviceName, NetworkUtils.osTag());
    }

    void incomingFinished(IncomingTransfer it) {
        incoming.remove(it.getSession().getId());
    }

    // ------------------------------------------------------------------ misc

    /** Connects to a peer by address and asks who it is. */
    public PeerInfo ping(String ip, int port) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), Protocol.CONNECT_TIMEOUT_MS);
            s.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.writeUTF(Protocol.MAGIC);
            out.writeInt(Protocol.VERSION);
            out.writeUTF(Protocol.PING);
            out.flush();
            String reply = in.readUTF();
            if (Protocol.ERROR.equals(reply)) throw new IOException(in.readUTF());
            if (!Protocol.PONG.equals(reply)) throw new IOException("Not a LAN-Caster peer");
            String id = in.readUTF();
            String name = in.readUTF();
            String os = in.readUTF();
            int version = in.readInt();
            return new PeerInfo(id, ip, port, name, os, version, true);
        }
    }

    private void register(TransferSession session) {
        sessions.put(session.getId(), session);
        ordered.add(session);
        Consumer<TransferSession> l = sessionCreatedListener;
        if (l != null) {
            try { l.accept(session); } catch (RuntimeException e) { logger.log(Level.WARNING, "Listener failed", e); }
        }
    }

    public void removeSession(TransferSession session) {
        if (session.isActive()) return;
        sessions.remove(session.getId());
        ordered.remove(session);
    }

    public void clearFinished() {
        for (TransferSession s : ordered) {
            if (!s.isActive()) removeSession(s);
        }
    }

    private void watchdog() {
        long now = System.currentTimeMillis();
        for (IncomingTransfer it : incoming.values()) {
            TransferSession s = it.getSession();
            it.flushState();
            if (s.getState() == TransferSession.State.TRANSFERRING && now - s.getLastProgressAt() > Protocol.STALL_TIMEOUT_MS) {
                it.fail("No data received for " + (Protocol.STALL_TIMEOUT_MS / 1000) + " seconds. Send again to resume.");
            }
        }
        for (OutgoingTransfer ot : outgoing.values()) {
            TransferSession s = ot.getSession();
            if (s.getState() == TransferSession.State.TRANSFERRING && now - s.getLastProgressAt() > Protocol.STALL_TIMEOUT_MS) {
                ot.fail("Peer stopped receiving data for " + (Protocol.STALL_TIMEOUT_MS / 1000) + " seconds");
            }
        }
    }

    public void shutdown() {
        for (OutgoingTransfer ot : outgoing.values()) ot.cancel();
        for (IncomingTransfer it : incoming.values()) it.cancel();
        server.stop();
        scheduler.shutdownNow();
        pool.shutdownNow();
    }
}
