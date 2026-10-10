package org.develop.lancaster.core.discovery;

import org.develop.lancaster.core.network.Protocol;

import java.util.Objects;

/**
 * A device on the LAN running LAN-Caster. Identity is the peer's persistent
 * instance id when known (discovered peers), otherwise {@code ip:port}
 * (peers added manually by address).
 */
public final class PeerInfo {
    public static final long ONLINE_TIMEOUT_MS = 10_000;
    public static final long EXPIRE_TIMEOUT_MS = 60_000;

    private final String id;
    private final String ip;
    private final boolean manual;
    private volatile int port;
    private volatile String name;
    private volatile String os;
    private volatile int protocolVersion;
    private volatile long lastSeen;

    public PeerInfo(String id, String ip, int port, String name, String os, int protocolVersion, boolean manual) {
        this.id = (id == null || id.isBlank()) ? null : id;
        this.ip = Objects.requireNonNull(ip);
        this.port = port > 0 ? port : Protocol.DEFAULT_PORT;
        this.name = clean(name);
        this.os = os == null ? "" : os;
        this.protocolVersion = protocolVersion;
        this.manual = manual;
        this.lastSeen = System.currentTimeMillis();
    }

    /** Convenience for legacy callers / tests. */
    public PeerInfo(String ip, String name) {
        this(null, ip, Protocol.DEFAULT_PORT, name, "", Protocol.VERSION, true);
    }

    public static PeerInfo manual(String ip, int port) {
        return new PeerInfo(null, ip, port, null, "", Protocol.VERSION, true);
    }

    private static String clean(String n) {
        return (n == null || n.trim().isEmpty()) ? null : n.trim();
    }

    public String key() { return id != null ? id : ip + ":" + port; }
    public String getId() { return id; }
    public String getIp() { return ip; }
    public int getPort() { return port; }
    public String getName() { return name; }
    public String getOs() { return os; }
    public int getProtocolVersion() { return protocolVersion; }
    public long getLastSeen() { return lastSeen; }
    public boolean isManual() { return manual; }

    public void setName(String name) { this.name = clean(name); }
    public void setPort(int port) { if (port > 0) this.port = port; }
    public void setOs(String os) { this.os = os == null ? "" : os; }
    public void setProtocolVersion(int v) { this.protocolVersion = v; }
    public void touch() { this.lastSeen = System.currentTimeMillis(); }

    public String displayName() { return name != null ? name : ip; }

    public boolean isOnline() {
        return manual || System.currentTimeMillis() - lastSeen < ONLINE_TIMEOUT_MS;
    }

    public boolean isExpired() {
        return !manual && System.currentTimeMillis() - lastSeen > EXPIRE_TIMEOUT_MS;
    }

    public boolean isCompatible() {
        return protocolVersion == Protocol.VERSION;
    }

    /** Merge a fresh announcement into this record (keeps a user supplied name). */
    public void updateFrom(PeerInfo fresh, boolean keepName) {
        this.port = fresh.port;
        if (!keepName) this.name = fresh.name;
        this.os = fresh.os;
        this.protocolVersion = fresh.protocolVersion;
        this.lastSeen = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return name == null ? ip : name + " (" + ip + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo)) return false;
        return key().equals(((PeerInfo) o).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}