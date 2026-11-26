package com.example.devicefinder.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded task schema shared with Rescue2 Python. Not a signature verifier or transport. */
public final class DiagnosticTaskEnvelope {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TARGETS = 64;
    public static final int MAX_PROBES = 16;
    public static final long MIN_TASK_TTL_MS = 1_000L;
    public static final long MAX_TASK_TTL_MS = 30L * 60L * 1000L;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ALLOWED_PROBES;
    static {
        Set<String> values = new HashSet<>();
        Collections.addAll(values, "android.runtime", "network.connectivity", "app.install",
                "lan.ipv4_interface", "lan.udp_bind", "lan.transfer_runtime");
        ALLOWED_PROBES = Collections.unmodifiableSet(values);
    }

    public final String taskId, adminKeyFingerprint, nonce;
    public final List<String> targetParticipantIds, probeIds;
    public final long createdAtEpochMs, expiresAtEpochMs;

    public DiagnosticTaskEnvelope(String taskId, String fingerprint, List<String> targets,
            List<String> probes, long createdAt, long expiresAt, String nonce) {
        this.taskId = token(taskId, "taskId");
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches())
            throw new IllegalArgumentException("Invalid admin key fingerprint");
        this.adminKeyFingerprint = fingerprint;
        this.targetParticipantIds = immutableTokens(targets, "target participant", MAX_TARGETS);
        this.probeIds = immutableTokens(probes, "probe", MAX_PROBES);
        for (String probe : this.probeIds)
            if (!ALLOWED_PROBES.contains(probe))
                throw new IllegalArgumentException("Probe is not in the read-only allowlist");
        if (createdAt <= 0 || expiresAt <= 0 || expiresAt - createdAt < MIN_TASK_TTL_MS
                || expiresAt - createdAt > MAX_TASK_TTL_MS)
            throw new IllegalArgumentException("Diagnostic task lifetime is outside policy");
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{22,64}"))
            throw new IllegalArgumentException("Invalid task nonce");
        this.createdAtEpochMs = createdAt;
        this.expiresAtEpochMs = expiresAt;
        this.nonce = nonce;
    }

    public boolean isValidAt(long nowEpochMs) {
        return nowEpochMs >= createdAtEpochMs && nowEpochMs < expiresAtEpochMs;
    }

    /**
     * Serialize the already-validated ASCII token fields without relying on Android's
     * JSONObject.put implementation. This keeps the wire representation deterministic
     * across Android/Robolectric versions and byte-compatible with the Python codec.
     */
    public String toJson() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
                .append(",\"taskId\":\"").append(taskId)
                .append("\",\"adminKeyFingerprint\":\"").append(adminKeyFingerprint)
                .append("\",\"targetParticipantIds\":");
        appendStringArray(json, targetParticipantIds);
        json.append(",\"probeIds\":");
        appendStringArray(json, probeIds);
        json.append(",\"createdAtEpochMs\":").append(createdAtEpochMs)
                .append(",\"expiresAtEpochMs\":").append(expiresAtEpochMs)
                .append(",\"nonce\":\"").append(nonce).append("\"}");
        return json.toString();
    }

    public static DiagnosticTaskEnvelope fromJson(String encoded) throws JSONException {
        if (encoded == null || encoded.length() > 16 * 1024)
            throw new IllegalArgumentException("Diagnostic task exceeds size limit");
        JSONObject json = new JSONObject(encoded);
        String[] required = {"schemaVersion", "taskId", "adminKeyFingerprint",
                "targetParticipantIds", "probeIds", "createdAtEpochMs", "expiresAtEpochMs", "nonce"};
        if (json.length() != required.length)
            throw new IllegalArgumentException("Invalid diagnostic task fields");
        for (String key : required) if (!json.has(key))
            throw new IllegalArgumentException("Missing diagnostic task field: " + key);
        if (json.getInt("schemaVersion") != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported diagnostic task schema");
        return new DiagnosticTaskEnvelope(json.getString("taskId"),
                json.getString("adminKeyFingerprint"), readArray(json.getJSONArray("targetParticipantIds")),
                readArray(json.getJSONArray("probeIds")), json.getLong("createdAtEpochMs"),
                json.getLong("expiresAtEpochMs"), json.getString("nonce"));
    }

    private static List<String> immutableTokens(List<String> values, String field, int maximum) {
        if (values == null || values.isEmpty() || values.size() > maximum)
            throw new IllegalArgumentException("Invalid " + field + " count");
        ArrayList<String> copy = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String valid = token(value, field);
            if (!seen.add(valid)) throw new IllegalArgumentException("Duplicate " + field);
            copy.add(valid);
        }
        return Collections.unmodifiableList(copy);
    }
    private static String token(String value, String field) {
        if (value == null || !TOKEN.matcher(value).matches())
            throw new IllegalArgumentException("Invalid " + field);
        return value;
    }
    private static void appendStringArray(StringBuilder json, List<String> values) {
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(values.get(i)).append('"');
        }
        json.append(']');
    }
    private static List<String> readArray(JSONArray array) throws JSONException {
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }
}
