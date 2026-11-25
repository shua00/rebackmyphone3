package com.example.devicefinder.diagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Explicit allowlist of lightweight, read-only Android diagnostics. */
public final class DiagnosticProbeCatalog {
    private DiagnosticProbeCatalog() {}

    public static List<DiagnosticExecutor.TestCase> localReadOnlyTests(Context context) {
        if (context == null) throw new IllegalArgumentException("Application context is required");
        final Context app = context.getApplicationContext();
        ArrayList<DiagnosticExecutor.TestCase> tests = new ArrayList<>();

        tests.add(new DiagnosticExecutor.TestCase("android.runtime", new DiagnosticExecutor.Probe() {
            @Override public boolean isSupported() { return true; }
            @Override public Map<String, Long> execute() {
                Runtime runtime = Runtime.getRuntime();
                long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
                return metrics("api_level", (long) Build.VERSION.SDK_INT,
                        "heap_used_kb", used / 1024L, "heap_max_kb", runtime.maxMemory() / 1024L);
            }
        }));
        tests.add(new DiagnosticExecutor.TestCase("network.connectivity", new DiagnosticExecutor.Probe() {
            @Override public boolean isSupported() { return true; }
            @Override public Map<String, Long> execute() throws Exception {
                ConnectivityManager manager = (ConnectivityManager)
                        app.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (manager == null) throw new IOException("Connectivity service unavailable");
                NetworkInfo info = manager.getActiveNetworkInfo();
                boolean connected = info != null && info.isConnected();
                long type = 0L;
                if (connected) {
                    int raw = info.getType();
                    type = raw == ConnectivityManager.TYPE_WIFI ? 1L
                            : raw == ConnectivityManager.TYPE_MOBILE ? 2L : 3L;
                }
                return metrics("connected", connected ? 1L : 0L, "network_type", type);
            }
        }));
        tests.add(new DiagnosticExecutor.TestCase("app.install", new DiagnosticExecutor.Probe() {
            @Override public boolean isSupported() { return true; }
            @Override public Map<String, Long> execute() throws PackageManager.NameNotFoundException {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                long versionCode = Build.VERSION.SDK_INT >= 28
                        ? info.getLongVersionCode() : info.versionCode;
                return metrics("version_code", Math.max(0L, versionCode),
                        "target_sdk", (long) app.getApplicationInfo().targetSdkVersion);
            }
        }));
        tests.add(new DiagnosticExecutor.TestCase("lan.ipv4_interface", new DiagnosticExecutor.Probe() {
            @Override public boolean isSupported() { return true; }
            @Override public Map<String, Long> execute() throws Exception {
                int activeInterfaces = 0, addressCount = 0, broadcastInterfaces = 0;
                java.util.Enumeration<java.net.NetworkInterface> interfaces =
                        java.net.NetworkInterface.getNetworkInterfaces();
                if (interfaces != null) while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface network = interfaces.nextElement();
                    if (!network.isUp() || network.isLoopback()) continue;
                    activeInterfaces++;
                    if (hasBroadcastAddress(network)) broadcastInterfaces++;
                    java.util.Enumeration<java.net.InetAddress> addresses = network.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress address = addresses.nextElement();
                        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress()) addressCount++;
                    }
                }
                return metrics("active_interfaces", activeInterfaces,
                        "address_count", addressCount, "broadcast_interfaces", broadcastInterfaces);
            }
        }));
        tests.add(new DiagnosticExecutor.TestCase("lan.udp_bind", new DiagnosticExecutor.Probe() {
            @Override public boolean isSupported() { return true; }
            @Override public Map<String, Long> execute() throws Exception {
                java.net.InetAddress selected = null;
                java.util.Enumeration<java.net.NetworkInterface> interfaces =
                        java.net.NetworkInterface.getNetworkInterfaces();
                if (interfaces != null) while (interfaces.hasMoreElements() && selected == null) {
                    java.net.NetworkInterface network = interfaces.nextElement();
                    if (!network.isUp() || network.isLoopback() || !hasBroadcastAddress(network)) continue;
                    java.util.Enumeration<java.net.InetAddress> addresses = network.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress address = addresses.nextElement();
                        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress()) { selected = address; break; }
                    }
                }
                if (selected == null) return null;
                try (java.net.DatagramSocket socket = new java.net.DatagramSocket(null)) {
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.bind(new java.net.InetSocketAddress(selected, 0));
                    return metrics("bound", 1L, "broadcast_enabled", socket.getBroadcast() ? 1L : 0L);
                }
            }
        }));
        tests.add(new DiagnosticExecutor.TestCase("lan.transfer_runtime", new DiagnosticExecutor.Probe() {
            @Override public boolean isSupported() { return true; }
            @Override public Map<String, Long> execute() {
                com.example.devicefinder.LanTransferRuntimeDiagnostics.Snapshot snapshot =
                        com.example.devicefinder.LanTransferRuntimeDiagnostics.snapshot(
                                app, System.currentTimeMillis());
                return metrics("discovery_state", snapshot.discoveryState,
                        "tcp_listener_state", snapshot.tcpListenerState,
                        "fresh_peer_count", snapshot.freshPeerCount,
                        "latest_peer_age_ms", snapshot.latestPeerAgeMs);
            }
        }));
        return Collections.unmodifiableList(tests);
    }

    private static boolean hasBroadcastAddress(java.net.NetworkInterface network) {
        for (java.net.InterfaceAddress address : network.getInterfaceAddresses())
            if (address.getBroadcast() != null) return true;
        return false;
    }

    /** Coarse category only; never reads SSID, BSSID, IP, carrier, or network identity. */
    public static String detectNetworkType(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return "OFFLINE";
            int type = info.getType();
            if (type == ConnectivityManager.TYPE_WIFI) return "WIFI";
            if (type == ConnectivityManager.TYPE_MOBILE) return "CELLULAR";
            return "OTHER";
        } catch (RuntimeException unavailable) { return "UNKNOWN"; }
    }

    private static Map<String, Long> metrics(String k1, long v1, String k2, long v2,
            String k3, long v3) {
        java.util.LinkedHashMap<String, Long> values = new java.util.LinkedHashMap<>();
        values.put(k1, v1); values.put(k2, v2); values.put(k3, v3);
        return values;
    }
    private static Map<String, Long> metrics(String k1, long v1, String k2, long v2,
            String k3, long v3, String k4, long v4) {
        java.util.LinkedHashMap<String, Long> values = new java.util.LinkedHashMap<>();
        values.put(k1, v1); values.put(k2, v2); values.put(k3, v3); values.put(k4, v4);
        return values;
    }
    private static Map<String, Long> metrics(String k1, long v1, String k2, long v2) {
        java.util.LinkedHashMap<String, Long> values = new java.util.LinkedHashMap<>();
        values.put(k1, v1); values.put(k2, v2);
        return values;
    }
}
