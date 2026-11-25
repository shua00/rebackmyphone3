package com.example.devicefinder.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, transport-independent result model for repeatable LAN acceptance runs.
 *
 * The model deliberately has no Android, socket, chat, or file-transfer dependencies so it can
 * be reused by an optional in-app diagnostic UI, instrumentation tests, and offline analysis.
 * Callers must never place credentials, raw peer IDs, IP addresses, payloads, or file contents
 * in this report.
 */
public final class DiagnosticRun {
    public static final int SCHEMA_VERSION = 1;
    public enum Status { PASS, FAIL, SKIPPED }

    public static final class Metadata {
        public final String runId;
        public final long startedAtEpochMs;
        public final String featureId, appVersion, commit, deviceModel, networkType, profile, side;
        public final int androidApi;

        public Metadata(String runId, long startedAtEpochMs, String appVersion, String commit,
                String deviceModel, int androidApi, String networkType, String profile, String side) {
            this("lan_transfer", runId, startedAtEpochMs, appVersion, commit, deviceModel,
                    androidApi, networkType, profile, side);
        }

        public Metadata(String featureId, String runId, long startedAtEpochMs, String appVersion,
                String commit, String deviceModel, int androidApi, String networkType,
                String profile, String side) {
            this.featureId = bounded(featureId, 64, "featureId");
            this.runId = bounded(runId, 64, "runId");
            this.startedAtEpochMs = startedAtEpochMs;
            this.appVersion = bounded(appVersion, 80, "appVersion");
            this.commit = bounded(commit, 64, "commit");
            this.deviceModel = bounded(deviceModel, 100, "deviceModel");
            this.androidApi = androidApi;
            this.networkType = bounded(networkType, 24, "networkType");
            this.profile = bounded(profile, 32, "profile");
            this.side = bounded(side, 16, "side");
            if (startedAtEpochMs <= 0 || androidApi < 1)
                throw new IllegalArgumentException("Invalid diagnostic metadata");
        }

        public static Metadata create(long startedAtEpochMs, String appVersion, String commit,
                String deviceModel, int androidApi, String networkType, String profile, String side) {
            return createForFeature("lan_transfer", startedAtEpochMs, appVersion, commit,
                    deviceModel, androidApi, networkType, profile, side);
        }

        public static Metadata createForFeature(String featureId, long startedAtEpochMs,
                String appVersion, String commit, String deviceModel, int androidApi,
                String networkType, String profile, String side) {
            return new Metadata(featureId, UUID.randomUUID().toString(), startedAtEpochMs,
                    appVersion, commit, deviceModel, androidApi, networkType, profile, side);
        }
    }

    public static final class CaseResult {
        public final String caseId, failureCode;
        public final Status status;
        public final long durationMs;
        public final Map<String, Long> metrics;

        public CaseResult(String caseId, Status status, long durationMs, String failureCode,
                Map<String, Long> metrics) {
            this.caseId = bounded(caseId, 80, "caseId");
            if (status == null || durationMs < 0)
                throw new IllegalArgumentException("Invalid diagnostic case result");
            this.status = status;
            this.durationMs = durationMs;
            this.failureCode = failureCode == null || failureCode.isEmpty()
                    ? "" : bounded(failureCode, 80, "failureCode");
            if (status == Status.PASS && !this.failureCode.isEmpty())
                throw new IllegalArgumentException("Passing case cannot have a failure code");
            if (status == Status.FAIL && this.failureCode.isEmpty())
                throw new IllegalArgumentException("Failed case requires a stable failure code");
            LinkedHashMap<String, Long> copy = new LinkedHashMap<>();
            if (metrics != null) {
                if (metrics.size() > 32) throw new IllegalArgumentException("Too many diagnostic metrics");
                for (Map.Entry<String, Long> entry : metrics.entrySet()) {
                    String key = bounded(entry.getKey(), 48, "metric key");
                    if (entry.getValue() == null || entry.getValue() < 0)
                        throw new IllegalArgumentException("Diagnostic metrics must be non-negative integers");
                    copy.put(key, entry.getValue());
                }
            }
            this.metrics = Collections.unmodifiableMap(copy);
        }
    }

    public final Metadata metadata;
    public final List<CaseResult> cases;
    public final int expectedCaseCount;

    public DiagnosticRun(Metadata metadata, List<CaseResult> cases, int expectedCaseCount) {
        if (metadata == null || cases == null || cases.size() > 128
                || expectedCaseCount < 1 || expectedCaseCount > 128 || cases.size() > expectedCaseCount)
            throw new IllegalArgumentException("Invalid diagnostic run");
        this.metadata = metadata;
        this.expectedCaseCount = expectedCaseCount;
        ArrayList<CaseResult> copy = new ArrayList<>(cases.size());
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (CaseResult result : cases) {
            if (result == null || !ids.add(result.caseId))
                throw new IllegalArgumentException("Null or duplicate diagnostic case");
            copy.add(result);
        }
        this.cases = Collections.unmodifiableList(copy);
    }

    public int count(Status status) {
        int count = 0;
        for (CaseResult result : cases) if (result.status == status) count++;
        return count;
    }

    public boolean isCompleteAndPassing() {
        return cases.size() == expectedCaseCount && count(Status.FAIL) == 0
                && count(Status.SKIPPED) == 0 && expectedCaseCount > 0;
    }

    private static String bounded(String value, int max, String field) {
        if (value == null || value.trim().isEmpty() || value.length() > max)
            throw new IllegalArgumentException("Invalid " + field);
        return value.trim();
    }
}
