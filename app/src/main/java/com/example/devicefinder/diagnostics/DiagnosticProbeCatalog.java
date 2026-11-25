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

/**
 * Explicit allowlist of lightweight, read-only Android diagnostics.
 *
 * Probes inspect framework/app state only: no shell, no log collection, no payload inspection,
 * no device identifiers, and no changes to production feature state.
 */
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
                        "heap_used_kb", used / 1024L,
                        "heap_max_kb", runtime.maxMemory() / 1024L);
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

        return Collections.unmodifiableList(tests);
    }

    /** Returns a coarse category only; never reads SSID, BSSID, IP, carrier, or network identity. */
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
        } catch (RuntimeException unavailable) {
            return "UNKNOWN";
        }
    }

    private static Map<String, Long> metrics(String key1, long value1,
            String key2, long value2, String key3, long value3) {
        java.util.LinkedHashMap<String, Long> values = new java.util.LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        values.put(key3, value3);
        return values;
    }

    private static Map<String, Long> metrics(String key1, long value1,
            String key2, long value2) {
        java.util.LinkedHashMap<String, Long> values = new java.util.LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return values;
    }
}
