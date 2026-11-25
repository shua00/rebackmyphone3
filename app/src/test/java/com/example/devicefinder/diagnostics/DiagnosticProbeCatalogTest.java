package com.example.devicefinder.diagnostics;

import android.os.Build;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DiagnosticProbeCatalogTest {
    @Test public void catalogIsFixedReadOnlyAllowlistAndProducesBoundedReport() {
        List<DiagnosticExecutor.TestCase> tests =
                DiagnosticProbeCatalog.localReadOnlyTests(RuntimeEnvironment.getApplication());
        Set<String> ids = new HashSet<>();
        for (DiagnosticExecutor.TestCase test : tests) ids.add(test.id);
        assertEquals(6, tests.size());
        assertTrue(ids.contains("android.runtime"));
        assertTrue(ids.contains("network.connectivity"));
        assertTrue(ids.contains("app.install"));
        assertTrue(ids.contains("lan.ipv4_interface"));
        assertTrue(ids.contains("lan.udp_bind"));
        assertTrue(ids.contains("lan.transfer_runtime"));
        assertFalse(ids.contains("shell"));
        assertFalse(ids.contains("logcat"));
        assertFalse(ids.contains("chat.content"));

        DiagnosticRun.Metadata metadata = DiagnosticRun.Metadata.createForFeature(
                "android_local", System.currentTimeMillis(), "test", "test",
                "Robolectric", Build.VERSION.SDK_INT, "UNKNOWN", "READ_ONLY", "LOCAL");
        DiagnosticRun run = DiagnosticExecutor.execute(metadata, tests, tests.size(), null, null);
        assertEquals(6, run.cases.size());
        for (DiagnosticRun.CaseResult result : run.cases) {
            assertTrue(result.metrics.size() <= 4);
            for (String key : result.metrics.keySet()) {
                assertFalse(key.toLowerCase(java.util.Locale.ROOT).contains("ip"));
                assertFalse(key.toLowerCase(java.util.Locale.ROOT).contains("ssid"));
                assertFalse(key.toLowerCase(java.util.Locale.ROOT).contains("payload"));
            }
        }
        assertTrue(DiagnosticJsonReport.toJson(run).contains("\"featureId\":\"android_local\""));
    }
    @Test public void networkMetadataUsesOnlyCoarseCategory() {
        String type = DiagnosticProbeCatalog.detectNetworkType(RuntimeEnvironment.getApplication());
        assertTrue(type.equals("WIFI") || type.equals("CELLULAR") || type.equals("OTHER")
                || type.equals("OFFLINE") || type.equals("UNKNOWN"));
    }
}
