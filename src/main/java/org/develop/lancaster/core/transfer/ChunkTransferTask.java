package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.Callable;

public class ChunkTransferTask implements Callable<Boolean> {

    private final String serverIp;
    private final int port;
    private final long startByte;
    private final long endByte;
    private final File destinationFile;
    private final int taskId;

    // 64KB Direct Buffer (Optimized for HDD writing)
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
             // RandomAccessFile allows us to write to a specific place on the disk
             RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw");
             FileChannel fileChannel = raf.getChannel()) {

            // 1. Send Request
            dos.writeUTF("CHUNK|" + startByte + "|" + endByte);
            dos.flush();

            // 2. Setup High-Performance Channels
            ReadableByteChannel socketChannel = Channels.newChannel(socket.getInputStream());

            // Move file pointer to start position
            fileChannel.position(startByte);

            // Allocate "Direct" memory (Outside Java Heap, faster for OS)
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

            long totalRead = 0;
            long expectedSize = endByte - startByte;
            long lastPrint = 0;

            // 3. NIO Read Loop
            while (totalRead < expectedSize) {
                // Read from Network into Buffer
                int bytesRead = socketChannel.read(buffer);

                if (bytesRead == -1) {
                    throw new IOException("Connection closed prematurely.");
                }

                // Flip buffer from "Write Mode" to "Read Mode" for the disk
                buffer.flip();

                // Write to Disk (Write exactly what we got)
                while (buffer.hasRemaining()) {
                    fileChannel.write(buffer);
                }

                // Clear buffer for next round
                buffer.clear();

                totalRead += bytesRead;

                if (totalRead - lastPrint > 50 * 1024 * 1024) {
                    System.out.println("[Task-" + taskId + "] Progress: " + (totalRead / 1024 / 1024) + " MB...");
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