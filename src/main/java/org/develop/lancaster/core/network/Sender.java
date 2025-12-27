package org.develop.lancaster.core.network;

import org.develop.lancaster.core.transfer.ProgressListener;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class Sender {
    private static final int PORT = 5000;
    private static final int THREAD_POOL_SIZE = 10;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    // Factory to create a listener for each specific client connection
    private final Function<Socket, ProgressListener> listenerFactory;

    public Sender(Function<Socket, ProgressListener> listenerFactory) {
        this.listenerFactory = listenerFactory;
    }

    public void startServing(File file) {
        pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;

        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("[Sender] Hosting " + file.getName() + " on Port " + PORT);

            while (running) {
                try {
                    Socket client = serverSocket.accept();

                    if (running) {
                        System.out.println("[Sender] Connected: " + client.getInetAddress());

                        // 1. Ask UI for a new ProgressListener for THIS specific client
                        ProgressListener clientListener = null;
                        if (listenerFactory != null) {
                            clientListener = listenerFactory.apply(client);
                        }

                        // 2. Pass it to the Handler
                        pool.submit(new ClientHandler(client, file.getAbsolutePath(), clientListener));
                    }
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("[Sender] Port 5000 is occupied or error occurred.");
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            if (pool != null) pool.shutdownNow();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}