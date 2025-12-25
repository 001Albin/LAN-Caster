package org.develop.lancaster.core.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class NetworkUtils {

    public static NetworkInterface findInterface() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();

            // 1. Basic Checks: Must be Up, Not Loopback, and Support Multicast
            if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) {
                continue;
            }

            // 2. Strict Check: Must have an IPv4 Address
            // (This filters out weird virtual adapters that only have IPv6 or no IP)
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address) {
                    // Found a valid IPv4 interface (likely your Wi-Fi)
                    System.out.println("[NetworkUtils] Using Interface: " + ni.getDisplayName() + " (" + addr.getHostAddress() + ")");
                    return ni;
                }
            }
        }

        throw new SocketException("No suitable network interface found! Connect to Wi-Fi.");
    }
}