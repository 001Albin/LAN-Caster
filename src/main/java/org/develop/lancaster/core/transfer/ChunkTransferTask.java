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
    private final ProgressListener listener; // <--- NEW: Listener

    private static final int BUFFER_SIZE = 64 * 1024;

    // Updated Constructor
    public ChunkTransferTask(String serverIp, int port, File destinationFile, long start, long end, int id, ProgressListener listener) {
        this.serverIp = serverIp;
        this.port = port;
        this.destinationFile = destinationFile;
        this.startByte = start;
        this.endByte = end;
        this.taskId = id;
        this.listener = listener;
    }

    @Override
    public Boolean call() throws Exception {
        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw");
             FileChannel fileChannel = raf.getChannel()) {

            // 1. Send Request
            dos.writeUTF("CHUNK|" + startByte + "|" + endByte);
            dos.flush();

            // 2. Setup NIO
            ReadableByteChannel socketChannel = Channels.newChannel(socket.getInputStream());
            fileChannel.position(startByte);
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

            long totalRead = 0;
            long expectedSize = endByte - startByte;

            // 3. Download Loop
            while (totalRead < expectedSize) {
                int bytesRead = socketChannel.read(buffer);
                if (bytesRead == -1) break;

                buffer.flip();
                while (buffer.hasRemaining()) fileChannel.write(buffer);
                buffer.clear();

                totalRead += bytesRead;

                // <--- NOTIFY UI --->
                if (listener != null) {
                    listener.onProgress(totalRead, expectedSize);
                }
            }
            return true;
        }
    }
}