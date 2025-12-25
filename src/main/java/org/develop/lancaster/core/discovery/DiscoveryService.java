package org.develop.lancaster.core.discovery;

import org.develop.lancaster.core.util.NetworkUtils;

import java.io.IOException;
import java.net.*;
import java.util.UUID;

public class DiscoveryService implements Runnable {

    private static final String MULTICAST_GROUP_IP = "224.0.0.1"; // The "Room" address
    private static final int PORT = 8888;
    private final String myUniqueId; // To identify OUR packets
    private volatile boolean running = true;

    public DiscoveryService() {
        // Generate a random ID for this session (e.g., "User-A")
        this.myUniqueId = UUID.randomUUID().toString();
    }

    @Override
    public void run() {
        // This 'run' method acts as the LISTENER (The Ears)
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);

            // Join the group to start hearing shouts
            NetworkInterface netIf = NetworkUtils.findInterface(); // We will create this helper next
            socket.joinGroup(new InetSocketAddress(group, PORT), netIf);

            System.out.println("[Discovery] Listening on " + MULTICAST_GROUP_IP + ":" + PORT);

            byte[] buffer = new byte[1024];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // Blocks until a packet arrives

                String message = new String(packet.getData(), 0, packet.getLength());

                // Parse the message: "LAN-CASTER_HELLO|UUID"
                String[] parts = message.split("\\|");
                if (parts.length == 2 && parts[0].equals("LAN-CASTER_HELLO")) {
                    String senderId = parts[1];
                    String senderIp = packet.getAddress().getHostAddress();

                    // FILTER: Ignore our own shouts
                    if (!senderId.equals(myUniqueId)) {
                        System.out.println("[Discovery] FOUND PEER! IP: " + senderIp);
                        // In the real app, you would add this IP to a UI list here
                    }
                }
            }

            socket.leaveGroup(new InetSocketAddress(group, PORT), netIf);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // This method acts as the SHOUTER (The Mouth)
    public void broadcastPresence() {
        try (DatagramSocket socket = new DatagramSocket()) {
            String msg = "LAN-CASTER_HELLO|" + myUniqueId;
            byte[] buffer = msg.getBytes();

            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, PORT);

            socket.send(packet);
            System.out.println("[Discovery] Shouted presence...");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Simple main to test this module in isolation
    public static void main(String[] args) throws InterruptedException {
        DiscoveryService service = new DiscoveryService();

        // 1. Start Listening in a background thread
        new Thread(service).start();

        // 2. Loop to shout every 3 seconds
        while (true) {
            service.broadcastPresence();
            Thread.sleep(3000);
        }
    }
}
