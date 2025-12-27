package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.Callable;

public class ChunkTransferTask implements Callable<Boolean> {

    private final String serverIp;
    private final int port;
    private final long startByte;
    private final long endByte;
    private final File destinationFile;
    private final int taskId;

    // OPTIMIZATION: 64KB Buffer
    private static final int BUFFER_SIZE = 64 * 1024;

    public ChunkTransferTask(String serverIp, int port, File destinationFile, long start, long end, int id) {
        this.serverIp = serverIp;
        this.port = port;
        this.destinationFile = destinationFile;
        this.startByte = start;
        this.endByte = end;
        this.taskId = id;
    }

    @Override
    public Boolean call() throws Exception {
        System.out.println("[Task-" + taskId + "] Requesting: " + (startByte / 1024 / 1024) + "MB to " + (endByte / 1024 / 1024) + "MB");

        try (Socket socket = new Socket(serverIp, port)) {

            // OPTIMIZATION: Maximize Receive Buffer for speed
            socket.setReceiveBufferSize(BUFFER_SIZE);
            socket.setTcpNoDelay(true);

            // OPTIMIZATION: Buffered Network Input
            // Reads large chunks from WiFi card into RAM automatically
            try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dis = new DataInputStream(
                         new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
                 RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw")) {

                // 1. Send Request
                String command = "CHUNK|" + startByte + "|" + endByte;
                dos.writeUTF(command);
                dos.flush(); // Send request immediately

                // 2. Prepare Disk Write
                raf.seek(startByte);

                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalRead = 0;
                long expectedSize = endByte - startByte;
                long lastPrint = 0;

                // 3. Smart Read Loop
                while (totalRead < expectedSize) {

                    int remaining = (int) Math.min(buffer.length, expectedSize - totalRead);

                    // Read from Buffered Network Stream
                    // (This will likely return full 64KB chunks now, which is faster)
                    bytesRead = dis.read(buffer, 0, remaining);

                    if (bytesRead == -1) {
                        throw new IOException("Connection closed prematurely.");
                    }

                    // Write to Disk
                    raf.write(buffer, 0, bytesRead);

                    totalRead += bytesRead;

                    // Progress Update (Reduced spam: only every 50MB)
                    if (totalRead - lastPrint > 50 * 1024 * 1024) {
                        System.out.println("[Task-" + taskId + "] Progress: " + (totalRead / 1024 / 1024) + " MB...");
                        lastPrint = totalRead;
                    }
                }

                System.out.println("[Task-" + taskId + "] Finished!");
                return true;
            }
        } catch (Exception e) {
            System.err.println("[Task-" + taskId + "] Failed: " + e.getMessage());
            throw e;
        }
    }
}