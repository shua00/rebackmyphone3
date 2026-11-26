package com.example.devicefinder.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Durable per-task consent state machine for ordinary Android diagnostic participants. */
public final class DiagnosticTaskConsentStore {
    public enum State { PENDING, APPROVED, REJECTED, CANCELLED, COMPLETED, EXPIRED }
    private static final String PREFS = "rescue2_diagnostic_task_consent_v1";
    private static final String PREFIX = "task.";
    private static final int MAX_ACTIVE_TASKS = 64;
    private static final int MAX_RETAINED_TASKS = 256;
    private final SharedPreferences preferences;

    public DiagnosticTaskConsentStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized boolean registerPending(DiagnosticTaskEnvelope envelope, long nowEpochMs) {
        if (envelope == null || !envelope.isValidAt(nowEpochMs)) return false;
        String key = key(envelope.adminKeyFingerprint, envelope.taskId);
        String existing = preferences.getString(key, null);
        Parsed existingParsed = parse(existing);
        if (existingParsed != null && existingParsed.expiresAtEpochMs > nowEpochMs) return false;

        SharedPreferences.Editor editor = preferences.edit();
        int active = 0, retained = 0;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) continue;
            Parsed parsed = parse(entry.getValue() instanceof String ? (String) entry.getValue() : null);
            if (parsed == null) return false;
            if (parsed.expiresAtEpochMs <= nowEpochMs) {
                editor.remove(entry.getKey());
                continue;
            }
            retained++;
            if (parsed.state == State.PENDING || parsed.state == State.APPROVED) active++;
        }
        if (active >= MAX_ACTIVE_TASKS || retained >= MAX_RETAINED_TASKS) {
            editor.commit();
            return false;
        }
        editor.putString(key, encode(State.PENDING, envelope.expiresAtEpochMs));
        return editor.commit();
    }

    public synchronized State getState(String fingerprint, String taskId, long nowEpochMs) {
        if (!validFingerprint(fingerprint) || !validTaskId(taskId)) return null;
        Parsed parsed = parse(preferences.getString(key(fingerprint, taskId), null));
        if (parsed == null) return null;
        if ((parsed.state == State.PENDING || parsed.state == State.APPROVED)
                && parsed.expiresAtEpochMs <= nowEpochMs) return State.EXPIRED;
        return parsed.state;
    }

    public synchronized boolean approve(String fingerprint, String taskId, long nowEpochMs) {
        return transition(fingerprint, taskId, nowEpochMs, State.PENDING, State.APPROVED);
    }
    public synchronized boolean reject(String fingerprint, String taskId, long nowEpochMs) {
        return transition(fingerprint, taskId, nowEpochMs, State.PENDING, State.REJECTED);
    }
    public synchronized boolean cancel(String fingerprint, String taskId, long nowEpochMs) {
        State state = getState(fingerprint, taskId, nowEpochMs);
        if (state != State.PENDING && state != State.APPROVED) return false;
        return writeState(fingerprint, taskId, stateExpiry(fingerprint, taskId), State.CANCELLED);
    }
    public synchronized boolean markCompleted(String fingerprint, String taskId, long nowEpochMs) {
        return transition(fingerprint, taskId, nowEpochMs, State.APPROVED, State.COMPLETED);
    }
    public synchronized boolean isApproved(String fingerprint, String taskId, long nowEpochMs) {
        return getState(fingerprint, taskId, nowEpochMs) == State.APPROVED;
    }

    public synchronized List<String> pendingTaskIds(String fingerprint, long nowEpochMs) {
        if (!validFingerprint(fingerprint)) return Collections.emptyList();
        ArrayList<String> result = new ArrayList<>();
        String prefix = PREFIX + fingerprint + ".";
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            Parsed parsed = parse(entry.getValue() instanceof String ? (String) entry.getValue() : null);
            if (parsed != null && parsed.state == State.PENDING && parsed.expiresAtEpochMs > nowEpochMs)
                result.add(entry.getKey().substring(prefix.length()));
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private boolean transition(String fingerprint, String taskId, long now,
            State expected, State next) {
        if (getState(fingerprint, taskId, now) != expected) return false;
        return writeState(fingerprint, taskId, stateExpiry(fingerprint, taskId), next);
    }
    private boolean writeState(String fingerprint, String taskId, long expiry, State state) {
        if (expiry <= 0) return false;
        return preferences.edit().putString(key(fingerprint, taskId), encode(state, expiry)).commit();
    }
    private long stateExpiry(String fingerprint, String taskId) {
        Parsed parsed = parse(preferences.getString(key(fingerprint, taskId), null));
        return parsed == null ? 0L : parsed.expiresAtEpochMs;
    }
    private static String key(String fingerprint, String taskId) { return PREFIX + fingerprint + "." + taskId; }
    private static String encode(State state, long expiry) { return state.name() + ":" + expiry; }
    private static Parsed parse(String value) {
        if (value == null) return null;
        int split = value.indexOf(':');
        if (split <= 0) return null;
        try {
            State state = State.valueOf(value.substring(0, split));
            long expiry = Long.parseLong(value.substring(split + 1));
            if (expiry <= 0) return null;
            return new Parsed(state, expiry);
        } catch (RuntimeException malformed) { return null; }
    }
    private static boolean validFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
    private static boolean validTaskId(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,80}");
    }
    private static final class Parsed {
        final State state;
        final long expiresAtEpochMs;
        Parsed(State state, long expiry) { this.state = state; this.expiresAtEpochMs = expiry; }
    }
}
