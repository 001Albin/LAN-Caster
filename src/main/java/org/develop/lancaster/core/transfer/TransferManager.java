package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class TransferManager {

    private static final int PORT = 5000;
    private final ExecutorService executor = Executors.newFixedThreadPool(4); // 4 Threads for SSD/WiFi

    public void downloadFile(String peerIp, String saveDir, ProgressListener uiListener) {
        executor.submit(() -> {
            try (Socket socket = new Socket(peerIp, PORT);
                 DataInputStream dis = new DataInputStream(socket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

                // 1. Request Metadata
                dos.writeUTF("METADATA");
                dos.flush();

                String filename = dis.readUTF();
                long fileSize = dis.readLong();

                File saveFile = new File(saveDir, filename);
                System.out.println("[Manager] Downloading " + filename + " (" + fileSize + " bytes)");

                // 2. Start Transfer (Using 1 Giant Chunk for simplicity & HDD safety)
                // You can split this into 4 chunks if you want, but 1 is safer for HDDs.
                ChunkTransferTask task = new ChunkTransferTask(
                        peerIp, PORT, saveFile, 0, fileSize, 0, uiListener
                );

                FutureTask<Boolean> future = new FutureTask<>(task);
                new Thread(future).start();
                future.get(); // Wait for finish

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}