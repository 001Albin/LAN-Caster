package org.develop.lancaster.core.network;

import org.develop.lancaster.core.transfer.ProgressListener;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Sender {
    private static final int PORT = 5000;
    private static final int THREAD_POOL_SIZE = 10;
    private volatile boolean running = false;
    private ServerSocketChannel serverSocketChannel;
    private ExecutorService pool;

    // Factory to create a listener
    private final Function<Socket, ProgressListener> listenerFactory;

    private static final Logger logger = Logger.getLogger(Sender.class.getName());

    // --- CONSTRUCTOR 1: DEFAULT (Fixes your Red Error) ---
    public Sender() {
        this(null); // Calls the main constructor with null
    }

    // --- CONSTRUCTOR 2: WITH LISTENER (Used for Progress Bars) ---
    public Sender(Function<Socket, ProgressListener> listenerFactory) {
        this.listenerFactory = listenerFactory;
    }

    public void startServing(File file) {
        pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;

        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress(PORT));
            logger.info(() -> "[Sender] Hosting " + file.getName() + " on Port " + PORT);

            while (running) {
                try {
                    SocketChannel sc = serverSocketChannel.accept();

                    if (sc != null && running) {
                        logger.info(() -> "[Sender] Connected: " + sc.socket().getInetAddress());
                        // Pass SocketChannel directly to ClientHandler to enable optimal transferTo
                        pool.submit(new ClientHandler(sc, file.getAbsolutePath(), listenerFactory));
                    }
                } catch (IOException e) {
                    if (running) logger.log(Level.WARNING, "Error accepting connection", e);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Port 5000 is occupied or failed to open server socket.", e);
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocketChannel != null) serverSocketChannel.close();
            if (pool != null) pool.shutdownNow();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error while stopping sender", e);
        }
    }
}