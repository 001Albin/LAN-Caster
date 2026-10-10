package org.develop.lancaster.core.transfer;

/**
 * Sliding-window throughput meter. Bytes are bucketed into 250 ms slots and the
 * rate is computed over the last three seconds, so the number shown in the UI is
 * smooth but still reacts quickly when a transfer stalls or finishes.
 */
public final class SpeedMeter {
    private static final long SLOT_MS = 250;
    private static final int SLOTS = 12; // 3 seconds

    private final long[] bytes = new long[SLOTS];
    private final long[] slotIds = new long[SLOTS];
    private long firstSampleMs = 0;

    public synchronized void add(long n) {
        long now = System.currentTimeMillis();
        if (firstSampleMs == 0) firstSampleMs = now;
        long slot = now / SLOT_MS;
        int idx = (int) (slot % SLOTS);
        if (slotIds[idx] != slot) {
            slotIds[idx] = slot;
            bytes[idx] = 0;
        }
        bytes[idx] += n;
    }

    /** Current throughput in bytes per second. */
    public synchronized double bytesPerSecond() {
        if (firstSampleMs == 0) return 0;
        long now = System.currentTimeMillis();
        long slot = now / SLOT_MS;
        long total = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (slot - slotIds[i] < SLOTS) total += bytes[i];
        }
        double windowSec = Math.min(SLOTS * SLOT_MS, Math.max(SLOT_MS, now - firstSampleMs)) / 1000.0;
        return total / windowSec;
    }

    public synchronized void reset() {
        java.util.Arrays.fill(bytes, 0);
        java.util.Arrays.fill(slotIds, 0);
        firstSampleMs = 0;
    }
}