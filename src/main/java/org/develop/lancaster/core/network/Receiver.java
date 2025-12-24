package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;

public class Receiver {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 5000);
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            System.out.println("[Receiver] Connected. Waiting for metadata...");

            // --- 1. READ METADATA ---
            String fileName = dis.readUTF(); // Read Name
            long fileSize = dis.readLong();  // Read Size

            System.out.println("[Receiver] Incoming File: " + fileName + " (" + fileSize + " bytes)");

            // --- 2. CREATE FILE & SEND ACKNOWLEDGMENT ---
            File file = new File("downloaded_" + fileName); // Add prefix to avoid overwriting original
            FileOutputStream fos = new FileOutputStream(file);

            dos.writeUTF("READY"); // Tell Sender to go ahead
            dos.flush();

            // --- 3. RECEIVE FILE CONTENT ---
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalRead = 0;

            // We loop until we have read exactly 'fileSize' bytes
            // (Or until stream ends, but counting is safer for progress bars later)
            while (totalRead < fileSize && (bytesRead = dis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }

            fos.close();
            System.out.println("[Receiver] Saved as: " + file.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
