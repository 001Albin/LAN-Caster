//This class is a "worker bee."
//Its only job is to download one specific range (e.g., bytes 1000 to 2000) and write it to the disk.

package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.Callable;

// We use Callable so it can return "true" when finished (or throw an error)
public class ChunkTransferTask implements Callable<Boolean> {

    private final String serverIp;
    private final int port;
    private final long startByte;
    private final long endByte;
    private final File destinationFile;
    private final int taskId;

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
        System.out.println("[Task-" + taskId + "] Starting download: " + startByte + " - " + endByte);

        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             // "rw" mode allows us to write to the middle of the file
             RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw")) {

            // 1. Send Request: "CHUNK|start|end"
            String command = "CHUNK|" + startByte + "|" + endByte;
            dos.writeUTF(command);

            // 2. Seek to the correct position on OUR disk
            // This is the Magic: Thread 2 writes to the middle, even if Thread 1 isn't done!
            raf.seek(startByte);

            // 3. Download Loop
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalRead = 0;
            long expectedSize = endByte - startByte;

            while (totalRead < expectedSize && (bytesRead = dis.read(buffer)) != -1) {
                // Safety: Don't write more than expected for this chunk
                int writeSize = (int) Math.min(bytesRead, expectedSize - totalRead);

                raf.write(buffer, 0, writeSize);
                totalRead += writeSize;
            }

            System.out.println("[Task-" + taskId + "] Finished!");
            return true;
        } catch (Exception e) {
            System.err.println("[Task-" + taskId + "] Failed: " + e.getMessage());
            throw e;
        }
    }
}
