/**This class acts as the "Listener." It waits for a connection and prints the message it receives.**/

package org.develop.lancaster.core.network;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Sender {
    private static final int PORT = 5000;
    private static final int THREAD_POOL_SIZE = 10;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    // This method is called by the UI Button
    public void startServing(File file) {
        pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;

        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("[Sender] Server started. Hosting: " + file.getName());

            while (running) {
                try {
                    // Wait for Kali to connect
                    Socket client = serverSocket.accept();

                    if (running) {
                        System.out.println("[Sender] Incoming connection...");
                        pool.submit(new ClientHandler(client, file.getAbsolutePath()));
                    }
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            if (pool != null) pool.shutdownNow();
            System.out.println("[Sender] Server stopped.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}