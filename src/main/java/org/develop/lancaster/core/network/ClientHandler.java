package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final File fileToSend;

    // OPTIMIZATION: 64KB Buffer matches TCP window sizes well
    private static final int BUFFER_SIZE = 64 * 1024;

    public ClientHandler(Socket socket, String filePath) {
        this.clientSocket = socket;
        this.fileToSend = new File(filePath);
    }

    @Override
    public void run() {
        // OPTIMIZATION: Use BufferedOutputStream.
        // This is the #1 way to speed up Java network transfers.
        // It prevents sending tiny packets and instead fills the WiFi bandwidth.
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(
                     new BufferedOutputStream(clientSocket.getOutputStream(), BUFFER_SIZE));
             RandomAccessFile raf = new RandomAccessFile(fileToSend, "r")) {

            // OPTIMIZATION: Hint to OS for larger socket buffers
            clientSocket.setSendBufferSize(BUFFER_SIZE);
            clientSocket.setTcpNoDelay(true); // Reduce latency for initial packets

            String command = dis.readUTF();

            if ("METADATA".equals(command)) {
                dos.writeUTF(fileToSend.getName());
                dos.writeLong(fileToSend.length());
                dos.flush(); // Important: Send metadata immediately
            }
            else if (command.startsWith("CHUNK")) {
                String[] parts = command.split("\\|");
                long start = Long.parseLong(parts[1]);
                long end = Long.parseLong(parts[2]);

                // 1. Seek to position (Fast on SSD, works on HDD)
                raf.seek(start);

                byte[] buffer = new byte[BUFFER_SIZE];
                long totalSent = 0;
                long expectedSize = end - start;

                // 2. Optimized Streaming Loop
                while (totalSent < expectedSize) {
                    // Calculate precise remaining bytes
                    int remaining = (int) Math.min(BUFFER_SIZE, expectedSize - totalSent);
                    int bytesRead = raf.read(buffer, 0, remaining);

                    if (bytesRead == -1) break;

                    // Write to the BUFFERED stream (fast RAM operation)
                    // The underlying BufferedOutputStream handles the slow network sending
                    dos.write(buffer, 0, bytesRead);

                    totalSent += bytesRead;
                }

                // 3. FINAL FLUSH
                // Pushes the last buffered bytes out to the network card
                dos.flush();

                System.out.println("[Handler] Sent Chunk: " + (start/1024/1024) + "MB - " + (end/1024/1024) + "MB");
            }

        } catch (SocketException e) {
            System.out.println("[Handler] Client disconnected (Normal if cancelled).");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}