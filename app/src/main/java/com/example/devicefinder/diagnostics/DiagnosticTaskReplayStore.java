package com.example.devicefinder.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/** Durable one-time task/nonce ledger; fail-closed at capacity and across process restarts. */
public final class DiagnosticTaskReplayStore {
    private static final String PREFS = "rescue2_diagnostic_task_replay_v1";
    private static final String TASK_PREFIX = "task.";
    private static final String NONCE_PREFIX = "nonce.";
    private static final int MAX_ENTRIES = 1024;
    private final SharedPreferences preferences;

    public DiagnosticTaskReplayStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Call only after signature, target and expiry validation have succeeded. */
    public synchronized boolean acceptOnce(DiagnosticTaskEnvelope envelope, long nowEpochMs) {
        if (envelope == null || !envelope.isValidAt(nowEpochMs)
                || envelope.expiresAtEpochMs <= nowEpochMs) return false;
        String taskKey = TASK_PREFIX + digest(envelope.adminKeyFingerprint + "\0" + envelope.taskId);
        String nonceKey = NONCE_PREFIX + digest(envelope.adminKeyFingerprint + "\0" + envelope.nonce);
        Map<String, ?> all = preferences.getAll();
        SharedPreferences.Editor editor = preferences.edit();
        int activeEntries = 0;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!(entry.getKey().startsWith(TASK_PREFIX) || entry.getKey().startsWith(NONCE_PREFIX)))
                continue;
            Object value = entry.getValue();
            if (!(value instanceof Long)) return false;
            if (((Long) value) <= nowEpochMs) editor.remove(entry.getKey());
            else activeEntries++;
        }
        if (all.containsKey(taskKey) || all.containsKey(nonceKey)) return false;
        if (activeEntries >= MAX_ENTRIES) return false;
        editor.putLong(taskKey, envelope.expiresAtEpochMs);
        editor.putLong(nonceKey, envelope.expiresAtEpochMs);
        return editor.commit();
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
