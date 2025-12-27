/**This class acts as the "Listener." It waits for a connection and prints the message it receives.**/

package org.develop.lancaster.core.network;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Sender {
    // 10 Threads = Up to 10 parallel chunks or clients
    private static final int THREAD_POOL_SIZE = 10;

    public static void main(String[] args) {
        // Hardcoded for now, later this comes from UI
        String filePath = "SystemDesign.pdf";

        // Create a pool of threads
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        try (ServerSocket serverSocket = new ServerSocket(5000)) {
            System.out.println("[Sender] Multi-threaded Server Started on Port 5000...");
            System.out.println("[Sender] Serving file: " + filePath);

            while (true) {
                // 1. Accept Connection (Blocks here until someone connects)
                Socket client = serverSocket.accept();

                // 2. Instead of handling it here, pass it to the pool!
                System.out.println("[Sender] New Client Connected! Assigning to worker...");
                pool.submit(new ClientHandler(client, filePath));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
