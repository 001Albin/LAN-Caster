package org.develop.lancaster.core.discovery;

import org.develop.lancaster.core.network.Protocol;
import org.develop.lancaster.core.util.NetworkUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Peer discovery over UDP.
 *
 * <p>Every two seconds each instance announces itself with
 * {@code LAN-CASTER_HELLO|id|base64(name)|port|os|protocolVersion}. The
 * announcement is sent as a multicast datagram on <em>every</em> eligible
 * interface (not just the first one) and, as a fallback for networks that filter
 * multicast, as a subnet broadcast on each interface. The listener joins the
 * multicast group on every interface and, because it is bound to the wildcard
 * address, also receives the broadcasts.
 *
 * <p>Sending per interface is what makes discovery work on Linux machines with
 * Docker/VPN adapters or without a default route: previously the packet left on
 * whatever interface the kernel chose and often never reached the Wi-Fi.
 */
public final class DiscoveryService implements Runnable {

    private static final Logger logger = Logger.getLogger(DiscoveryService.class.getName());
    private static final String HELLO = "LAN-CASTER_HELLO";
    private static final long ANNOUNCE_INTERVAL_MS = 2_000;

    private final String instanceId;
    private final IntSupplier portSupplier;
    private volatile String localName;
    private volatile boolean running = true;
    private volatile Consumer<PeerInfo> onPeerFound;
    private volatile MulticastSocket listenSocket;
    private volatile String lastError;

    public DiscoveryService(String instanceId, String localName, IntSupplier portSupplier) {
        this.instanceId = instanceId;
        this.localName = localName;
        this.portSupplier = portSupplier;
    }

    public void setOnPeerFound(Consumer<PeerInfo> callback) { this.onPeerFound = callback; }

    public void setLocalName(String name) {
        this.localName = (name == null || name.trim().isEmpty()) ? null : name.trim();
    }

    public String getLastError() { return lastError; }

    /** Starts the listener and the periodic announcer on daemon threads. */
    public void start() {
        Thread listener = new Thread(this, "discovery-listener");
        listener.setDaemon(true);
        listener.start();

        Thread announcer = new Thread(() -> {
            while (running) {
                broadcastPresence();
                try { Thread.sleep(ANNOUNCE_INTERVAL_MS); } catch (InterruptedException e) { return; }
            }
        }, "discovery-announcer");
        announcer.setDaemon(true);
        announcer.start();
    }

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(null)) {
            listenSocket = socket;
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Protocol.DISCOVERY_PORT));
            socket.setSoTimeout(1_000);
            InetAddress group = InetAddress.getByName(Protocol.MULTICAST_GROUP);

            List<NetworkInterface> joined = new ArrayList<>();
            for (NetworkInterface ni : NetworkUtils.eligibleInterfaces()) {
                try {
                    socket.joinGroup(new InetSocketAddress(group, Protocol.DISCOVERY_PORT), ni);
                    joined.add(ni);
                } catch (IOException e) {
                    logger.log(Level.FINE, "Could not join multicast group on " + ni.getName(), e);
                }
            }
            logger.info(() -> "[Discovery] Listening on UDP " + Protocol.DISCOVERY_PORT
                    + ", multicast joined on " + joined.size() + " interface(s)");
            if (joined.isEmpty()) lastError = "No network interface available for discovery";

            byte[] buffer = new byte[1024];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                handlePacket(packet);
            }
            for (NetworkInterface ni : joined) {
                try { socket.leaveGroup(new InetSocketAddress(group, Protocol.DISCOVERY_PORT), ni); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            if (running) {
                lastError = "Discovery listener failed: " + e.getMessage();
                logger.log(Level.WARNING, "Discovery service IO error", e);
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        String[] parts = message.split("\\|");
        if (parts.length < 2 || !HELLO.equals(parts[0])) return;

        String senderId = parts[1];
        if (senderId.equals(instanceId)) return;

        String senderIp = packet.getAddress().getHostAddress();
        String name = parts.length > 2 ? decodeName(parts[2]) : null;
        int port = parts.length > 3 ? parseInt(parts[3], Protocol.DEFAULT_PORT) : Protocol.DEFAULT_PORT;
        String os = parts.length > 4 ? parts[4] : "";
        int version = parts.length > 5 ? parseInt(parts[5], 1) : 1;

        PeerInfo pi = new PeerInfo(senderId, senderIp, port, name, os, version, false);
        Consumer<PeerInfo> cb = onPeerFound;
        if (cb != null) cb.accept(pi);
    }

    private static String decodeName(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            String n = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).trim();
            return n.isEmpty() ? null : n;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    /** Sends one announcement on every eligible interface (multicast + subnet broadcast). */
    public void broadcastPresence() {
        String name = localName;
        String encoded = (name == null || name.isEmpty()) ? ""
                : Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        String payload = HELLO + "|" + instanceId + "|" + encoded + "|" + portSupplier.getAsInt()
                + "|" + NetworkUtils.osTag() + "|" + Protocol.VERSION;
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);

        InetAddress group;
        try {
            group = InetAddress.getByName(Protocol.MULTICAST_GROUP);
        } catch (IOException e) {
            return;
        }

        List<NetworkInterface> interfaces = NetworkUtils.eligibleInterfaces();
        if (interfaces.isEmpty()) {
            lastError = "No network interface found. Connect to Wi-Fi or Ethernet.";
            return;
        }
        int sent = 0;
        for (NetworkInterface ni : interfaces) {
            // multicast, pinned to this interface
            try (MulticastSocket ms = new MulticastSocket()) {
                ms.setNetworkInterface(ni);
                ms.setTimeToLive(1);
                ms.send(new DatagramPacket(data, data.length, group, Protocol.DISCOVERY_PORT));
                sent++;
            } catch (IOException e) {
                logger.log(Level.FINE, "Multicast send failed on " + ni.getName(), e);
            }
            // subnet broadcast fallback
            for (InetAddress bcast : NetworkUtils.broadcastAddresses(ni)) {
                try (DatagramSocket ds = new DatagramSocket()) {
                    ds.setBroadcast(true);
                    ds.send(new DatagramPacket(data, data.length, bcast, Protocol.DISCOVERY_PORT));
                    sent++;
                } catch (IOException e) {
                    logger.log(Level.FINE, "Broadcast send failed on " + ni.getName(), e);
                }
            }
        }
        if (sent == 0) lastError = "Could not send discovery packets on any interface";
        else lastError = null;
    }

    public void stop() {
        running = false;
        MulticastSocket s = listenSocket;
        if (s != null) s.close();
    }
}
