package org.develop.lancaster.core.discovery;

import org.develop.lancaster.core.util.NetworkUtils;
import org.develop.lancaster.core.util.Config;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscoveryService implements Runnable {

    private static final String MULTICAST_GROUP_IP = "224.0.0.1";
    private static final int PORT = 8888;
    private final String myUniqueId;
    private volatile boolean running = true;

    private static final Logger logger = Logger.getLogger(DiscoveryService.class.getName());

    // Callback to notify the UI with PeerInfo
    private Consumer<PeerInfo> onPeerFound;

    // optional human-friendly local name
    private volatile String localName;

    public DiscoveryService() {
        this.myUniqueId = UUID.randomUUID().toString();
        try {
            this.localName = Config.getDeviceName();
        } catch (Exception e) {
            this.localName = null;
        }
    }

    public void setOnPeerFound(Consumer<PeerInfo> callback) {
        this.onPeerFound = callback;
    }

    public void setLocalName(String name) {
        this.localName = (name == null || name.trim().isEmpty()) ? null : name.trim();
    }

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            NetworkInterface netIf = NetworkUtils.findInterface();
            socket.joinGroup(new InetSocketAddress(group, PORT), netIf);

            logger.info(() -> "[Discovery] Listening on " + MULTICAST_GROUP_IP + ":" + PORT);

            byte[] buffer = new byte[1024];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                String[] parts = message.split("\\|", 3);

                if (parts.length >= 2 && parts[0].equals("LAN-CASTER_HELLO")) {
                    String senderId = parts[1];
                    String senderIp = packet.getAddress().getHostAddress();

                    if (!senderId.equals(myUniqueId)) {
                        String remoteName = null;

                        if (parts.length == 3 && parts[2] != null && !parts[2].isEmpty()) {
                            try {
                                byte[] decoded = Base64.getUrlDecoder().decode(parts[2]);
                                String name = new String(decoded, StandardCharsets.UTF_8);
                                if (name != null && !name.trim().isEmpty()) {
                                    remoteName = name.trim();
                                }
                            } catch (IllegalArgumentException ignored) {
                                // malformed name, ignore
                                logger.log(Level.FINE, "Malformed name token from " + senderIp, ignored);
                            }
                        }

                        PeerInfo pi = new PeerInfo(senderIp, remoteName);

                        if (onPeerFound != null) {
                            onPeerFound.accept(pi);
                        }
                    }
                }
            }
            socket.leaveGroup(new InetSocketAddress(group, PORT), netIf);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Discovery service IO error", e);
        }
    }

    public void broadcastPresence() {
        try (DatagramSocket socket = new DatagramSocket()) {
            String payload;
            if (localName == null || localName.isEmpty()) {
                payload = "LAN-CASTER_HELLO|" + myUniqueId;
            } else {
                String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(localName.getBytes(StandardCharsets.UTF_8));
                payload = "LAN-CASTER_HELLO|" + myUniqueId + "|" + encoded;
            }
            byte[] buffer = payload.getBytes();
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, PORT);
            socket.send(packet);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to broadcast presence", e);
        }
    }

    public void stop() {
        this.running = false;
    }
}