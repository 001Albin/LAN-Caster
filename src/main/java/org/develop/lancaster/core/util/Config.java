package org.develop.lancaster.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class Config {
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".lancaster");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");
    private static final Properties props = new Properties();

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
            // ignore, defaults will be used
        }
    }

    public static String getDeviceName() {
        return props.getProperty("device.name", System.getProperty("user.name", "LAN-Caster"));
    }

    public static void setDeviceName(String name) {
        if (name == null) return;
        props.setProperty("device.name", name.trim());
        persist();
    }

    // Per-peer custom name storage: key = peer.<ip>
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

    private static void persist() {
        try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
            props.store(out, "LAN-Caster configuration");
        } catch (IOException ignored) {
            // ignore persistence errors
        }
    }
}
