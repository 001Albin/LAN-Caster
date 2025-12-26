//This class is the "Coordinator." It talks to the server once to get the file size, does the math to
//split it, and then tells the ExecutorService to launch the workers.
package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TransferManager {

    private static final int THREAD_COUNT = 4; // Use 4 parallel threads

    public void downloadFile(String serverIp, int port, String saveDir) {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        try (Socket socket = new Socket(serverIp, port);
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            // 1. Handshake: Get Name and Size
            System.out.println("[Manager] Connecting to get metadata...");
            dos.writeUTF("METADATA");
            String fileName = dis.readUTF();
            long fileSize = dis.readLong();
            System.out.println("[Manager] File: " + fileName + " (" + fileSize + " bytes)");

            // 2. Prepare the Empty File on Disk
            File outFile = new File(saveDir, "downloaded_" + fileName);
            try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
                raf.setLength(fileSize); // Reserve the disk space immediately (Sparse File)
            }

            // 3. Calculate Splits (The Math)
            long chunkSize = fileSize / THREAD_COUNT;
            List<ChunkTransferTask> tasks = new ArrayList<>();

            for (int i = 0; i < THREAD_COUNT; i++) {
                long start = i * chunkSize;
                long end = (i == THREAD_COUNT - 1) ? fileSize : (start + chunkSize); // Last chunk gets the remainder

                tasks.add(new ChunkTransferTask(serverIp, port, outFile, start, end, i));
            }

            // 4. Launch Everything!
            System.out.println("[Manager] Launching " + THREAD_COUNT + " parallel threads...");
            List<Future<Boolean>> results = pool.invokeAll(tasks);

            // 5. Wait for all to finish
            for (Future<Boolean> result : results) {
                result.get(); // This blocks until the task is done
            }

            System.out.println("------------------------------------------------");
            System.out.println("[Manager] TRANSFER COMPLETE! File saved at: " + outFile.getAbsolutePath());
            System.out.println("------------------------------------------------");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pool.shutdown();
        }
    }

    // Quick Main for Testing
    public static void main(String[] args) {
        TransferManager tm = new TransferManager();
        // REPLACE WITH YOUR WINDOWS IP if running on Kali
        String windowsIp = "localhost";

        // "." means save in current directory
        tm.downloadFile(windowsIp, 5000, ".");
    }
}