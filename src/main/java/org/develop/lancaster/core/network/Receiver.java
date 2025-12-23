package org.develop.lancaster.core.network;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;

public class Receiver {
    public static void main(String[] args) {
        String outputName = "received_copy.txt";

        try (Socket socket = new Socket("localhost", 5000);
             InputStream is = socket.getInputStream();
             FileOutputStream fos = new FileOutputStream(outputName)) {

            System.out.println("[Receiver] Connected. Downloading file...");

            // --- THE BINARY STREAM LOGIC (Module 2) ---
            byte[] buffer = new byte[4096]; // Same buffer size
            int bytesRead;

            // Read from socket -> Write to disk
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            // ------------------------------------------

            System.out.println("[Receiver] Download Complete! Saved as: " + outputName);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
