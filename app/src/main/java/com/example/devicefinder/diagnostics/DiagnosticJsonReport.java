package com.example.devicefinder.diagnostics;

import java.util.Map;

/** Stable JSON export for offline comparison; intentionally excludes all secret/network payload data. */
public final class DiagnosticJsonReport {
    private DiagnosticJsonReport() {}

    public static String toJson(DiagnosticRun run) {
        if (run == null) throw new IllegalArgumentException("Diagnostic run is required");
        DiagnosticRun.Metadata m = run.metadata;
        StringBuilder out = new StringBuilder(1024);
        out.append('{');
        field(out, "schemaVersion", DiagnosticRun.SCHEMA_VERSION).append(',');
        field(out, "featureId", m.featureId).append(',');
        field(out, "runId", m.runId).append(',');
        field(out, "startedAtEpochMs", m.startedAtEpochMs).append(',');
        field(out, "appVersion", m.appVersion).append(',');
        field(out, "commit", m.commit).append(',');
        field(out, "deviceModel", m.deviceModel).append(',');
        field(out, "androidApi", m.androidApi).append(',');
        field(out, "networkType", m.networkType).append(',');
        field(out, "profile", m.profile).append(',');
        field(out, "side", m.side).append(',');
        field(out, "expectedCaseCount", run.expectedCaseCount).append(',');
        field(out, "passCount", run.count(DiagnosticRun.Status.PASS)).append(',');
        field(out, "failCount", run.count(DiagnosticRun.Status.FAIL)).append(',');
        field(out, "skippedCount", run.count(DiagnosticRun.Status.SKIPPED)).append(',');
        field(out, "timeoutCount", run.count(DiagnosticRun.Status.TIMEOUT)).append(',');
        field(out, "cancelledCount", run.count(DiagnosticRun.Status.CANCELLED)).append(',');
        field(out, "acceptancePassed", run.isCompleteAndPassing()).append(',');
        out.append("\"cases\":[");
        for (int i = 0; i < run.cases.size(); i++) {
            if (i > 0) out.append(',');
            DiagnosticRun.CaseResult c = run.cases.get(i);
            out.append('{');
            field(out, "caseId", c.caseId).append(',');
            field(out, "status", c.status.name()).append(',');
            field(out, "durationMs", c.durationMs).append(',');
            field(out, "failureCode", c.failureCode).append(',');
            out.append("\"metrics\":{");
            int metricIndex = 0;
            for (Map.Entry<String, Long> metric : c.metrics.entrySet()) {
                if (metricIndex++ > 0) out.append(',');
                quote(out, metric.getKey()).append(':').append(metric.getValue());
            }
            out.append("}}");
        }
        return out.append("]}").toString();
    }

    /** Reconstructs a validated report from the stable exported JSON schema. */
    public static DiagnosticRun fromJson(String encoded) throws org.json.JSONException {
        if (encoded == null || encoded.length() > 128 * 1024)
            throw new IllegalArgumentException("Diagnostic report exceeds size limit");
        org.json.JSONObject root = new org.json.JSONObject(encoded);
        if (root.getInt("schemaVersion") != DiagnosticRun.SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported diagnostic report schema");
        DiagnosticRun.Metadata metadata = new DiagnosticRun.Metadata(
                root.getString("featureId"), root.getString("runId"),
                root.getLong("startedAtEpochMs"), root.getString("appVersion"),
                root.getString("commit"), root.getString("deviceModel"),
                root.getInt("androidApi"), root.getString("networkType"),
                root.getString("profile"), root.getString("side"));
        int expected = root.getInt("expectedCaseCount");
        org.json.JSONArray encodedCases = root.getJSONArray("cases");
        if (encodedCases.length() > 128)
            throw new IllegalArgumentException("Too many diagnostic cases");
        java.util.ArrayList<DiagnosticRun.CaseResult> cases = new java.util.ArrayList<>();
        for (int i = 0; i < encodedCases.length(); i++) {
            org.json.JSONObject item = encodedCases.getJSONObject(i);
            DiagnosticRun.Status status = DiagnosticRun.Status.valueOf(item.getString("status"));
            org.json.JSONObject encodedMetrics = item.getJSONObject("metrics");
            if (encodedMetrics.length() > 32)
                throw new IllegalArgumentException("Too many diagnostic metrics");
            java.util.LinkedHashMap<String, Long> metrics = new java.util.LinkedHashMap<>();
            java.util.Iterator<String> keys = encodedMetrics.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = encodedMetrics.get(key);
                if (!(value instanceof Number))
                    throw new IllegalArgumentException("Diagnostic metric must be numeric");
                double numeric = ((Number) value).doubleValue();
                long integer = ((Number) value).longValue();
                if (!Double.isFinite(numeric) || numeric != integer || integer < 0)
                    throw new IllegalArgumentException("Diagnostic metric must be a non-negative integer");
                metrics.put(key, integer);
            }
            cases.add(new DiagnosticRun.CaseResult(item.getString("caseId"), status,
                    item.getLong("durationMs"), item.optString("failureCode", ""), metrics));
        }
        return new DiagnosticRun(metadata, cases, expected);
    }

    private static StringBuilder field(StringBuilder out, String key, String value) {
        quote(out, key).append(':');
        return quote(out, value);
    }
    private static StringBuilder field(StringBuilder out, String key, long value) {
        return quote(out, key).append(':').append(value);
    }
    private static StringBuilder field(StringBuilder out, String key, int value) {
        return quote(out, key).append(':').append(value);
    }
    private static StringBuilder field(StringBuilder out, String key, boolean value) {
        return quote(out, key).append(':').append(value);
    }
    private static StringBuilder quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"');
    }
}
