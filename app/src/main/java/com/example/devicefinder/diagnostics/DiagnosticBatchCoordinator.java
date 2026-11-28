package com.example.devicefinder.diagnostics;

import java.util.List;

/**
 * Optional multi-endpoint diagnostic relay. The Store must be durable; the Transport is an
 * independently supplied lightweight control channel and must not be implemented via file transfer.
 * Delivery is at-least-once; eventId provides receiver-side idempotency.
 */
public final class DiagnosticBatchCoordinator {
    public interface Store {
        /** Atomically persist before any network attempt. */
        void enqueue(DiagnosticBatchEnvelope envelope);
        List<DiagnosticBatchEnvelope> pending(int limit);
        void markDelivered(String eventId);
        /** Atomically insert if eventId has not been seen; false means duplicate. */
        boolean acceptIncoming(DiagnosticBatchEnvelope envelope);
        List<DiagnosticBatchEnvelope> received(String batchId, int limit);
    }

    public interface Transport {
        /** Non-blocking/bounded send over an independent diagnostics control channel. */
        void send(DiagnosticBatchEnvelope envelope) throws Exception;
    }

    public interface Membership {
        boolean isExpectedParticipant(String batchId, String participantId);
    }

    public interface Clock {
        long nowEpochMs();
    }

    public static final class ReceiveResult {
        public enum Status { ACCEPTED, DUPLICATE, UNEXPECTED_PARTICIPANT, WRONG_BATCH }
        public final Status status;
        private ReceiveResult(Status status) { this.status = status; }
    }

    private final Store store;
    private final Transport transport;
    private final Membership membership;
    private final Clock clock;
    private final String batchId;

    public DiagnosticBatchCoordinator(String batchId, Store store, Transport transport,
            Membership membership, Clock clock) {
        if (batchId == null || batchId.trim().isEmpty() || store == null
                || membership == null || clock == null)
            throw new IllegalArgumentException("Batch id, store, membership and clock are required");
        this.batchId = batchId;
        this.store = store;
        this.transport = transport;
        this.membership = membership;
        this.clock = clock;
    }

    /** Persists locally first. If the channel is unavailable, the report remains queued. */
    public void submit(DiagnosticBatchEnvelope envelope) {
        if (envelope == null || !batchId.equals(envelope.batchId))
            throw new IllegalArgumentException("Envelope belongs to another batch");
        store.enqueue(envelope);
        flush(1);
    }

    /** Retries a bounded number of locally queued events; transport failure never loses data. */
    public int flush(int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid flush limit");
        if (transport == null) return 0;
        int delivered = 0;
        List<DiagnosticBatchEnvelope> pending = store.pending(limit);
        if (pending == null) return 0;
        for (DiagnosticBatchEnvelope envelope : pending) {
            if (envelope == null || !batchId.equals(envelope.batchId)) continue;
            try {
                transport.send(envelope);
                store.markDelivered(envelope.eventId);
                delivered++;
            } catch (Exception unavailable) {
                // Keep this and later events queued; retry on explicit reconnect/backoff trigger.
                break;
            }
        }
        return delivered;
    }

    /** Validates batch membership and deduplicates before exposing data to the aggregator. */
    public ReceiveResult receive(DiagnosticBatchEnvelope envelope) {
        if (envelope == null || !batchId.equals(envelope.batchId))
            return new ReceiveResult(ReceiveResult.Status.WRONG_BATCH);
        if (!membership.isExpectedParticipant(batchId, envelope.participantId))
            return new ReceiveResult(ReceiveResult.Status.UNEXPECTED_PARTICIPANT);
        if (envelope.createdAtEpochMs > clock.nowEpochMs() + 5 * 60 * 1000L)
            return new ReceiveResult(ReceiveResult.Status.UNEXPECTED_PARTICIPANT);
        boolean inserted = store.acceptIncoming(envelope);
        return new ReceiveResult(inserted ? ReceiveResult.Status.ACCEPTED
                : ReceiveResult.Status.DUPLICATE);
    }

    public List<DiagnosticBatchEnvelope> getReceivedReports(int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid report limit");
        return store.received(batchId, limit);
    }
}
