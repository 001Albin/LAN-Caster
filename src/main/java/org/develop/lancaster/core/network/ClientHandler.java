package org.develop.lancaster.core.network;

import org.develop.lancaster.core.transfer.ProgressListener;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.SocketChannel;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final File fileToSend;
    private final SocketChannel socketChannelField; // optional, for true zero-copy

    // Support both a direct listener (old API) and a factory (new API)
    private final ProgressListener fallbackListener;
    private final Function<Socket, ProgressListener> listenerFactory;

    // Constructor with direct Listener (backwards compatible)
    public ClientHandler(Socket socket, String filePath, ProgressListener listener) {
        this.clientSocket = socket;
        this.fileToSend = new File(filePath);
        this.fallbackListener = listener;
        this.listenerFactory = null;
        this.socketChannelField = socket.getChannel();
    }

    // Constructor with factory: factory will be invoked when CHUNK command is received
    public ClientHandler(Socket socket, String filePath, Function<Socket, ProgressListener> listenerFactory) {
        this.clientSocket = socket;
        this.fileToSend = new File(filePath);
        this.listenerFactory = listenerFactory;
        this.fallbackListener = null;
        this.socketChannelField = socket.getChannel();
    }

    // Flexible constructor to accept either type (used to avoid compile mismatch in calling code)
    @SuppressWarnings("unchecked")
    public ClientHandler(Socket socket, String filePath, Object listenerOrFactory) {
        this.clientSocket = socket;
        this.fileToSend = new File(filePath);
        if (listenerOrFactory instanceof ProgressListener) {
            this.fallbackListener = (ProgressListener) listenerOrFactory;
            this.listenerFactory = null;
        } else if (listenerOrFactory instanceof Function) {
            this.listenerFactory = (Function<Socket, ProgressListener>) listenerOrFactory;
            this.fallbackListener = null;
        } else {
            this.listenerFactory = null;
            this.fallbackListener = null;
        }
        this.socketChannelField = socket.getChannel();
    }

    // New constructor accepting SocketChannel directly (used by ServerSocketChannel accept)
    @SuppressWarnings("unchecked")
    public ClientHandler(SocketChannel sc, String filePath, Object listenerOrFactory) {
        this.clientSocket = sc.socket();
        this.fileToSend = new File(filePath);
        if (listenerOrFactory instanceof ProgressListener) {
            this.fallbackListener = (ProgressListener) listenerOrFactory;
            this.listenerFactory = null;
        } else if (listenerOrFactory instanceof Function) {
            this.listenerFactory = (Function<Socket, ProgressListener>) listenerOrFactory;
            this.fallbackListener = null;
        } else {
            this.listenerFactory = null;
            this.fallbackListener = null;
        }
        this.socketChannelField = sc;
    }

    private static final Logger logger = Logger.getLogger(ClientHandler.class.getName());

    @Override
    public void run() {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
             FileInputStream fis = new FileInputStream(fileToSend);
             FileChannel fileChannel = fis.getChannel()) {

            WritableByteChannel socketChannel;
            if (socketChannelField != null) {
                socketChannel = socketChannelField; // true zero-copy path
            } else {
                socketChannel = Channels.newChannel(clientSocket.getOutputStream());
            }

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

                // Tune socket for bulk transfer: disable Nagle and increase send buffer.
                try {
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setSendBufferSize(4 * 1024 * 1024); // 4 MB
                } catch (SocketException se) {
                    logger.log(Level.FINE, "Socket tuning not permitted", se);
                }

                // Create the listener only now (prevents duplicate UI card for METADATA)
                ProgressListener activeListener = (listenerFactory != null) ? listenerFactory.apply(clientSocket) : fallbackListener;

                // Transfer in chunks to produce frequent progress updates while keeping high throughput.
                final long TRANSFER_CHUNK_SIZE = 64L * 1024L * 1024L; // 64 MB

                // 2. High-Speed Loop with Progress Reporting
                while (totalSent < expectedSize) {
                    long remaining = expectedSize - totalSent;
                    long toWrite = Math.min(remaining, TRANSFER_CHUNK_SIZE);

                    long written = fileChannel.transferTo(start + totalSent, toWrite, socketChannel);

                    if (written <= 0) {
                        if (Thread.interrupted()) break;
                        continue;
                    }
                    totalSent += written;

                    // 3. --- NOTIFY UI HERE ---
                    if (activeListener != null) {
                        activeListener.onProgress(totalSent, expectedSize);
                    }
                }

                dos.flush();
                // log without lambda to avoid capturing mutable local
                logger.info("[Handler] Sent " + (totalSent/1024/1024) + " MB to " + clientSocket.getInetAddress());
            }

        } catch (SocketException e) {
            logger.info("[Handler] Transfer stopped (Client disconnected).");
        } catch (IOException e) {
            logger.log(Level.WARNING, "IO error in ClientHandler", e);
        } finally {
            try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}