//This class is a "worker bee."
//Its only job is to download one specific range (e.g., bytes 1000 to 2000) and write it to the disk.

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

    // MATCH SENDER BUFFER: 64KB
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
        System.out.println("[Task-" + taskId + "] Requesting: " + (startByte/1024/1024) + "MB to " + (endByte/1024/1024) + "MB");

        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw")) {

            String command = "CHUNK|" + startByte + "|" + endByte;
            dos.writeUTF(command);

            raf.seek(startByte);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalRead = 0;
            long expectedSize = endByte - startByte;

            // Progress tracking
            long lastPrint = 0;

            while (totalRead < expectedSize && (bytesRead = dis.read(buffer)) != -1) {
                int writeSize = (int) Math.min(bytesRead, expectedSize - totalRead);
                raf.write(buffer, 0, writeSize);
                totalRead += writeSize;

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