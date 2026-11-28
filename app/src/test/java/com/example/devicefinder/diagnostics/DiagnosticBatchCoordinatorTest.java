package com.example.devicefinder.diagnostics;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticBatchCoordinatorTest {
    private static final class MemoryStore implements DiagnosticBatchCoordinator.Store {
        final List<DiagnosticBatchEnvelope> out = new ArrayList<>();
        final List<DiagnosticBatchEnvelope> in = new ArrayList<>();
        final Set<String> delivered = new HashSet<>();
        @Override public void enqueue(DiagnosticBatchEnvelope e) { out.add(e); }
        @Override public List<DiagnosticBatchEnvelope> pending(int n) {
            List<DiagnosticBatchEnvelope> result = new ArrayList<>();
            for (DiagnosticBatchEnvelope e : out) if (!delivered.contains(e.eventId) && result.size() < n) result.add(e);
            return result;
        }
        @Override public void markDelivered(String id) { delivered.add(id); }
        @Override public boolean acceptIncoming(DiagnosticBatchEnvelope e) {
            for (DiagnosticBatchEnvelope old : in) if (old.eventId.equals(e.eventId)) return false;
            in.add(e); return true;
        }
        @Override public List<DiagnosticBatchEnvelope> received(String batch, int n) {
            List<DiagnosticBatchEnvelope> result = new ArrayList<>();
            for (DiagnosticBatchEnvelope e : in) if (batch.equals(e.batchId) && result.size() < n) result.add(e);
            return result;
        }
    }

    private DiagnosticBatchEnvelope envelope(String participant) {
        return DiagnosticBatchEnvelope.create("batch-1", "run-1", participant, 1, 1000,
                "{\"featureId\":\"lan\",\"cases\":[]}");
    }

    @Test public void reportIsPersistedBeforeUnavailableTransportAndCanBeRetried() {
        MemoryStore store = new MemoryStore();
        final boolean[] online = {false};
        DiagnosticBatchCoordinator.Transport transport = e -> {
            if (!online[0]) throw new java.io.IOException("offline");
        };
        DiagnosticBatchCoordinator coordinator = new DiagnosticBatchCoordinator(
                "batch-1", store, transport, (b, p) -> true, () -> 2000);
        DiagnosticBatchEnvelope event = envelope("participant-a");
        coordinator.submit(event);
        assertEquals(1, store.pending(10).size());
        online[0] = true;
        assertEquals(1, coordinator.flush(10));
        assertTrue(store.pending(10).isEmpty());
    }

    @Test public void receiveRejectsUnexpectedParticipantAndWrongBatch() {
        MemoryStore store = new MemoryStore();
        DiagnosticBatchCoordinator coordinator = new DiagnosticBatchCoordinator(
                "batch-1", store, null, (b, p) -> "participant-a".equals(p), () -> 2000);
        assertEquals(DiagnosticBatchCoordinator.ReceiveResult.Status.UNEXPECTED_PARTICIPANT,
                coordinator.receive(envelope("participant-b")).status);
        DiagnosticBatchEnvelope wrong = DiagnosticBatchEnvelope.create(
                "batch-2", "run-1", "participant-a", 1, 1000, "{}");
        assertEquals(DiagnosticBatchCoordinator.ReceiveResult.Status.WRONG_BATCH,
                coordinator.receive(wrong).status);
        assertTrue(store.in.isEmpty());
    }

    @Test public void incomingEventsAreIdempotentAndReportsRemainBatchScoped() {
        MemoryStore store = new MemoryStore();
        DiagnosticBatchCoordinator coordinator = new DiagnosticBatchCoordinator(
                "batch-1", store, null, (b, p) -> true, () -> 2000);
        DiagnosticBatchEnvelope event = envelope("participant-a");
        assertEquals(DiagnosticBatchCoordinator.ReceiveResult.Status.ACCEPTED,
                coordinator.receive(event).status);
        assertEquals(DiagnosticBatchCoordinator.ReceiveResult.Status.DUPLICATE,
                coordinator.receive(event).status);
        assertEquals(1, coordinator.getReceivedReports(10).size());
    }

    @Test public void envelopeRoundTripsAndRejectsOversizedPayload() throws Exception {
        DiagnosticBatchEnvelope original = envelope("participant-a");
        DiagnosticBatchEnvelope decoded = DiagnosticBatchEnvelope.fromJson(original.toJson());
        assertEquals(original.eventId, decoded.eventId);
        assertEquals(original.reportJson, decoded.reportJson);
        try {
            new DiagnosticBatchEnvelope("b", "e", "r", "p", 0, 1,
                    new String(new char[DiagnosticBatchEnvelope.MAX_REPORT_JSON_CHARS + 1]).replace((char) 0, 'x'));
            fail("oversized payload accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
