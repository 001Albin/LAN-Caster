package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.Callable;

// This class is a "worker bee."
// It downloads one specific range (e.g., bytes 1000 to 2000) and writes it to disk.
public class ChunkTransferTask implements Callable<Boolean> {

    private final String serverIp;
    private final int port;
    private final long startByte;
    private final long endByte;
    private final File destinationFile;
    private final int taskId;

    // BUFFER SIZE: 64KB (Matches your Sender)
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

        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw")) {

            // 1. Send Request
            String command = "CHUNK|" + startByte + "|" + endByte;
            dos.writeUTF(command);
            dos.flush(); // Ensure request is sent immediately

            // 2. Seek to the correct position in the file
            raf.seek(startByte);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalRead = 0;
            long expectedSize = endByte - startByte;

            // Progress tracking
            long lastPrint = 0;

            // 3. Smart Read Loop
            // Only ask for the bytes we actually need (prevents hanging at 99%)
            while (totalRead < expectedSize) {

                int remaining = (int) Math.min(buffer.length, expectedSize - totalRead);

                // Read exactly 'remaining' or less
                bytesRead = dis.read(buffer, 0, remaining);

                if (bytesRead == -1) {
                    throw new IOException("Connection closed prematurely by sender. Download incomplete.");
                }

                raf.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                // Print progress every 50 MB per thread
                if (totalRead - lastPrint > 50 * 1024 * 1024) {
                    System.out.println("[Task-" + taskId + "] Progress: " + (totalRead / 1024 / 1024) + " MB downloaded...");
                    lastPrint = totalRead;
                }
            }

            System.out.println("[Task-" + taskId + "] Finished!");
            return true;

        } catch (Exception e) {
            System.err.println("[Task-" + taskId + "] Failed: " + e.getMessage());
            throw e;
        }
    }
}