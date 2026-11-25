package com.example.devicefinder.diagnostics;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.IOException;
import java.io.OutputStream;

/** Android adapters for user-controlled save, share-to-AI, and offline HTML viewing. */
public final class DiagnosticReportIntents {
    private DiagnosticReportIntents() {}

    public static Intent createSaveIntent(DiagnosticRun run, DiagnosticReportExporter.Format format) {
        if (run == null || format == null) throw new IllegalArgumentException("Run and format are required");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(format.mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, "diagnostic-" + safeFilePart(run.metadata.runId)
                + "." + format.extension);
        return intent;
    }

    /** Call with the URI returned by ACTION_CREATE_DOCUMENT after the user chooses a destination. */
    public static void writeToUri(Context context, Uri destination, DiagnosticRun run,
            DiagnosticReportExporter.Format format) throws IOException {
        if (context == null || destination == null) throw new IllegalArgumentException("Context and destination are required");
        OutputStream output = context.getContentResolver().openOutputStream(destination, "wt");
        if (output == null) throw new IOException("Unable to open selected report destination");
        try (OutputStream stream = output) {
            DiagnosticReportExporter.write(run, format, stream);
        }
    }

    /** Shares a human-readable report plus machine-readable JSON to AI or another app. */
    public static Intent createShareIntent(DiagnosticRun run) {
        if (run == null) throw new IllegalArgumentException("Diagnostic run is required");
        String text = DiagnosticReportRenderer.toMarkdown(run)
                + "\n\n---\n\n## Machine-readable JSON\n\n"
                + DiagnosticJsonReport.toJson(run) + "\n";
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Diagnostic report - " + run.metadata.featureId);
        intent.putExtra(Intent.EXTRA_TEXT, text);
        return Intent.createChooser(intent, "Share diagnostic report");
    }

    /** Opens a previously exported HTML report using the selected browser/viewer. */
    public static Intent createHtmlViewIntent(Uri reportUri) {
        if (reportUri == null) throw new IllegalArgumentException("Report URI is required");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(reportUri, "text/html");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private static String safeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
