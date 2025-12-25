package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;

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
             RandomAccessFile raf = new RandomAccessFile(fileToSend, "r")) {

            System.out.println("[Handler] Handling connection from: " + clientSocket.getInetAddress());

            // 1. Wait for Command (What does the client want?)
            String command = dis.readUTF(); // e.g., "METADATA" or "CHUNK|0|1024"

            if ("METADATA".equals(command)) {
                // Send File Info
                dos.writeUTF(fileToSend.getName());
                dos.writeLong(fileToSend.length());
                System.out.println("[Handler] Sent Metadata.");
            }
            else if (command.startsWith("CHUNK")) {
                // HANDLE RANGE REQUEST: "CHUNK|start|end"
                String[] parts = command.split("\\|");
                long start = Long.parseLong(parts[1]);
                long end = Long.parseLong(parts[2]);
                int chunkSize = (int) (end - start);

                // Read specific part of the file
                byte[] buffer = new byte[chunkSize];
                raf.seek(start); // JUMP to the specific position!
                raf.readFully(buffer);

                // Send back only that part
                dos.write(buffer);
                System.out.println("[Handler] Sent Chunk: " + start + " - " + end);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}
