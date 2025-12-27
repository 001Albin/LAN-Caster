package org.develop.lancaster.core.discovery;

import org.develop.lancaster.core.util.NetworkUtils;
import java.io.IOException;
import java.net.*;
import java.util.UUID;
import java.util.function.Consumer;

public class DiscoveryService implements Runnable {

    private static final String MULTICAST_GROUP_IP = "224.0.0.1";
    private static final int PORT = 8888;
    private final String myUniqueId;
    private volatile boolean running = true;

    // NEW: A "callback" function to notify the UI
    private Consumer<String> onPeerFound;

    public DiscoveryService() {
        this.myUniqueId = UUID.randomUUID().toString();
    }

    // NEW: Method for the UI to register itself
    public void setOnPeerFound(Consumer<String> callback) {
        this.onPeerFound = callback;
    }

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            NetworkInterface netIf = NetworkUtils.findInterface();
            socket.joinGroup(new InetSocketAddress(group, PORT), netIf);

            System.out.println("[Discovery] Listening on " + MULTICAST_GROUP_IP + ":" + PORT);

            byte[] buffer = new byte[1024];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                String[] parts = message.split("\\|");

                if (parts.length == 2 && parts[0].equals("LAN-CASTER_HELLO")) {
                    String senderId = parts[1];
                    String senderIp = packet.getAddress().getHostAddress();

                    // If it's not me, notify the UI!
                    if (!senderId.equals(myUniqueId)) {
                        // System.out.println("[Discovery] Found: " + senderIp);

                        // TRIGGER THE CALLBACK
                        if (onPeerFound != null) {
                            onPeerFound.accept(senderIp);
                        }
                    }
                }
            }
            socket.leaveGroup(new InetSocketAddress(group, PORT), netIf);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void broadcastPresence() {
        try (DatagramSocket socket = new DatagramSocket()) {
            String msg = "LAN-CASTER_HELLO|" + myUniqueId;
            byte[] buffer = msg.getBytes();
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, PORT);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}