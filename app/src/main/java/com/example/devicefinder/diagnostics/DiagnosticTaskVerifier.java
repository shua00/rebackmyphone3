package com.example.devicefinder.diagnostics;

import com.example.devicefinder.Rescue2AdminTrustStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Authenticates and binds a signed diagnostic task before replay checks or execution. */
public final class DiagnosticTaskVerifier {
    public static final class Result {
        public final boolean accepted;
        public final String errorCode;
        public final DiagnosticTaskEnvelope envelope;
        private Result(boolean ok, String code, DiagnosticTaskEnvelope task) {
            accepted = ok;
            errorCode = code;
            envelope = task;
        }
    }

    private DiagnosticTaskVerifier() {}

    public static Result verify(String encodedJson, byte[] signature,
            String localParticipantId, long nowEpochMs, Rescue2AdminTrustStore trustStore) {
        if (trustStore == null || localParticipantId == null || localParticipantId.isEmpty())
            return new Result(false, "INVALID_CONTEXT", null);
        final DiagnosticTaskEnvelope envelope;
        try {
            envelope = DiagnosticTaskEnvelope.fromJson(encodedJson);
        } catch (Exception malformed) {
            return new Result(false, "MALFORMED_TASK", null);
        }
        byte[] canonical = envelope.toJson().getBytes(StandardCharsets.UTF_8);
        byte[] supplied = encodedJson.getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(canonical, supplied))
            return new Result(false, "NON_CANONICAL_TASK", null);
        if (!envelope.isValidAt(nowEpochMs))
            return new Result(false, "TASK_EXPIRED", null);
        if (!envelope.targetParticipantIds.contains(localParticipantId))
            return new Result(false, "WRONG_TARGET", null);
        if (!trustStore.isTrusted(envelope.adminKeyFingerprint))
            return new Result(false, "UNTRUSTED_ADMIN", null);
        if (!trustStore.verify(envelope.adminKeyFingerprint, canonical, signature))
            return new Result(false, "INVALID_SIGNATURE", null);
        return new Result(true, "OK", envelope);
    }
}
