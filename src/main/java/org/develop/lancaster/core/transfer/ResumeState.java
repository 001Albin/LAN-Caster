package org.develop.lancaster.core.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Properties;

/**
 * Bitmap of received blocks for one incoming file. Persisted next to the
 * partial download so an interrupted 100 GB transfer continues where it stopped
 * instead of starting over.
 */
public final class ResumeState {
    private final long fileSize;
    private final int blockSize;
    private final long lastModified;
    private final int totalBlocks;
    private final BitSet done;
    private volatile boolean dirty;

    private ResumeState(long fileSize, int blockSize, long lastModified, BitSet done) {
        this.fileSize = fileSize;
        this.blockSize = blockSize;
        this.lastModified = lastModified;
        this.totalBlocks = blockCount(fileSize, blockSize);
        this.done = done;
    }

    public static int blockCount(long fileSize, int blockSize) {
        if (fileSize <= 0) return 0;
        return (int) ((fileSize + blockSize - 1) / blockSize);
    }

    public static ResumeState fresh(long fileSize, int blockSize, long lastModified) {
        return new ResumeState(fileSize, blockSize, lastModified, new BitSet());
    }

    /**
     * Load a previously saved state if it matches the offered file; otherwise null.
     */
    public static ResumeState load(Path stateFile, long fileSize, int blockSize, long lastModified) {
        if (stateFile == null || !Files.isRegularFile(stateFile)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            p.load(in);
            long fs = Long.parseLong(p.getProperty("fileSize", "-1"));
            int bs = Integer.parseInt(p.getProperty("blockSize", "-1"));
            long lm = Long.parseLong(p.getProperty("lastModified", "0"));
            if (fs != fileSize || bs != blockSize) return null;
            // A changed modification time means the source file changed: start over.
            if (lastModified != 0 && lm != 0 && lm != lastModified) return null;
            byte[] bits = Base64.getDecoder().decode(p.getProperty("bitmap", ""));
            return new ResumeState(fileSize, blockSize, lastModified, BitSet.valueOf(bits));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public long getFileSize() { return fileSize; }
    public int getBlockSize() { return blockSize; }
    public int getTotalBlocks() { return totalBlocks; }

    public int blockIndex(long offset) { return (int) (offset / blockSize); }

    public long blockOffset(int idx) { return (long) idx * blockSize; }

    public int blockLength(int idx) {
        long start = blockOffset(idx);
        return (int) Math.min(blockSize, fileSize - start);
    }

    /** Marks a block as fully written. Returns true if it was not already marked. */
    public synchronized boolean markDone(int idx) {
        if (idx < 0 || idx >= totalBlocks) return false;
        if (done.get(idx)) return false;
        done.set(idx);
        dirty = true;
        return true;
    }

    public synchronized boolean isDone(int idx) { return done.get(idx); }

    public synchronized int doneCount() { return done.cardinality(); }

    public synchronized boolean isComplete() { return done.cardinality() >= totalBlocks; }

    public synchronized long doneBytes() {
        long total = 0;
        for (int i = done.nextSetBit(0); i >= 0; i = done.nextSetBit(i + 1)) {
            total += blockLength(i);
        }
        return total;
    }

    /** Contiguous byte ranges {start, length} that still have to be received, in file order. */
    public synchronized List<long[]> missingRanges() {
        List<long[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < totalBlocks) {
            if (done.get(i)) { i++; continue; }
            int j = i;
            while (j < totalBlocks && !done.get(j)) j++;
            long start = blockOffset(i);
            long end = Math.min(fileSize, blockOffset(j));
            ranges.add(new long[]{start, end - start});
            i = j;
        }
        return ranges;
    }

    public boolean isDirty() { return dirty; }

    /** Atomically writes the state file (write to temp, then move). */
    public synchronized void save(Path stateFile) throws IOException {
        Properties p = new Properties();
        p.setProperty("fileSize", Long.toString(fileSize));
        p.setProperty("blockSize", Integer.toString(blockSize));
        p.setProperty("lastModified", Long.toString(lastModified));
        p.setProperty("bitmap", Base64.getEncoder().encodeToString(done.toByteArray()));
        Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "LAN-Caster resume state");
        }
        try {
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}