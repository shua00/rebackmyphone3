package com.example.devicefinder.diagnostics;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Bounded, transport-neutral diagnostic report envelope. Contains no raw peer/device identity. */
public final class DiagnosticBatchEnvelope {
    public static final int MAX_REPORT_JSON_CHARS = 128 * 1024;
    public final String batchId, eventId, runId, participantId, reportJson;
    public final long sequence, createdAtEpochMs;

    public DiagnosticBatchEnvelope(String batchId, String eventId, String runId,
            String participantId, long sequence, long createdAtEpochMs, String reportJson) {
        this.batchId = token(batchId, "batchId");
        this.eventId = token(eventId, "eventId");
        this.runId = token(runId, "runId");
        this.participantId = token(participantId, "participantId");
        if (sequence < 0 || createdAtEpochMs <= 0 || reportJson == null
                || reportJson.isEmpty() || reportJson.length() > MAX_REPORT_JSON_CHARS)
            throw new IllegalArgumentException("Invalid diagnostic batch envelope");
        this.sequence = sequence;
        this.createdAtEpochMs = createdAtEpochMs;
        this.reportJson = reportJson;
    }

    public static DiagnosticBatchEnvelope create(String batchId, String runId,
            String participantId, long sequence, long nowEpochMs, String reportJson) {
        return new DiagnosticBatchEnvelope(batchId, UUID.randomUUID().toString(), runId,
                participantId, sequence, nowEpochMs, reportJson);
    }

    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("schemaVersion", 1);
            json.put("batchId", batchId);
            json.put("eventId", eventId);
            json.put("runId", runId);
            json.put("participantId", participantId);
            json.put("sequence", sequence);
            json.put("createdAtEpochMs", createdAtEpochMs);
            json.put("reportJson", reportJson);
            return json.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode diagnostic envelope", impossible);
        }
    }

    public static DiagnosticBatchEnvelope fromJson(String encoded) throws JSONException {
        if (encoded == null || encoded.length() > MAX_REPORT_JSON_CHARS + 2048)
            throw new IllegalArgumentException("Diagnostic envelope exceeds size limit");
        JSONObject json = new JSONObject(encoded);
        if (json.optInt("schemaVersion", -1) != 1)
            throw new IllegalArgumentException("Unsupported diagnostic envelope schema");
        return new DiagnosticBatchEnvelope(json.getString("batchId"), json.getString("eventId"),
                json.getString("runId"), json.getString("participantId"),
                json.getLong("sequence"), json.getLong("createdAtEpochMs"),
                json.getString("reportJson"));
    }

    private static String token(String value, String field) {
        if (value == null || value.trim().isEmpty() || value.length() > 80)
            throw new IllegalArgumentException("Invalid " + field);
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.'))
                throw new IllegalArgumentException("Invalid " + field);
        }
        return trimmed;
    }
}
