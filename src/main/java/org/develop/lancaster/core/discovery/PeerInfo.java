package org.develop.lancaster.core.discovery;

import java.util.Objects;

public final class PeerInfo {
    private final String ip;
    private String name; // human-friendly name or null

    public PeerInfo(String ip, String name) {
        this.ip = ip;
        this.name = (name == null || name.trim().isEmpty()) ? null : name.trim();
    }

    public String getIp() { return ip; }
    public String getName() { return name; }
    public void setName(String name) { this.name = (name == null || name.trim().isEmpty()) ? null : name.trim(); }

    @Override
    public String toString() {
        if (name == null || name.isEmpty()) return ip;
        return name + " (" + ip + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo)) return false;
        PeerInfo that = (PeerInfo) o;
        return Objects.equals(ip, that.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip);
    }
}

