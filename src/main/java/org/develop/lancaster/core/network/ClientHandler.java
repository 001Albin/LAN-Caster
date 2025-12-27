package org.develop.lancaster.core.network;

import org.develop.lancaster.core.transfer.ProgressListener;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final File fileToSend;
    private final ProgressListener listener; // <--- The bridge to the UI

    // Constructor with Listener
    public ClientHandler(Socket socket, String filePath, ProgressListener listener) {
        this.clientSocket = socket;
        this.fileToSend = new File(filePath);
        this.listener = listener;
    }

    @Override
    public void run() {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
             FileInputStream fis = new FileInputStream(fileToSend);
             FileChannel fileChannel = fis.getChannel()) {

            WritableByteChannel socketChannel = Channels.newChannel(clientSocket.getOutputStream());

            // 1. Handshake
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

                // 2. High-Speed Loop with Progress Reporting
                while (totalSent < expectedSize) {
                    long remaining = expectedSize - totalSent;

                    // Zero-Copy Transfer (Fastest Java Method)
                    long written = fileChannel.transferTo(start + totalSent, remaining, socketChannel);

                    if (written == 0) break;
                    totalSent += written;

                    // 3. --- NOTIFY UI HERE ---
                    if (listener != null) {
                        listener.onProgress(totalSent, expectedSize);
                    }
                }

                dos.flush();
                System.out.println("[Handler] Sent " + (totalSent/1024/1024) + " MB to " + clientSocket.getInetAddress());
            }

        } catch (SocketException e) {
            System.out.println("[Handler] Transfer stopped (Client disconnected).");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}