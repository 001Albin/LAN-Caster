package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final File fileToSend;
    // BUFFER SIZE: 64KB (This matches your Receiver and is memory efficient)
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

                // 1. Move to the correct spot in the file
                raf.seek(start);

                byte[] buffer = new byte[BUFFER_SIZE];
                long totalSent = 0;
                long expectedSize = end - start;

                // 2. The Streaming Loop (Solves the RAM crash)
                // It reads 64KB -> sends 64KB -> repeats.
                while (totalSent < expectedSize) {

                    // Don't read past the end of the requested chunk
                    int remaining = (int) Math.min(BUFFER_SIZE, expectedSize - totalSent);
                    int bytesRead = raf.read(buffer, 0, remaining);

                    if (bytesRead == -1) break; // End of file

                    dos.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                }

                // 3. FINAL FLUSH (Add this line!)
                // Ensures the last bytes are pushed out before we close the socket.
                dos.flush();

                System.out.println("[Handler] Sent Chunk: " + start + " - " + end);
            }

        } catch (SocketException e) {
            System.out.println("[Handler] Client disconnected (Transfer cancelled).");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}