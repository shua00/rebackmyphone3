package com.example.devicefinder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stable, privacy-preserving identity for the selected Wi-Fi LAN path. */
public final class LanTransferNetworkFingerprint {
    private LanTransferNetworkFingerprint() {}

    public static String create(String networkIdentity, String interfaceName,
            List<String> addresses, List<String> routes) {
        if (networkIdentity == null || networkIdentity.isEmpty()
                || interfaceName == null || interfaceName.isEmpty()
                || addresses == null || addresses.isEmpty() || routes == null)
            throw new IllegalArgumentException("Incomplete Wi-Fi LAN network snapshot");
        List<String> sortedAddresses = new ArrayList<>(addresses);
        List<String> sortedRoutes = new ArrayList<>(routes);
        Collections.sort(sortedAddresses);
        Collections.sort(sortedRoutes);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, networkIdentity);
            update(digest, interfaceName);
            for (String address : sortedAddresses) update(digest, address);
            digest.update((byte) 0xff);
            for (String route : sortedRoutes) update(digest, route);
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder("wifi:sha256:");
            for (byte value : digest.digest()) {
                int b = value & 0xff;
                result.append(hex[b >>> 4]).append(hex[b & 0x0f]);
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) throw new IllegalArgumentException("Null network snapshot field");
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
