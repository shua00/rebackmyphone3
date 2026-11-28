package com.example.devicefinder.diagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import com.example.devicefinder.Rescue2AdminTrustStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes only the signed task's registered read-only probes after explicit device consent. */
public final class DiagnosticTaskRunAdapter {
    private DiagnosticTaskRunAdapter() {}

    public static DiagnosticRun execute(Context context, DiagnosticTaskVerifier.Result verification,
            Rescue2AdminTrustStore trustStore, DiagnosticTaskConsentStore consentStore,
            DiagnosticRunHistoryStore history,
            long nowEpochMs, DiagnosticExecutor.Cancellation cancellation,
            DiagnosticExecutor.Listener progress) throws Exception {
        if (context == null || verification == null || !verification.accepted
                || !verification.replayProtected || verification.envelope == null
                || trustStore == null || consentStore == null || history == null)
            throw new SecurityException("A verified task, device consent and history store are required");
        DiagnosticTaskEnvelope envelope = verification.envelope;
        if (!trustStore.isTrusted(envelope.adminKeyFingerprint))
            throw new SecurityException("Rescue2 administrator trust was revoked");
        if (!consentStore.isApproved(envelope.adminKeyFingerprint, envelope.taskId, nowEpochMs))
            throw new SecurityException("Diagnostic task has not been explicitly approved on this device");

        List<DiagnosticExecutor.TestCase> selected = selectRequestedProbes(
                DiagnosticProbeCatalog.localReadOnlyTests(context), envelope.probeIds);
        String runId = UUID.nameUUIDFromBytes((envelope.adminKeyFingerprint + ":"
                + envelope.taskId).getBytes(StandardCharsets.UTF_8)).toString();
        String appVersion = "unknown";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (info.versionName != null && !info.versionName.trim().isEmpty()) appVersion = info.versionName;
        } catch (Exception ignored) { }
        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        DiagnosticRun.Metadata metadata = new DiagnosticRun.Metadata(
                "rescue2_remote_diagnostic", runId, nowEpochMs, appVersion, "unknown",
                model.isEmpty() ? "unknown" : model, Build.VERSION.SDK_INT,
                DiagnosticProbeCatalog.detectNetworkType(context), "rescue2", "participant");
        DiagnosticRunRecorder recorder = new DiagnosticRunRecorder(DiagnosticRunRecorder.sqlite(history));
        DiagnosticRun saved = recorder.executeAndRecord(metadata, selected, selected.size(),
                cancellation, progress);
        if (!consentStore.markCompleted(envelope.adminKeyFingerprint, envelope.taskId, nowEpochMs))
            throw new IllegalStateException("Diagnostic report saved but consent state could not be completed");
        return saved;
    }

    static List<DiagnosticExecutor.TestCase> selectRequestedProbes(
            List<DiagnosticExecutor.TestCase> registered, List<String> requestedIds) {
        if (registered == null || requestedIds == null || requestedIds.isEmpty())
            throw new IllegalArgumentException("Registered probes and requested probe IDs are required");
        Map<String, DiagnosticExecutor.TestCase> byId = new HashMap<>();
        for (DiagnosticExecutor.TestCase test : registered) {
            if (test == null || byId.put(test.id, test) != null)
                throw new IllegalArgumentException("Invalid or duplicate registered diagnostic probe");
        }
        ArrayList<DiagnosticExecutor.TestCase> selected = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String id : requestedIds) {
            if (id == null || !seen.add(id))
                throw new IllegalArgumentException("Duplicate or invalid requested probe");
            DiagnosticExecutor.TestCase test = byId.get(id);
            if (test == null) throw new SecurityException("Probe is not registered: " + id);
            selected.add(test);
        }
        return java.util.Collections.unmodifiableList(selected);
    }
}
