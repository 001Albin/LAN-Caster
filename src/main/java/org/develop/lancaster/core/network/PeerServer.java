package org.develop.lancaster.core.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The always-on TCP listener of a LAN-Caster instance. Every connection starts
 * with the {@link Protocol} handshake and is then handed to the
 * {@link Handler} according to its kind.
 */
public final class PeerServer {
    private static final Logger logger = Logger.getLogger(PeerServer.class.getName());

    public interface Handler {
        /** A sender offers a file. The handler owns the socket and must close it. */
        void onOffer(Socket socket, DataInputStream in, DataOutputStream out) throws IOException;

        /** A sender opened a data stream for a transfer. The handler owns the channel. */
        void onData(SocketChannel channel, String transferId, int streamIndex) throws IOException;

        /** A device asks to pair. The handler owns the socket and must answer. */
        void onPair(Socket socket, DataInputStream in, DataOutputStream out) throws IOException;

        /** Identity reported to PING requests. */
        PingInfo onPing();
    }

    public static final class PingInfo {
        public final String id;
        public final String name;
        public final String os;
        public PingInfo(String id, String name, String os) { this.id = id; this.name = name; this.os = os; }
    }

    private final Handler handler;
    private final ExecutorService pool;
    private volatile ServerSocketChannel server;
    private volatile boolean running;
    private volatile int port = -1;

    public PeerServer(Handler handler, ExecutorService pool) {
        this.handler = handler;
        this.pool = pool != null ? pool : Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "peer-server-io");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Binds to {@code preferredPort}, falling back to any free port if it is taken.
     * @return the port actually bound
     */
    public synchronized int start(int preferredPort) throws IOException {
        if (running) return port;
        ServerSocketChannel ssc = ServerSocketChannel.open();
        try {
            // Must be set before bind so accepted sockets inherit a large window.
            ssc.setOption(StandardSocketOptions.SO_RCVBUF, Protocol.SOCKET_BUFFER_SIZE);
        } catch (IOException | UnsupportedOperationException e) {
            logger.log(Level.FINE, "Could not set SO_RCVBUF", e);
        }
        try {
            ssc.bind(new InetSocketAddress(preferredPort), 64);
        } catch (IOException e) {
            logger.info("Port " + preferredPort + " is busy, choosing a free port instead");
            ssc.bind(new InetSocketAddress(0), 64);
        }
        this.server = ssc;
        this.port = ssc.socket().getLocalPort();
        this.running = true;

        Thread acceptor = new Thread(this::acceptLoop, "peer-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        logger.info("[PeerServer] Listening on TCP port " + port);
        return port;
    }

    public int getPort() { return port; }
    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel sc = server.accept();
                pool.execute(() -> handle(sc));
            } catch (IOException e) {
                if (running) logger.log(Level.WARNING, "Accept failed", e);
            }
        }
    }

    private void handle(SocketChannel sc) {
        Socket socket = sc.socket();
        boolean handedOff = false;
        try {
            sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
            socket.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            String magic = in.readUTF();
            if (!Protocol.MAGIC.equals(magic)) {
                logger.fine("Rejected connection with bad magic from " + socket.getInetAddress());
                return;
            }
            int version = in.readInt();
            String kind = in.readUTF();
            if (version != Protocol.VERSION) {
                out.writeUTF(Protocol.ERROR);
                out.writeUTF("Incompatible LAN-Caster version (peer " + version + ", mine " + Protocol.VERSION + ")");
                out.flush();
                return;
            }

            switch (kind) {
                case Protocol.OFFER:
                    handedOff = true;
                    handler.onOffer(socket, in, out);
                    break;
                case Protocol.DATA: {
                    String transferId = in.readUTF();
                    int streamIndex = in.readInt();
                    socket.setSoTimeout(0);
                    handedOff = true;
                    handler.onData(sc, transferId, streamIndex);
                    break;
                }
                case Protocol.PAIR:
                    handedOff = true;
                    handler.onPair(socket, in, out);
                    break;
                case Protocol.PING: {
                    PingInfo me = handler.onPing();
                    out.writeUTF(Protocol.PONG);
                    out.writeUTF(me.id == null ? "" : me.id);
                    out.writeUTF(me.name == null ? "" : me.name);
                    out.writeUTF(me.os == null ? "" : me.os);
                    out.writeInt(Protocol.VERSION);
                    out.flush();
                    break;
                }
                default:
                    logger.fine("Unknown connection kind " + kind);
            }
        } catch (IOException e) {
            if (!handedOff) logger.log(Level.FINE, "Handshake failed from " + socket.getInetAddress(), e);
            else logger.log(Level.FINE, "Connection ended", e);
        } finally {
            try { sc.close(); } catch (IOException ignored) {}
        }
    }

    public synchronized void stop() {
        running = false;
        ServerSocketChannel s = server;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
