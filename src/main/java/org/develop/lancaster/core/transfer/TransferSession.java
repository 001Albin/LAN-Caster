package org.develop.lancaster.core.transfer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Observable state of one file transfer (one file, one peer, one direction).
 * The transfer engine mutates it from background threads; the UI reads it from
 * the JavaFX thread. State changes are pushed to listeners, byte counters are
 * meant to be polled.
 */
public final class TransferSession {

    public enum Direction { SEND, RECEIVE }

    public enum State {
        INCOMING_OFFER("Waiting for you to accept"),
        CONNECTING("Connecting"),
        WAITING_ACCEPT("Waiting for peer to accept"),
        TRANSFERRING("Transferring"),
        FINALIZING("Finishing up"),
        COMPLETED("Completed"),
        FAILED("Failed"),
        CANCELLED("Cancelled"),
        DECLINED("Declined");

        private final String label;
        State(String label) { this.label = label; }
        public String label() { return label; }

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == DECLINED;
        }
    }

    /** The receiver's answer to an incoming offer. */
    public static final class Decision {
        public final boolean accept;
        public final Path directory;
        private Decision(boolean accept, Path directory) { this.accept = accept; this.directory = directory; }
        public static Decision accept(Path dir) { return new Decision(true, dir); }
        public static Decision decline() { return new Decision(false, null); }
    }

    private final String id;
    private final Direction direction;
    private final String fileName;
    private final long fileSize;
    private final String peerKey;
    private final String peerIp;
    private volatile int peerPort;
    private volatile String peerName;

    private final AtomicLong bytesDone = new AtomicLong();
    private final SpeedMeter speed = new SpeedMeter();
    private volatile State state;
    private volatile String message = "";
    private final long createdAt = System.currentTimeMillis();
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile long lastProgressAt = System.currentTimeMillis();
    private volatile Path localFile;
    private volatile boolean resumed;
    private volatile int streams;
    private volatile Runnable canceller;

    private final CompletableFuture<Decision> decision = new CompletableFuture<>();
    private final List<Consumer<TransferSession>> listeners = new CopyOnWriteArrayList<>();

    public TransferSession(String id, Direction direction, String fileName, long fileSize,
                           String peerKey, String peerIp, String peerName, State initial) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.direction = direction;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.peerKey = peerKey;
        this.peerIp = peerIp;
        this.peerName = peerName;
        this.state = initial;
    }

    // --------------------------------------------------------------- getters

    public String getId() { return id; }
    public Direction getDirection() { return direction; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public String getPeerKey() { return peerKey; }
    public String getPeerIp() { return peerIp; }
    public int getPeerPort() { return peerPort; }
    public void setPeerPort(int p) { this.peerPort = p; }
    public String getPeerName() { return peerName != null ? peerName : peerIp; }
    public void setPeerName(String n) { if (n != null && !n.isBlank()) this.peerName = n; }
    public State getState() { return state; }
    public String getMessage() { return message; }
    public long getBytesDone() { return bytesDone.get(); }
    public long getCreatedAt() { return createdAt; }
    public long getStartedAt() { return startedAt; }
    public long getFinishedAt() { return finishedAt; }
    public long getLastProgressAt() { return lastProgressAt; }
    public Path getLocalFile() { return localFile; }
    public void setLocalFile(Path p) { this.localFile = p; }
    public boolean isResumed() { return resumed; }
    public void setResumed(boolean r) { this.resumed = r; }
    public int getStreams() { return streams; }
    public void setStreams(int s) { this.streams = s; }
    public CompletableFuture<Decision> decision() { return decision; }
    public boolean isActive() { return !state.isTerminal(); }

    public double progress() {
        if (fileSize <= 0) return state == State.COMPLETED ? 1.0 : 0.0;
        return Math.min(1.0, (double) bytesDone.get() / fileSize);
    }

    public double bytesPerSecond() {
        return state == State.TRANSFERRING ? speed.bytesPerSecond() : 0;
    }

    /** Seconds remaining at the current speed, or -1 if unknown. */
    public long etaSeconds() {
        double bps = bytesPerSecond();
        if (bps < 1) return -1;
        return (long) Math.ceil((fileSize - bytesDone.get()) / bps);
    }

    /** Average speed over the whole transfer (bytes/s), for the completed summary. */
    public double averageBytesPerSecond() {
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        long start = startedAt > 0 ? startedAt : createdAt;
        double sec = Math.max(0.001, (end - start) / 1000.0);
        return bytesDone.get() / sec;
    }

    // --------------------------------------------------------------- mutation (engine side)

    public void addListener(Consumer<TransferSession> l) { listeners.add(l); }
    public void removeListener(Consumer<TransferSession> l) { listeners.remove(l); }

    public void setCanceller(Runnable r) { this.canceller = r; }

    public void setState(State newState, String msg) {
        State old = this.state;
        if (old.isTerminal() && newState != old) return; // terminal states are final
        if (newState == State.TRANSFERRING && startedAt == 0) startedAt = System.currentTimeMillis();
        if (newState.isTerminal() && finishedAt == 0) finishedAt = System.currentTimeMillis();
        this.state = newState;
        this.message = msg == null ? "" : msg;
        fire();
    }

    public void setMessage(String msg) {
        this.message = msg == null ? "" : msg;
        fire();
    }

    public void addProgress(long n) {
        if (n <= 0) return;
        bytesDone.addAndGet(n);
        speed.add(n);
        lastProgressAt = System.currentTimeMillis();
    }

    public void setBytesDone(long n) {
        bytesDone.set(n);
        lastProgressAt = System.currentTimeMillis();
    }

    public void touchProgress() { lastProgressAt = System.currentTimeMillis(); }

    /** Called by the user (UI). */
    public void requestCancel() {
        Runnable c = canceller;
        if (c != null) c.run();
        else setState(State.CANCELLED, "Cancelled");
        if (!decision.isDone()) decision.complete(Decision.decline());
    }

    public void accept(Path directory) { decision.complete(Decision.accept(directory)); }
    public void decline() { decision.complete(Decision.decline()); }

    private void fire() {
        for (Consumer<TransferSession> l : listeners) {
            try { l.accept(this); } catch (RuntimeException ignored) {}
        }
    }

    @Override
    public String toString() {
        return direction + " " + fileName + " [" + state + "] " + bytesDone.get() + "/" + fileSize;
    }
}