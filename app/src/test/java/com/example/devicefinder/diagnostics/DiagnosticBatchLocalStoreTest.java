package com.example.devicefinder.diagnostics;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticBatchLocalStoreTest {
    private Context context;
    private DiagnosticBatchLocalStore store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("diagnostic_batch_spool.db");
        store = new DiagnosticBatchLocalStore(context);
    }

    @After public void tearDown() {
        if (store != null) store.close();
        context.deleteDatabase("diagnostic_batch_spool.db");
    }

    private DiagnosticBatchEnvelope event(long createdAt) {
        return DiagnosticBatchEnvelope.create("batch-store", "run-store", "participant-store",
                System.nanoTime(), createdAt, "{\"featureId\":\"diagnostics\",\"cases\":[]}");
    }

    @Test public void pendingOutboundIsNeverEvictedAndFullQueueFailsExplicitly() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < DiagnosticBatchLocalStore.MAX_PENDING_OUTBOUND; i++) {
            store.enqueue(event(now - DiagnosticBatchLocalStore.RETENTION_MS * 2));
        }
        try {
            store.enqueue(event(now));
            fail("full pending queue must reject a new report");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("queue is full"));
        }
        assertEquals(DiagnosticBatchLocalStore.MAX_PENDING_OUTBOUND,
                store.pending(DiagnosticBatchLocalStore.MAX_PENDING_OUTBOUND).size());
    }

    @Test public void inboxIsBoundedAndDuplicateEventsRemainIdempotent() {
        long now = System.currentTimeMillis();
        DiagnosticBatchEnvelope first = event(now);
        assertTrue(store.acceptIncoming(first));
        assertFalse(store.acceptIncoming(first));
        for (int i = 1; i < DiagnosticBatchLocalStore.MAX_INBOX_EVENTS + 1; i++) {
            store.acceptIncoming(event(now + i));
        }
        try (android.database.Cursor cursor = store.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM diagnostic_events WHERE direction='in'", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(DiagnosticBatchLocalStore.MAX_INBOX_EVENTS, cursor.getInt(0));
        }
    }

    @Test public void retentionDeletesOnlyInboxAndDeliveredOutbound() {
        long old = System.currentTimeMillis() - DiagnosticBatchLocalStore.RETENTION_MS - 1000;
        DiagnosticBatchEnvelope pending = event(old);
        DiagnosticBatchEnvelope delivered = event(old);
        DiagnosticBatchEnvelope inbox = event(old);
        store.enqueue(pending);
        store.enqueue(delivered);
        store.markDelivered(delivered.eventId);
        assertTrue(store.acceptIncoming(inbox));

        assertEquals(2, store.pruneExpiredReports());
        assertEquals(1, store.pending(10).size());
        assertEquals(0, store.received("batch-store", 10).size());
    }
}
