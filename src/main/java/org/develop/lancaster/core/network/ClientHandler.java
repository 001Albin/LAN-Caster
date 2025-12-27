package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final File fileToSend;
    // INCREASE BUFFER: 4KB -> 64KB (Much faster for large files)
    private static final int BUFFER_SIZE = 64 * 1024;

    public ClientHandler(Socket socket, String filePath) {
        this.clientSocket = socket;
        this.fileToSend = new File(filePath);
    }

    @Override
    public void run() {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
             RandomAccessFile raf = new RandomAccessFile(fileToSend, "r")) {

            String command = dis.readUTF();

            if ("METADATA".equals(command)) {
                dos.writeUTF(fileToSend.getName());
                dos.writeLong(fileToSend.length());
            }
            else if (command.startsWith("CHUNK")) {
                String[] parts = command.split("\\|");
                long start = Long.parseLong(parts[1]);
                long end = Long.parseLong(parts[2]);

                // Prepare "Bucket"
                byte[] buffer = new byte[BUFFER_SIZE];
                raf.seek(start);

                long totalSent = 0;
                long expectedSize = end - start;

                while (totalSent < expectedSize) {
                    // Calculate how much to read (don't over-read past the chunk end)
                    int remaining = (int) Math.min(BUFFER_SIZE, expectedSize - totalSent);
                    int bytesRead = raf.read(buffer, 0, remaining);

                    if (bytesRead == -1) break; // End of file reached unexpectedly

                    dos.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                }
                // Only print when fully done with a chunk to reduce spam
                System.out.println("[Handler] Sent Chunk: " + start + " - " + end);
            }

        } catch (SocketException e) {
            // This happens if the user cancels the download. It's normal.
            System.out.println("[Handler] Client disconnected abruptly (Transfer cancelled).");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}