package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Receiver {
    private static final Logger logger = Logger.getLogger(Receiver.class.getName());

    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 5000);
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            logger.info("[Receiver] Connected. Waiting for metadata...");

            // --- 1. READ METADATA ---
            String fileName = dis.readUTF(); // Read Name
            long fileSize = dis.readLong();  // Read Size

            logger.info("[Receiver] Incoming File: " + fileName + " (" + fileSize + " bytes)");

            // --- 2. CREATE FILE & SEND ACKNOWLEDGMENT ---
            File file = new File("downloaded_" + fileName); // Add prefix to avoid overwriting original
            try (FileOutputStream fos = new FileOutputStream(file)) {

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

                logger.info("[Receiver] Saved as: " + file.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Receiver encountered an error", e);
        }
    }
}
