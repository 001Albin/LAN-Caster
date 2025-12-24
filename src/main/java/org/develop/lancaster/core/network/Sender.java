/**This class acts as the "Listener." It waits for a connection and prints the message it receives.**/

package org.develop.lancaster.core.network;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class Sender {
    public static void main(String[] args) {
        System.out.println("Sender is running...");

        File file = new File("vibeproject.pdf");

        // 1. Initialize ServerSocket on Port 5000
        try(ServerSocket serverSocket = new ServerSocket(5000)){
            System.out.println("[Sender] Waiting for connection...");

            try(Socket socket = serverSocket.accept();
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                FileInputStream fis = new FileInputStream(file);){

                System.out.println("[Sender] Connected. Sending Metadata...");

                // --- 1. SEND METADATA (The Handshake) ---
                dos.writeUTF(file.getName());  // Send Filename
                dos.writeLong(file.length());  // Send File Size
                dos.flush(); // Force send immediately

                // --- 2. WAIT FOR ACKNOWLEDGMENT ---
                String response = dis.readUTF();
                if (!"READY".equals(response)) {
                    System.err.println("[Sender] Receiver rejected transfer.");
                    return; // Stop if client isn't ready
                }

                System.out.println("[Sender] Client is READY. Sending bytes...");

                // --- 3. SEND FILE CONTENT (Binary Stream) ---
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalSent = 0;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                }

                System.out.println("[Sender] Finished! Sent " + totalSent + " bytes.");
            }

        } catch (IOException e) {
           e.printStackTrace();
        }
    }
}
