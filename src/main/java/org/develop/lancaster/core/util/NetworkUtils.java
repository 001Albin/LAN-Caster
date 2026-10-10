package org.develop.lancaster.core.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NetworkUtils {
    private static final Logger logger = Logger.getLogger(NetworkUtils.class.getName());

    private static final String[] VIRTUAL_PREFIXES = {
            "docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "lxc", "lxd", "zt", "tailscale", "wg", "tun", "tap"
    };

    private NetworkUtils() {}

    /**
     * All interfaces that are up, not loopback, support multicast and carry an IPv4
     * address. Physical looking adapters (Wi-Fi / Ethernet) are listed before virtual
     * ones (Docker bridges, VPN tunnels) so the "primary" interface is first.
     */
    public static List<NetworkInterface> eligibleInterfaces() {
        List<NetworkInterface> physical = new ArrayList<>();
        List<NetworkInterface> virtual = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    if (!hasIpv4(ni)) continue;
                    if (isVirtual(ni)) virtual.add(ni); else physical.add(ni);
                } catch (SocketException e) {
                    logger.log(Level.FINE, "Skipping interface " + ni.getName(), e);
                }
            }
        } catch (SocketException e) {
            logger.log(Level.WARNING, "Unable to enumerate network interfaces", e);
        }
        physical.addAll(virtual);
        return physical;
    }

    /** First eligible interface, kept for callers that need exactly one. */
    public static NetworkInterface findInterface() throws SocketException {
        List<NetworkInterface> list = eligibleInterfaces();
        if (list.isEmpty()) {
            throw new SocketException("No suitable network interface found! Connect to Wi-Fi or Ethernet.");
        }
        return list.get(0);
    }

    /** IPv4 broadcast addresses of the given interface (may be empty on point-to-point links). */
    public static Set<InetAddress> broadcastAddresses(NetworkInterface ni) {
        Set<InetAddress> out = new LinkedHashSet<>();
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            InetAddress b = ia.getBroadcast();
            if (b != null) out.add(b);
        }
        return out;
    }

    /** All local IPv4 addresses, used to recognise our own packets. */
    public static Set<String> localIPv4Addresses() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {}
        return out;
    }

    public static String osTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac";
        if (os.contains("nux") || os.contains("nix")) return "linux";
        return "other";
    }

    private static boolean hasIpv4(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address) return true;
        }
        return false;
    }

    private static boolean isVirtual(NetworkInterface ni) {
        String name = ni.getName().toLowerCase(Locale.ROOT);
        String display = ni.getDisplayName() == null ? "" : ni.getDisplayName().toLowerCase(Locale.ROOT);
        for (String p : VIRTUAL_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return display.contains("virtual") || display.contains("vmware") || display.contains("hyper-v")
                || display.contains("vpn") || display.contains("tap-") || display.contains("wintun");
    }
}