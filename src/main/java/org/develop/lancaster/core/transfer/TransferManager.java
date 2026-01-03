package org.develop.lancaster.core.transfer;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransferManager {

    private static final int PORT = 5000;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Logger logger = Logger.getLogger(TransferManager.class.getName());

    public void downloadFile(String peerIp, String saveDir, ProgressListener uiListener) {
        executor.submit(() -> {
            long fileSize;
            String filename;

            // 1. Request Metadata (single lightweight connection)
            try (Socket socket = new Socket(peerIp, PORT);
                 DataInputStream dis = new DataInputStream(socket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

                dos.writeUTF("METADATA");
                dos.flush();

                filename = dis.readUTF();
                fileSize = dis.readLong();

            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to request metadata from " + peerIp, e);
                return;
            }

            File saveFile = new File(saveDir, filename);
            logger.info(() -> "[Manager] Downloading " + filename + " (" + fileSize + " bytes)");

            // Pre-allocate file to avoid fragmentation and allow RandomAccess writes safely
            try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
                raf.setLength(fileSize);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to preallocate file: " + saveFile.getAbsolutePath(), e);
                return;
            }

            // Decide parallelism: balance between HDD safety and throughput.
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            int parts = Math.min(8, cores * 2); // up to 8 parallel connections

            long baseChunk = fileSize / parts;

            ExecutorService partsExecutor = Executors.newFixedThreadPool(parts);
            List<Future<Boolean>> futures = new ArrayList<>();

            AtomicLong globalDownloaded = new AtomicLong(0);
            AtomicLong[] lastReported = new AtomicLong[parts];
            for (int i = 0; i < parts; i++) lastReported[i] = new AtomicLong(0);

            for (int i = 0; i < parts; i++) {
                final int idx = i;
                long start = idx * baseChunk;
                long end = (idx == parts - 1) ? fileSize : (start + baseChunk);

                ProgressListener partListener = (current, total) -> {
                    long prev = lastReported[idx].getAndSet(current);
                    long delta = current - prev;
                    if (delta <= 0) return;
                    long totalNow = globalDownloaded.addAndGet(delta);
                    uiListener.onProgress(totalNow, fileSize);
                };

                ChunkTransferTask task = new ChunkTransferTask(peerIp, PORT, saveFile, start, end, idx, partListener);
                futures.add(partsExecutor.submit(task));
            }

            // wait for parts
            try {
                for (Future<Boolean> f : futures) f.get();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error while downloading parts from " + peerIp, e);
            } finally {
                partsExecutor.shutdownNow();
            }

            // Ensure UI shows completion
            uiListener.onProgress(fileSize, fileSize);
            logger.info(() -> "[Manager] Download complete: " + saveFile.getAbsolutePath());
        });
    }
}