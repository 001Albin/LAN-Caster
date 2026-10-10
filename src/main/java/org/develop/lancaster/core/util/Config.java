package org.develop.lancaster.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Persistent user settings stored in {@code ~/.lancaster/config.properties}.
 */
public final class Config {
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("lancaster.configDir",
            Paths.get(System.getProperty("user.home"), ".lancaster").toString()));
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");
    private static final Properties props = new Properties();

    private Config() {}

    static {
        try {
            if (Files.notExists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            if (Files.exists(CONFIG_FILE)) {
                try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
                    props.load(in);
                }
            }
        } catch (IOException ignored) {
            // defaults will be used
        }
    }

    // ---------------------------------------------------------------- identity

    /** Stable id for this installation, so peers keep their identity across restarts. */
    public static synchronized String getInstanceId() {
        String override = System.getProperty("lancaster.instanceId");
        if (override != null && !override.isBlank()) return override;
        String id = props.getProperty("instance.id");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            props.setProperty("instance.id", id);
            persist();
        }
        return id;
    }

    // Developer overrides (-Dlancaster.instanceId=A -Dlancaster.deviceName=Alpha
    // -Dlancaster.autoAccept=true -Dlancaster.downloadDir=...) make it possible to
    // run two instances on one machine for testing.

    public static String getDeviceName() {
        String override = System.getProperty("lancaster.deviceName");
        if (override != null && !override.isBlank()) return override;
        String n = props.getProperty("device.name");
        if (n == null || n.isBlank()) return defaultDeviceName();
        return n;
    }

    /** Machine name without a DNS lookup (InetAddress.getLocalHost can hang on misconfigured Linux boxes). */
    private static String defaultDeviceName() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                Path etcHostname = Paths.get("/etc/hostname");
                if (Files.isRegularFile(etcHostname)) host = Files.readString(etcHostname).trim();
            } catch (IOException ignored) {}
        }
        if (host == null || host.isBlank()) host = System.getProperty("user.name", "LAN-Caster");
        return host;
    }

    public static void setDeviceName(String name) {
        if (name == null || name.trim().isEmpty()) props.remove("device.name");
        else props.setProperty("device.name", name.trim());
        persist();
    }

    // ---------------------------------------------------------------- peers

    public static String getPeerName(String ip) {
        if (ip == null) return null;
        return props.getProperty("peer." + ip);
    }

    public static void setPeerName(String ip, String name) {
        if (ip == null) return;
        if (name == null || name.trim().isEmpty()) {
            props.remove("peer." + ip);
        } else {
            props.setProperty("peer." + ip, name.trim());
        }
        persist();
    }

    public static void removePeerName(String ip) {
        if (ip == null) return;
        props.remove("peer." + ip);
        persist();
    }

    // ---------------------------------------------------------------- pairing

    /** Devices that went through the pairing handshake; key = their instance id. */
    public static synchronized boolean isPaired(String instanceId) {
        return instanceId != null && !instanceId.isBlank() && props.containsKey("paired." + instanceId);
    }

    public static synchronized void addPaired(String instanceId, String name) {
        if (instanceId == null || instanceId.isBlank()) return;
        props.setProperty("paired." + instanceId, name == null ? "" : name.trim());
        persist();
    }

    public static synchronized void removePaired(String instanceId) {
        if (instanceId == null) return;
        props.remove("paired." + instanceId);
        persist();
    }

    public static synchronized Map<String, String> pairedDevices() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : props.stringPropertyNames()) {
            if (k.startsWith("paired.")) out.put(k.substring("paired.".length()), props.getProperty(k));
        }
        return out;
    }

    // ---------------------------------------------------------------- transfer settings

    /** Folder where accepted files are saved. Created on demand. */
    public static Path getDownloadDir() {
        String dir = System.getProperty("lancaster.downloadDir", props.getProperty("download.dir"));
        Path p;
        if (dir != null && !dir.isBlank()) {
            p = Paths.get(dir);
        } else {
            p = Paths.get(System.getProperty("user.home"), "Downloads");
            if (!Files.isDirectory(p)) p = Paths.get(System.getProperty("user.home"));
        }
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        return p;
    }

    public static void setDownloadDir(Path dir) {
        if (dir == null) props.remove("download.dir");
        else props.setProperty("download.dir", dir.toAbsolutePath().toString());
        persist();
    }

    /** Number of parallel TCP streams per transfer; 0 means "automatic". */
    public static int getParallelStreams() {
        try {
            return Math.max(0, Math.min(16, Integer.parseInt(props.getProperty("transfer.streams", "0"))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setParallelStreams(int n) {
        props.setProperty("transfer.streams", String.valueOf(Math.max(0, Math.min(16, n))));
        persist();
    }

    /** Effective stream count after resolving "automatic". */
    public static int resolveParallelStreams() {
        int configured = getParallelStreams();
        if (configured > 0) return configured;
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(6, cores));
    }

    public static boolean isAutoAccept() {
        String override = System.getProperty("lancaster.autoAccept");
        if (override != null) return Boolean.parseBoolean(override);
        return Boolean.parseBoolean(props.getProperty("transfer.autoaccept", "false"));
    }

    public static void setAutoAccept(boolean on) {
        props.setProperty("transfer.autoaccept", String.valueOf(on));
        persist();
    }

    private static synchronized void persist() {
        try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
            props.store(out, "LAN-Caster configuration");
        } catch (IOException ignored) {
            // ignore persistence errors
        }
    }
}