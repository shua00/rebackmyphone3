package com.example.devicefinder.diagnostics;

import java.util.Map;

/** Offline human-readable report views. No Android or external rendering dependencies. */
public final class DiagnosticReportRenderer {
    private DiagnosticReportRenderer() {}
    public static String toMarkdown(DiagnosticRun run) {
        requireRun(run);
        DiagnosticRun.Metadata m=run.metadata;
        StringBuilder out=new StringBuilder(2048);
        out.append("# Diagnostic report\n\n")
           .append("- Schema: ").append(DiagnosticRun.SCHEMA_VERSION).append("\n")
           .append("- Feature: ").append(md(m.featureId)).append("\n")
           .append("- Run ID: ").append(md(m.runId)).append("\n")
           .append("- Started (epoch ms): ").append(m.startedAtEpochMs).append("\n")
           .append("- App: ").append(md(m.appVersion)).append("\n")
           .append("- Commit: ").append(md(m.commit)).append("\n")
           .append("- Device: ").append(md(m.deviceModel)).append(" (Android API ").append(m.androidApi).append(")\n")
           .append("- Network: ").append(md(m.networkType)).append("\n")
           .append("- Profile: ").append(md(m.profile)).append("\n")
           .append("- Side: ").append(md(m.side)).append("\n\n")
           .append("## Summary\n\n")
           .append("- Acceptance: **").append(run.isCompleteAndPassing()?"PASS":"NOT PASSED").append("**\n")
           .append("- Cases: ").append(run.cases.size()).append(" / ").append(run.expectedCaseCount).append("\n")
           .append("- Passed: ").append(run.count(DiagnosticRun.Status.PASS)).append("\n")
           .append("- Failed: ").append(run.count(DiagnosticRun.Status.FAIL)).append("\n")
           .append("- Skipped: ").append(run.count(DiagnosticRun.Status.SKIPPED)).append("\n")
           .append("- Timed out: ").append(run.count(DiagnosticRun.Status.TIMEOUT)).append("\n")
           .append("- Cancelled: ").append(run.count(DiagnosticRun.Status.CANCELLED)).append("\n\n")
           .append("## Test cases\n\n| Test | Status | Duration (ms) | Failure code | Metrics |\n")
           .append("|---|---|---:|---|---|\n");
        for(DiagnosticRun.CaseResult c:run.cases) out.append("| ").append(md(c.caseId)).append(" | ")
                .append(c.status.name()).append(" | ").append(c.durationMs).append(" | ")
                .append(md(c.failureCode)).append(" | ").append(md(metrics(c.metrics))).append(" |\n");
        out.append("\n## AI analysis notes\n\nCompare reports with matching schema versions. ")
           .append("Missing, skipped, timed-out, and cancelled cases are not passes. ")
           .append("Correlate device-side reports by run ID and feature ID.\n");
        return out.toString();
    }
    public static String toHtml(DiagnosticRun run) {
        requireRun(run);
        DiagnosticRun.Metadata m=run.metadata;
        StringBuilder out=new StringBuilder(4096);
        out.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
           .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
           .append("<title>Diagnostic report</title><style>")
           .append("body{font:16px system-ui,sans-serif;line-height:1.5;margin:0;padding:20px;background:#f5f6f8;color:#202124}")
           .append("main{max-width:960px;margin:auto;background:white;padding:20px;border-radius:12px}")
           .append("dl{display:grid;grid-template-columns:minmax(110px,180px) 1fr;gap:6px 14px}")
           .append("dt{font-weight:600;color:#555}dd{margin:0;overflow-wrap:anywhere}")
           .append(".summary{display:flex;flex-wrap:wrap;gap:12px}.tile{padding:12px 16px;background:#f1f3f4;border-radius:8px}")
           .append("table{border-collapse:collapse;width:100%;display:block;overflow-x:auto}th,td{text-align:left;padding:9px;border-bottom:1px solid #ddd;vertical-align:top}")
           .append("code{overflow-wrap:anywhere}@media(max-width:520px){body{padding:8px}main{padding:14px}dl{grid-template-columns:1fr}}")
           .append("</style></head><body><main><h1>Diagnostic report</h1><dl>");
        row(out,"Feature",m.featureId); row(out,"Schema",Integer.toString(DiagnosticRun.SCHEMA_VERSION));
        row(out,"Run ID",m.runId); row(out,"Started (epoch ms)",Long.toString(m.startedAtEpochMs));
        row(out,"App version",m.appVersion); row(out,"Commit",m.commit);
        row(out,"Device",m.deviceModel+" (Android API "+m.androidApi+")");
        row(out,"Network",m.networkType); row(out,"Profile",m.profile); row(out,"Side",m.side);
        out.append("</dl><h2>Summary</h2><div class=\"summary\">");
        tile(out,"Acceptance",run.isCompleteAndPassing()?"PASS":"NOT PASSED");
        tile(out,"Cases",run.cases.size()+" / "+run.expectedCaseCount);
        tile(out,"Passed",Integer.toString(run.count(DiagnosticRun.Status.PASS)));
        tile(out,"Failed",Integer.toString(run.count(DiagnosticRun.Status.FAIL)));
        tile(out,"Skipped",Integer.toString(run.count(DiagnosticRun.Status.SKIPPED)));
        tile(out,"Timed out",Integer.toString(run.count(DiagnosticRun.Status.TIMEOUT)));
        tile(out,"Cancelled",Integer.toString(run.count(DiagnosticRun.Status.CANCELLED)));
        out.append("</div><h2>Test cases</h2><table><thead><tr><th>Test</th><th>Status</th><th>Duration (ms)</th><th>Failure code</th><th>Metrics</th></tr></thead><tbody>");
        for(DiagnosticRun.CaseResult c:run.cases) out.append("<tr><td>").append(html(c.caseId))
                .append("</td><td>").append(c.status.name()).append("</td><td>").append(c.durationMs)
                .append("</td><td>").append(html(c.failureCode)).append("</td><td><code>")
                .append(html(metrics(c.metrics))).append("</code></td></tr>");
        out.append("</tbody></table><h2>AI analysis notes</h2><p>Missing, skipped, timed-out, and cancelled tests are not passes.</p>")
           .append("</main></body></html>");
        return out.toString();
    }
    private static void requireRun(DiagnosticRun run){if(run==null)throw new IllegalArgumentException("Diagnostic run is required");}
    private static String metrics(Map<String,Long> values){StringBuilder b=new StringBuilder();for(Map.Entry<String,Long> e:values.entrySet()){if(b.length()>0)b.append(", ");b.append(e.getKey()).append('=').append(e.getValue());}return b.toString();}
    private static String md(String s){return s.replace("\\","\\\\").replace("|","\\|").replace("\r"," ").replace("\n"," ");}
    private static String html(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static void row(StringBuilder b,String k,String v){b.append("<dt>").append(html(k)).append("</dt><dd>").append(html(v)).append("</dd>");}
    private static void tile(StringBuilder b,String k,String v){b.append("<div class=\"tile\"><strong>").append(html(k)).append("</strong><br>").append(html(v)).append("</div>");}
}
