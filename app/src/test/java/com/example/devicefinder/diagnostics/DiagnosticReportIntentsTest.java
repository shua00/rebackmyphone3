package com.example.devicefinder.diagnostics;

import android.content.Intent;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticReportIntentsTest {
    private DiagnosticRun report() {
        DiagnosticRun.Metadata metadata = new DiagnosticRun.Metadata("network", "intent-test",
                System.currentTimeMillis(), "1.0", "abc123", "TestDevice", 34,
                "wifi", "SECURE", "sender");
        return new DiagnosticRun(metadata, Collections.singletonList(
                new DiagnosticRun.CaseResult("probe", DiagnosticRun.Status.PASS,
                        10, "", Collections.singletonMap("rttMs", 5L))), 1);
    }

    @Test public void saveIntentUsesSafAndCorrectMimeAndExtension() {
        Intent intent = DiagnosticReportIntents.createSaveIntent(report(),
                DiagnosticReportExporter.Format.MARKDOWN);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("text/markdown", intent.getType());
        assertEquals("diagnostic-intent-test.md", intent.getStringExtra(Intent.EXTRA_TITLE));
    }

    @Test public void shareIntentContainsReadableAndMachineReadableReport() {
        Intent intent = DiagnosticReportIntents.createShareIntent(report());
        assertEquals(Intent.ACTION_CHOOSER, intent.getAction());
        Intent send = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(send);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        String body = send.getStringExtra(Intent.EXTRA_TEXT);
        assertTrue(body.contains("# Diagnostic report"));
        assertTrue(body.contains("Machine-readable JSON"));
        assertTrue(body.contains("\"schemaVersion\":1"));
    }

    @Test public void htmlViewIntentGrantsReadAccess() {
        Uri uri = Uri.parse("content://test/reports/report.html");
        Intent intent = DiagnosticReportIntents.createHtmlViewIntent(uri);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("text/html", intent.getType());
        assertEquals(uri, intent.getData());
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test public void writeToUriRejectsMissingDestination() {
        try {
            DiagnosticReportIntents.writeToUri(RuntimeEnvironment.getApplication(), null,
                    report(), DiagnosticReportExporter.Format.JSON);
            fail("missing destination must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Context and destination"));
        } catch (Exception unexpected) {
            fail(unexpected.getMessage());
        }
    }
}
