package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final File fileToSend;

    public ClientHandler(Socket socket, String filePath) {
        this.clientSocket = socket;
        this.fileToSend = new File(filePath);
    }

    @Override
    public void run() {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
             // Open the File Channel (Direct Disk Access)
             FileInputStream fis = new FileInputStream(fileToSend);
             FileChannel fileChannel = fis.getChannel()) {

            // Get the Socket Channel (Direct Network Access)
            WritableByteChannel socketChannel = Channels.newChannel(clientSocket.getOutputStream());

            String command = dis.readUTF();

            if ("METADATA".equals(command)) {
                dos.writeUTF(fileToSend.getName());
                dos.writeLong(fileToSend.length());
                dos.flush();
            }
            else if (command.startsWith("CHUNK")) {
                String[] parts = command.split("\\|");
                long start = Long.parseLong(parts[1]);
                long end = Long.parseLong(parts[2]);

                long expectedSize = end - start;
                long totalSent = 0;

                // ZERO-COPY MAGIC:
                // This tells the Operating System to move bytes directly from
                // Disk to Network Card without using Java RAM.
                while (totalSent < expectedSize) {
                    long remaining = expectedSize - totalSent;

                    // transferTo returns how many bytes were actually transferred
                    long written = fileChannel.transferTo(start + totalSent, remaining, socketChannel);

                    if (written == 0) break; // Should not happen unless blocked
                    totalSent += written;
                }

                System.out.println("[Handler] Sent Chunk (Zero-Copy): " + (start/1024/1024) + "MB - " + (end/1024/1024) + "MB");
            }

        } catch (SocketException e) {
            System.out.println("[Handler] Client disconnected.");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}