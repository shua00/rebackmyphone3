package com.example.devicefinder;

/**
 * Immutable per-session communication profile for file transfer.
 *
 * Security invariants are deliberately not configurable: every mode requires authenticated
 * peers, encrypted transport, integrity verification, and replay protection. Modes only tune
 * performance behavior. A profile is resolved once when a session starts and must not be
 * mutated while that session is active.
 */
public final class LanTransferCommunicationMode {
    public enum Mode { SECURE, HIGH_PERFORMANCE, AUTO }
    public enum Traffic { CHAT, VOICE, FILE }
    public enum LinkQuality { UNKNOWN, GOOD, CONSTRAINED, POOR }

    public static final int POLICY_VERSION = 1;

    public final Mode requestedMode;
    public final Mode effectiveMode;
    public final Traffic traffic;
    public final LinkQuality linkQuality;
    public final int policyVersion;
    public final int maxInFlightMessages;
    public final int maxInFlightBytes;
    public final int ackBatchSize;
    public final int ackDelayMs;
    public final int connectTimeoutMs;
    public final int readTimeoutMs;
    public final boolean authenticatedPeerRequired;
    public final boolean encryptionRequired;
    public final boolean integrityRequired;
    public final boolean replayProtectionRequired;

    private LanTransferCommunicationMode(Mode requestedMode, Mode effectiveMode,
            Traffic traffic, LinkQuality linkQuality, int maxInFlightMessages,
            int maxInFlightBytes, int ackBatchSize, int ackDelayMs,
            int connectTimeoutMs, int readTimeoutMs) {
        this.requestedMode = requestedMode;
        this.effectiveMode = effectiveMode;
        this.traffic = traffic;
        this.linkQuality = linkQuality;
        this.policyVersion = POLICY_VERSION;
        this.maxInFlightMessages = maxInFlightMessages;
        this.maxInFlightBytes = maxInFlightBytes;
        this.ackBatchSize = ackBatchSize;
        this.ackDelayMs = ackDelayMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.authenticatedPeerRequired = true;
        this.encryptionRequired = true;
        this.integrityRequired = true;
        this.replayProtectionRequired = true;
    }

    /** Resolve a new immutable session snapshot. Never call this to mutate an active session. */
    public static LanTransferCommunicationMode resolve(Mode requested, Traffic traffic,
            LinkQuality quality) {
        if (requested == null || traffic == null || quality == null)
            throw new IllegalArgumentException("Mode, traffic type, and link quality are required");

        Mode effective = requested;
        if (requested == Mode.AUTO) {
            // AUTO chooses throughput/latency tuning only; security requirements never change.
            effective = (traffic == Traffic.FILE && quality == LinkQuality.GOOD)
                    ? Mode.HIGH_PERFORMANCE : Mode.SECURE;
        }

        if (effective == Mode.SECURE) {
            if (traffic == Traffic.CHAT || traffic == Traffic.VOICE) {
                return new LanTransferCommunicationMode(requested, effective, traffic, quality,
                        1, 64 * 1024, 1, 0, 5000, 10000);
            }
            return new LanTransferCommunicationMode(requested, effective, traffic, quality,
                    2, 256 * 1024, 1, 0, 5000, 15000);
        }

        // High-performance profile: larger bounded windows and ACK coalescing. No security
        // primitive is disabled; values remain conservative until measured on real devices.
        if (traffic == Traffic.CHAT || traffic == Traffic.VOICE) {
            return new LanTransferCommunicationMode(requested, effective, traffic, quality,
                    2, 128 * 1024, 2, 2, 5000, 10000);
        }
        int messages = quality == LinkQuality.GOOD ? 8 : 4;
        int bytes = quality == LinkQuality.GOOD ? 1024 * 1024 : 512 * 1024;
        return new LanTransferCommunicationMode(requested, effective, traffic, quality,
                messages, bytes, 8, 10, 5000, 20000);
    }

    public boolean hasSecurityBaseline() {
        return authenticatedPeerRequired && encryptionRequired
                && integrityRequired && replayProtectionRequired;
    }
}
