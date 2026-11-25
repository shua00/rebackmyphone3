package com.example.devicefinder.diagnostics;

import java.util.Map;

/** Stable JSON export for offline comparison; intentionally excludes all secret/network payload data. */
public final class LanTransferDiagnosticReport {
    private LanTransferDiagnosticReport() {}

    public static String toJson(LanTransferDiagnosticRun run) {
        if (run == null) throw new IllegalArgumentException("Diagnostic run is required");
        LanTransferDiagnosticRun.Metadata m = run.metadata;
        StringBuilder out = new StringBuilder(1024);
        out.append('{');
        field(out, "schemaVersion", LanTransferDiagnosticRun.SCHEMA_VERSION).append(',');
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
        field(out, "passCount", run.count(LanTransferDiagnosticRun.Status.PASS)).append(',');
        field(out, "failCount", run.count(LanTransferDiagnosticRun.Status.FAIL)).append(',');
        field(out, "skippedCount", run.count(LanTransferDiagnosticRun.Status.SKIPPED)).append(',');
        field(out, "acceptancePassed", run.isCompleteAndPassing()).append(',');
        out.append("\"cases\":[");
        for (int i = 0; i < run.cases.size(); i++) {
            if (i > 0) out.append(',');
            LanTransferDiagnosticRun.CaseResult c = run.cases.get(i);
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
