package org.develop.lancaster;

import org.develop.lancaster.core.discovery.PeerInfo;
import org.develop.lancaster.core.transfer.TransferEngine;
import org.develop.lancaster.core.transfer.TransferSession;
import org.develop.lancaster.core.util.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the transfer engine over the loopback interface:
 * two engines, a large random file, full transfer, cancel + resume.
 */
class LoopbackTransferTest {

    private static final long FILE_SIZE = 300L * 1024 * 1024; // 300 MB

    static {
        // Keep the test away from the developer's real ~/.lancaster settings.
        try {
            System.setProperty("lancaster.configDir", Files.createTempDirectory("lancaster-test-config").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TempDir Path tmp;
    private TransferEngine sender;
    private TransferEngine receiver;

    @BeforeEach
    void startEngines() throws Exception {
        Config.removePaired("sender-id");
        Config.removePaired("receiver-id");
        sender = new TransferEngine("sender-id", "Sender");
        receiver = new TransferEngine("receiver-id", "Receiver");
        sender.start();
        receiver.start();
    }

    @Test
    void unpairedDeviceCannotSendAndPairingNeedsConfirmation() throws Exception {
        Path src = tmp.resolve("small.bin");
        Files.write(src, new byte[1024]);
        PeerInfo peer = PeerInfo.manual("127.0.0.1", receiver.getPort());

        // Not paired: the offer is declined before the receiver even shows a card.
        receiver.setSessionCreatedListener(s -> s.accept(tmp));
        TransferSession out = sender.sendFile(src.toFile(), peer);
        waitTerminal(out, 20);
        assertEquals(TransferSession.State.DECLINED, out.getState(), out.getMessage());
        assertTrue(receiver.getSessions().isEmpty(), "receiver must not create a session for unpaired senders");

        // Rejected pairing
        receiver.setPairingHandler(req -> CompletableFuture.completedFuture(false));
        TransferEngine.PairResult rejected = sender.requestPairing(peer, null).get(20, TimeUnit.SECONDS);
        assertFalse(rejected.ok);
        assertFalse(Config.isPaired("receiver-id"));

        // Accepted pairing: both sides remember each other, code is 6 digits
        String[] seenCode = new String[1];
        receiver.setPairingHandler(req -> { seenCode[0] = req.code; return CompletableFuture.completedFuture(true); });
        String[] shownCode = new String[1];
        TransferEngine.PairResult ok = sender.requestPairing(peer, c -> shownCode[0] = c).get(20, TimeUnit.SECONDS);
        assertTrue(ok.ok, ok.message);
        assertEquals(shownCode[0], seenCode[0]);
        assertEquals(6, seenCode[0].length());
        assertTrue(Config.isPaired("receiver-id"));
        assertTrue(Config.isPaired("sender-id"));

        // Now the transfer goes through.
        TransferSession out2 = sender.sendFile(src.toFile(), peer);
        waitTerminal(out2, 20);
        assertEquals(TransferSession.State.COMPLETED, out2.getState(), out2.getMessage());
        assertEquals(1024, Files.size(tmp.resolve("small.bin")));
    }

    private void pairEngines() throws Exception {
        receiver.setPairingHandler(req -> CompletableFuture.completedFuture(true));
        TransferEngine.PairResult r = sender.requestPairing(PeerInfo.manual("127.0.0.1", receiver.getPort()), null)
                .get(20, TimeUnit.SECONDS);
        assertTrue(r.ok, r.message);
    }

    @AfterEach
    void stopEngines() {
        sender.shutdown();
        receiver.shutdown();
    }

    @Test
    void transfersLargeFileIntact() throws Exception {
        pairEngines();
        Path src = tmp.resolve("src/big.bin");
        Files.createDirectories(src.getParent());
        String expected = writeRandomFile(src, FILE_SIZE);
        Path downloads = tmp.resolve("downloads");

        receiver.setSessionCreatedListener(s -> s.accept(downloads));
        PeerInfo peer = PeerInfo.manual("127.0.0.1", receiver.getPort());

        long t0 = System.nanoTime();
        TransferSession out = sender.sendFile(src.toFile(), peer);
        waitTerminal(out, 120);
        double secs = (System.nanoTime() - t0) / 1e9;

        assertEquals(TransferSession.State.COMPLETED, out.getState(), out.getMessage());
        TransferSession in = receiver.getSessions().get(0);
        waitTerminal(in, 10);
        assertEquals(TransferSession.State.COMPLETED, in.getState(), in.getMessage());

        Path saved = in.getLocalFile();
        assertNotNull(saved);
        assertEquals(downloads.resolve("big.bin"), saved);
        assertEquals(FILE_SIZE, Files.size(saved));
        assertEquals(expected, sha256(saved));
        assertFalse(Files.exists(downloads.resolve("big.bin.lcpart")));
        assertFalse(Files.exists(downloads.resolve("big.bin.lcstate")));
        System.out.printf("Loopback transfer: %d MB in %.2f s = %.0f MB/s (%d streams)%n",
                FILE_SIZE / 1024 / 1024, secs, FILE_SIZE / 1024.0 / 1024.0 / secs, in.getStreams());
    }

    @Test
    void cancelledTransferResumesWhereItStopped() throws Exception {
        pairEngines();
        Path src = tmp.resolve("src/resume.bin");
        Files.createDirectories(src.getParent());
        String expected = writeRandomFile(src, FILE_SIZE);
        Path downloads = tmp.resolve("downloads");

        // First attempt: accept, then cancel on the receiver once ~30% arrived.
        CompletableFuture<TransferSession> firstIncoming = new CompletableFuture<>();
        receiver.setSessionCreatedListener(s -> { s.accept(downloads); firstIncoming.complete(s); });
        PeerInfo peer = PeerInfo.manual("127.0.0.1", receiver.getPort());
        TransferSession out1 = sender.sendFile(src.toFile(), peer);
        TransferSession in1 = firstIncoming.get(20, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 60_000;
        while (in1.getBytesDone() < FILE_SIZE * 0.3 && System.currentTimeMillis() < deadline && in1.isActive()) {
            Thread.sleep(20);
        }
        assertTrue(in1.isActive(), "transfer finished before we could cancel it: " + in1.getMessage());
        in1.requestCancel();
        waitTerminal(in1, 20);
        waitTerminal(out1, 20);
        assertEquals(TransferSession.State.CANCELLED, in1.getState());
        assertEquals(TransferSession.State.CANCELLED, out1.getState(), out1.getMessage());
        assertTrue(Files.exists(downloads.resolve("resume.bin.lcpart")), "partial file should be kept");
        assertTrue(Files.exists(downloads.resolve("resume.bin.lcstate")), "resume state should be kept");
        long partial = in1.getBytesDone();
        assertTrue(partial > 0);

        // Second attempt resumes.
        CompletableFuture<TransferSession> secondIncoming = new CompletableFuture<>();
        receiver.setSessionCreatedListener(s -> { s.accept(downloads); secondIncoming.complete(s); });
        TransferSession out2 = sender.sendFile(src.toFile(), peer);
        TransferSession in2 = secondIncoming.get(20, TimeUnit.SECONDS);
        waitTerminal(out2, 120);
        waitTerminal(in2, 10);

        assertEquals(TransferSession.State.COMPLETED, out2.getState(), out2.getMessage());
        assertEquals(TransferSession.State.COMPLETED, in2.getState(), in2.getMessage());
        assertTrue(in2.isResumed(), "second transfer should have resumed");
        assertTrue(out2.isResumed(), "sender should have skipped already received blocks");
        assertEquals(expected, sha256(in2.getLocalFile()));
        assertFalse(Files.exists(downloads.resolve("resume.bin.lcpart")));
        assertFalse(Files.exists(downloads.resolve("resume.bin.lcstate")));
        System.out.printf("Resume: %d MB were already present before the second attempt%n", partial / 1024 / 1024);
    }

    private static void waitTerminal(TransferSession s, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (s.isActive() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertFalse(s.isActive(), "timed out in state " + s.getState() + " (" + s.getMessage() + ")");
    }

    /** Fills the file with fast pseudo-random data and returns its SHA-256. */
    private static String writeRandomFile(Path p, long size) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
        long x = 0x9E3779B97F4A7C15L;
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            while (written < size) {
                buf.clear();
                int n = (int) Math.min(buf.capacity(), size - written);
                buf.limit(n);
                while (buf.hasRemaining()) {
                    x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
                    if (buf.remaining() >= 8) buf.putLong(x); else buf.put((byte) x);
                }
                buf.flip();
                md.update(buf.duplicate());
                while (buf.hasRemaining()) ch.write(buf);
                written += n;
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            while (ch.read(buf) > 0) {
                buf.flip();
                md.update(buf);
                buf.clear();
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
