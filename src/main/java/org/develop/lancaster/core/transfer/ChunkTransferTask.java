package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.Callable;
import java.nio.channels.SocketChannel;

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
        // Use SocketChannel for better throughput and allow FileChannel.transferFrom
        try (SocketChannel sc = SocketChannel.open();
             RandomAccessFile raf = new RandomAccessFile(destinationFile, "rw");
             FileChannel fileChannel = raf.getChannel()) {

            sc.connect(new InetSocketAddress(serverIp, port));
            sc.configureBlocking(true);
            try {
                sc.socket().setTcpNoDelay(true);
                sc.socket().setReceiveBufferSize(2 * 1024 * 1024); // 2MB
            } catch (IOException ignored) {}

            // 1. Send Request using socket output stream
            DataOutputStream dos = new DataOutputStream(Channels.newOutputStream(sc));
            dos.writeUTF("CHUNK|" + startByte + "|" + endByte);
            dos.flush();

            long totalRead = 0;
            long expectedSize = endByte - startByte;

            // Position file channel at the offset
            fileChannel.position(startByte);

            // Use transferFrom in chunks for zero-copy receive
            final long RECV_CHUNK = 16L * 1024L * 1024L; // 16MB

            while (totalRead < expectedSize) {
                long remaining = expectedSize - totalRead;
                long toRead = Math.min(remaining, RECV_CHUNK);

                long read = fileChannel.transferFrom(sc, fileChannel.position(), toRead);
                if (read <= 0) {
                    if (Thread.interrupted()) break;
                    continue;
                }

                totalRead += read;
                // advance file position
                fileChannel.position(fileChannel.position() + read);

                if (listener != null) listener.onProgress(totalRead, expectedSize);
            }
            return true;
        }
    }
}