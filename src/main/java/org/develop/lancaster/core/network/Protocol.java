package org.develop.lancaster.core.network;

/**
 * Wire-protocol constants shared by sender and receiver.
 *
 * <p>Every TCP connection starts with a small handshake written with
 * {@link java.io.DataOutputStream}:
 * <pre>
 *   UTF  MAGIC            "LANC"
 *   int  VERSION          protocol version
 *   UTF  kind             OFFER | DATA | PING
 * </pre>
 *
 * <p><b>OFFER</b> (sender → receiver, the control connection). Payload:
 * transferId, fileName, fileSize, senderName, senderId, blockSize, lastModified.
 * The receiver answers with {@code ACCEPT streams missingCount [start,len]*}
 * or {@code DECLINE reason}. The connection then stays open and carries
 * status messages in both directions: DONE, CANCEL reason, ERROR reason.
 *
 * <p><b>DATA</b> (sender → receiver, one per parallel stream). Payload:
 * transferId, streamIndex. The receiver answers OK (or ERROR reason). The sender
 * then pushes blocks, each framed by a 12 byte header {@code [long offset][int length]}
 * followed by exactly {@code length} raw file bytes. A header with offset -1 and
 * length 0 marks the end of the stream.
 *
 * <p><b>PING</b> (any → any). The server answers with its id, name and OS. Used
 * when a peer is added manually by IP.
 */
public final class Protocol {
    private Protocol() {}

    public static final String MAGIC = "LANC";
    public static final int VERSION = 2;

    public static final int DEFAULT_PORT = 5000;
    public static final int DISCOVERY_PORT = 8888;
    public static final String MULTICAST_GROUP = "239.255.77.77";

    // connection kinds
    public static final String OFFER = "OFFER";
    public static final String DATA = "DATA";
    public static final String PING = "PING";
    /** Pairing request: senderId, senderName, code, senderPort → PAIR_OK id name | DECLINE reason. */
    public static final String PAIR = "PAIR";
    public static final String PAIR_OK = "PAIR_OK";

    // messages
    public static final String ACCEPT = "ACCEPT";
    public static final String DECLINE = "DECLINE";
    public static final String OK = "OK";
    public static final String DONE = "DONE";
    public static final String CANCEL = "CANCEL";
    public static final String ERROR = "ERROR";
    public static final String PONG = "PONG";

    /** Size of one resumable unit. Also the granularity of the sender's work queue. */
    public static final int BLOCK_SIZE = 16 * 1024 * 1024;
    /** Size of the direct buffer used for socket reads / disk writes on the receiver. */
    public static final int IO_BUFFER_SIZE = 4 * 1024 * 1024;
    /** Requested TCP socket buffer size (needs to be set before connect/bind). */
    public static final int SOCKET_BUFFER_SIZE = 4 * 1024 * 1024;
    /** Maximum bytes handed to a single transferTo() call so progress stays smooth. */
    public static final long SEND_PIECE_SIZE = 4L * 1024 * 1024;

    public static final int BLOCK_HEADER_SIZE = Long.BYTES + Integer.BYTES;
    public static final long END_OF_STREAM = -1L;

    public static final int CONNECT_TIMEOUT_MS = 6_000;
    public static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    /** How long the sender waits for the receiver to click Accept. */
    public static final int ACCEPT_WAIT_MS = 180_000;
    /** A transfer with no progress for this long is considered dead. */
    public static final int STALL_TIMEOUT_MS = 60_000;
    public static final int MAX_STREAM_RETRIES = 3;
}