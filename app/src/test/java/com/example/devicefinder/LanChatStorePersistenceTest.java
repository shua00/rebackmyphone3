package com.example.devicefinder;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanChatStorePersistenceTest {
    private static final String DB_NAME = "lan_p2p_delivery.db";
    private Context context;
    private LanChatStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DB_NAME);
        store = new LanChatStore(context);
    }

    @After
    public void tearDown() {
        if (store != null) {
            store.closeStore();
            store = null;
        }
        context.deleteDatabase(DB_NAME);
    }

    @Test
    public void knownOfflinePeersWithoutCurrentRoutesAreFrozenAndNeverExpandedAfterRestart() {
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("INSERT INTO lan_peers(peer_id,endpoint_ip,endpoint_port,last_seen_at,status) " +
                "VALUES(?,?,?,?,?)",
                new Object[]{"android-offline-route", "192.0.2.30", 8888, 100L, "offline"});
        db.execSQL("INSERT INTO lan_peers(peer_id,endpoint_ip,endpoint_port,last_seen_at,status) " +
                "VALUES(?,?,?,?,?)",
                new Object[]{"android-offline-no-route", "", 0, 90L, "offline"});

        assertTrue(store.enqueueWithRecipientSnapshot(
                "android-offline-fixed", "desktop-peer", "offline recipient body",
                1234L, Collections.emptyMap()));
        List<LanChatStore.Target> initial = store.loadTargets("android-offline-fixed");
        assertEquals(2, initial.size());
        assertEquals("android-offline-no-route", initial.get(0).peerId);
        assertEquals("", initial.get(0).endpointIp);
        assertEquals("android-offline-route", initial.get(1).peerId);
        assertEquals("192.0.2.30", initial.get(1).endpointIp);

        store.closeStore();
        store = new LanChatStore(context);
        db = store.getWritableDatabase();
        db.execSQL("INSERT INTO lan_peers(peer_id,endpoint_ip,endpoint_port,last_seen_at,status) " +
                "VALUES(?,?,?,?,?)",
                new Object[]{"android-late-joiner", "192.0.2.31", 8888, 200L, "active"});

        List<LanChatStore.Target> afterRestart = store.loadTargets("android-offline-fixed");
        assertEquals(2, afterRestart.size());
        assertEquals("android-offline-no-route", afterRestart.get(0).peerId);
        assertEquals("android-offline-route", afterRestart.get(1).peerId);
        assertFalse(store.acknowledge("android-offline-fixed", "android-late-joiner"));
    }

    @Test
    public void recentDeliverySummaryReflectsPartialAndCompleteDurableAcks() {
        Map<String, InetSocketAddress> routes = new java.util.LinkedHashMap<>();
        routes.put("android-a", new InetSocketAddress("192.0.2.10", 8888));
        routes.put("android-b", new InetSocketAddress("192.0.2.11", 8888));
        assertTrue(store.enqueueWithRecipientSnapshot(
                "android-summary", "desktop-peer", "summary body", 1234L, routes));

        List<LanChatStore.DeliverySummary> summary = store.loadRecentDeliverySummaries(3);
        assertEquals(1, summary.size());
        assertEquals(2, summary.get(0).totalTargets);
        assertEquals(0, summary.get(0).ackedTargets);
        assertEquals("pending", summary.get(0).state);

        assertTrue(store.acknowledge("android-summary", "android-a"));
        summary = store.loadRecentDeliverySummaries(3);
        assertEquals(1, summary.get(0).ackedTargets);
        assertEquals(2, summary.get(0).totalTargets);
        assertEquals("pending", summary.get(0).state);

        assertTrue(store.acknowledge("android-summary", "android-b"));
        summary = store.loadRecentDeliverySummaries(3);
        assertEquals(2, summary.get(0).ackedTargets);
        assertEquals("delivered", summary.get(0).state);
    }

    @Test
    public void fixedRecipientOutboxAndAckSurviveCloseAndReopen() {
        Map<String, InetSocketAddress> routes = Collections.singletonMap(
                "android-a", new InetSocketAddress("192.0.2.10", 8888));
        assertTrue(store.enqueueWithRecipientSnapshot(
                "android-restart-outbox", "desktop-peer", "durable body", 1234L, routes));
        List<LanChatStore.Target> initial = store.loadTargets("android-restart-outbox");
        assertEquals(1, initial.size());
        assertEquals("android-a", initial.get(0).peerId);

        assertTrue(store.markAttempt(
                "android-restart-outbox", "android-a", "192.0.2.10:8888", ""));
        assertTrue(store.recordSuccessfulSendEndpoint(
                "android-restart-outbox", "android-a", "192.0.2.10:8888"));
        store.closeStore();

        store = new LanChatStore(context);
        List<LanChatStore.PendingMessage> pending = store.loadPending(10);
        assertEquals(1, pending.size());
        assertEquals("android-restart-outbox", pending.get(0).messageId);
        assertEquals("durable body", pending.get(0).body);
        assertTrue(store.matchesExpectedAckEndpoint(
                "android-restart-outbox", "android-a", "192.0.2.10", 8888));
        assertTrue(store.acknowledge("android-restart-outbox", "android-a"));
        assertTrue(store.acknowledge("android-restart-outbox", "android-a"));
        store.closeStore();

        store = new LanChatStore(context);
        assertTrue(store.allTargetsAcked("android-restart-outbox"));
        assertEquals(0, store.loadPending(10).size());
        LanChatStore.AckProgress progress = store.getTargetAckProgress("android-restart-outbox");
        assertNotNull(progress);
        assertEquals(1, progress.totalTargets);
        assertEquals(1, progress.ackedTargets);
    }

    @Test
    public void inboxCommitAndDisplayMarkerSurviveCloseAndReopen() {
        assertTrue(store.storeIncoming(
                "android-main", "desktop-peer", "android-inbox-restart",
                "persist before ACK", "Desktop", 1234L, "12:34:56"));
        assertFalse(store.isIncomingDisplayed(
                "android-main", "desktop-peer", "android-inbox-restart"));
        store.closeStore();

        store = new LanChatStore(context);
        List<LanChatStore.IncomingMessage> undisplayed =
                store.loadUndisplayedIncoming("android-main", 20);
        assertEquals(1, undisplayed.size());
        assertEquals("persist before ACK", undisplayed.get(0).body);
        assertTrue(store.storeIncoming(
                "android-main", "desktop-peer", "android-inbox-restart",
                "persist before ACK", "Desktop", 1234L, "12:34:56"));
        assertFalse(store.storeIncoming(
                "android-main", "desktop-peer", "android-inbox-restart",
                "tampered duplicate", "Desktop", 1234L, "12:34:56"));
        assertTrue(store.markIncomingDisplayed(
                "android-main", "desktop-peer", "android-inbox-restart"));
        store.closeStore();

        store = new LanChatStore(context);
        assertTrue(store.isIncomingDisplayed(
                "android-main", "desktop-peer", "android-inbox-restart"));
        assertEquals(0, store.loadUndisplayedIncoming("android-main", 20).size());
    }

    @Test
    public void versionEightTargetLedgerMigratesAttemptEndpointWithoutLosingSentEndpoint() {
        SQLiteDatabase legacy = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        legacy.execSQL("CREATE TABLE lan_outbox_targets (" +
                "message_id TEXT NOT NULL, peer_id TEXT NOT NULL, " +
                "status TEXT NOT NULL DEFAULT 'pending', attempt_count INTEGER NOT NULL DEFAULT 0, " +
                "last_attempt_at INTEGER NOT NULL DEFAULT 0, last_ack_at INTEGER NOT NULL DEFAULT 0, " +
                "last_endpoint TEXT NOT NULL DEFAULT '', last_sent_endpoint TEXT NOT NULL DEFAULT '', " +
                "last_error TEXT NOT NULL DEFAULT '', last_recovery_at_ms INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(message_id,peer_id))");
        legacy.execSQL("INSERT INTO lan_outbox_targets " +
                "(message_id,peer_id,last_endpoint,last_sent_endpoint) VALUES(?,?,?,?)",
                new Object[]{"legacy-message", "android-a", "192.0.2.10:8888", "192.0.2.10:8888"});
        legacy.setVersion(8);
        legacy.close();

        store = new LanChatStore(context);
        SQLiteDatabase migrated = store.getReadableDatabase();
        boolean hasAttemptEndpoint = false;
        Cursor columns = migrated.rawQuery("PRAGMA table_info(lan_outbox_targets)", null);
        try {
            int nameColumn = columns.getColumnIndex("name");
            while (columns.moveToNext()) {
                if ("last_attempt_endpoint".equals(columns.getString(nameColumn))) {
                    hasAttemptEndpoint = true;
                    break;
                }
            }
        } finally {
            columns.close();
        }
        assertTrue(hasAttemptEndpoint);

        Cursor row = migrated.rawQuery(
                "SELECT last_sent_endpoint,last_attempt_endpoint FROM lan_outbox_targets " +
                        "WHERE message_id=? AND peer_id=?",
                new String[]{"legacy-message", "android-a"});
        try {
            assertTrue(row.moveToFirst());
            assertEquals("192.0.2.10:8888", row.getString(0));
            assertEquals("", row.getString(1));
        } finally {
            row.close();
        }
    }

    @Test
    public void sqliteWriteFailuresRollbackAndRemainRetryable() {
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("CREATE TRIGGER reject_inbox_insert BEFORE INSERT ON lan_inbox " +
                "BEGIN SELECT RAISE(ABORT, 'injected inbox write failure'); END");
        assertFalse(store.storeIncoming(
                "android-main", "desktop-peer", "android-inbox-failure",
                "must not ACK before durable commit", "Desktop", 1234L, "12:34:56"));
        assertEquals(0, store.loadUndisplayedIncoming("android-main", 20).size());
        db.execSQL("DROP TRIGGER reject_inbox_insert");
        assertTrue(store.storeIncoming(
                "android-main", "desktop-peer", "android-inbox-failure",
                "must not ACK before durable commit", "Desktop", 1234L, "12:34:56"));

        Map<String, InetSocketAddress> routes = Collections.singletonMap(
                "android-a", new InetSocketAddress("192.0.2.10", 8888));
        assertTrue(store.enqueueWithRecipientSnapshot(
                "android-write-failure", "desktop-peer", "retry after rollback", 1234L, routes));
        db.execSQL("CREATE TRIGGER reject_target_update BEFORE UPDATE ON lan_outbox_targets " +
                "BEGIN SELECT RAISE(ABORT, 'injected target write failure'); END");
        assertFalse(store.markAttempt(
                "android-write-failure", "android-a", "192.0.2.10:8888", ""));
        assertEquals(0, store.loadTargets("android-write-failure").get(0).attemptCount);
        db.execSQL("DROP TRIGGER reject_target_update");

        assertTrue(store.markAttempt(
                "android-write-failure", "android-a", "192.0.2.10:8888", ""));
        db.execSQL("CREATE TRIGGER reject_ack_update BEFORE UPDATE OF status ON lan_outbox_targets " +
                "BEGIN SELECT RAISE(ABORT, 'injected ACK write failure'); END");
        assertFalse(store.acknowledge("android-write-failure", "android-a"));
        assertFalse(store.allTargetsAcked("android-write-failure"));
        db.execSQL("DROP TRIGGER reject_ack_update");

        assertTrue(store.acknowledge("android-write-failure", "android-a"));
        assertTrue(store.allTargetsAcked("android-write-failure"));
    }

    @Test
    public void concurrentIdenticalInboxDeliveriesCommitExactlyOneDurableRow() throws Exception {
        final int workers = 6;
        final int deliveries = 12;
        final CountDownLatch ready = new CountDownLatch(workers);
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] results = new Future[deliveries];
            for (int i = 0; i < deliveries; i++) {
                results[i] = pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) return false;
                    return store.storeIncoming(
                            "android-main", "desktop-peer", "android-inbox-concurrent",
                            "same authenticated payload", "Desktop", 1234L, "12:34:56");
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Boolean> result : results) {
                assertTrue(result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        List<LanChatStore.IncomingMessage> pending =
                store.loadUndisplayedIncoming("android-main", 20);
        assertEquals(1, pending.size());
        assertEquals("android-inbox-concurrent", pending.get(0).messageId);
        assertFalse(store.storeIncoming(
                "android-main", "desktop-peer", "android-inbox-concurrent",
                "different payload under reused message ID", "Desktop", 1234L, "12:34:56"));
        assertEquals(1, store.loadUndisplayedIncoming("android-main", 20).size());
    }

    @Test
    public void acknowledgementsCannotInventFixedRecipientsOrJoinClosedDiscovery() {
        assertTrue(store.enqueue(
                "android-ack-discovery", "desktop-peer", "discovery body", 1234L));
        assertFalse(store.acknowledge("android-ack-discovery", "android-late"));
        assertEquals(0, store.loadTargets("android-ack-discovery").size());

        assertEquals(LanChatStore.DISCOVERY_WINDOW_STARTED,
                store.beginOrReadDiscoveryWindow("android-ack-discovery"));
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id=?",
                new Object[]{"android-ack-discovery"});
        assertTrue(store.finalizeDiscoveryWindow("android-ack-discovery"));
        assertFalse(store.acknowledge("android-ack-discovery", "android-late"));
        assertEquals(0, store.loadTargets("android-ack-discovery").size());

        Map<String, InetSocketAddress> routes = Collections.singletonMap(
                "android-fixed", new InetSocketAddress("192.0.2.12", 8888));
        assertTrue(store.enqueueWithRecipientSnapshot(
                "android-ack-fixed", "desktop-peer", "fixed recipients", 1234L, routes));
        assertFalse(store.acknowledge("android-ack-fixed", "android-late"));
        List<LanChatStore.Target> fixed = store.loadTargets("android-ack-fixed");
        assertEquals(1, fixed.size());
        assertEquals("android-fixed", fixed.get(0).peerId);
        assertTrue(store.acknowledge("android-ack-fixed", "android-fixed"));
        assertTrue(store.acknowledge("android-ack-fixed", "android-fixed"));
        assertTrue(store.allTargetsAcked("android-ack-fixed"));
    }

    @Test
    public void expiredDiscoveryWindowRemainsFrozenAfterCloseAndReopen() {
        assertTrue(store.enqueue(
                "android-restart-discovery", "desktop-peer", "discovery body", 1234L));
        assertEquals(LanChatStore.DISCOVERY_WINDOW_STARTED,
                store.beginOrReadDiscoveryWindow("android-restart-discovery"));
        store.closeStore();

        store = new LanChatStore(context);
        assertEquals(LanChatStore.DISCOVERY_WINDOW_OPEN,
                store.beginOrReadDiscoveryWindow("android-restart-discovery"));
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id=?",
                new Object[]{"android-restart-discovery"});
        store.closeStore();

        store = new LanChatStore(context);
        assertTrue(store.finalizeDiscoveryWindow("android-restart-discovery"));
        assertTrue(store.isWaitingForPeers("android-restart-discovery"));
        assertTrue(store.addDiscoveryPeerToOpenMessages("android-late"));
        assertEquals(0, store.loadTargets("android-restart-discovery").size());
        store.closeStore();

        store = new LanChatStore(context);
        assertTrue(store.isWaitingForPeers("android-restart-discovery"));
        assertEquals(0, store.loadTargets("android-restart-discovery").size());
    }
    @Test
    public void broadcastFragmentAckBitmapsArePeerScopedIdempotentAndDurable() {
        assertTrue(store.enqueue(
                "broadcast-fragment-ledger", "desktop-peer", "x".repeat(3000), 1234L));
        assertEquals(LanChatStore.DISCOVERY_WINDOW_STARTED,
                store.beginOrReadDiscoveryWindow("broadcast-fragment-ledger"));

        // The first authenticated ACK from each broadcast recipient creates only a
        // message-scoped target and records that peer's own first-flight bitmap.
        assertTrue(store.admitBroadcastFragmentAckTarget(
                "broadcast-fragment-ledger", "android-a", "192.0.2.10", 8888, 7));
        assertTrue(store.admitBroadcastFragmentAckTarget(
                "broadcast-fragment-ledger", "android-b", "192.0.2.11", 8888, 0));
        assertFalse(store.admitBroadcastFragmentAckTarget(
                "broadcast-fragment-ledger", "android-c", "192.0.2.12", 8888, 8));
        assertFalse(store.acknowledgeTargetFragment(
                "broadcast-fragment-ledger", "android-unknown", 0));

        assertEquals("0000000100",
                store.loadTargetFragmentAckBitmap("broadcast-fragment-ledger", "android-a"));
        assertEquals("1000000000",
                store.loadTargetFragmentAckBitmap("broadcast-fragment-ledger", "android-b"));

        // Out-of-order and duplicate ACKs update only the matching peer's bitmap.
        assertTrue(store.acknowledgeTargetFragment(
                "broadcast-fragment-ledger", "android-a", 2));
        assertTrue(store.acknowledgeTargetFragment(
                "broadcast-fragment-ledger", "android-a", 2));
        assertTrue(store.acknowledgeTargetFragment(
                "broadcast-fragment-ledger", "android-b", 1));
        assertEquals("0010000100",
                store.loadTargetFragmentAckBitmap("broadcast-fragment-ledger", "android-a"));
        assertEquals("1100000000",
                store.loadTargetFragmentAckBitmap("broadcast-fragment-ledger", "android-b"));

        // Fragment ACKs are not final delivery ACKs and must not create trusted peers.
        assertFalse(store.allTargetsAcked("broadcast-fragment-ledger"));
        Cursor trusted = store.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM lan_peers WHERE peer_id IN (?,?)",
                new String[]{"android-a", "android-b"});
        try {
            assertTrue(trusted.moveToFirst());
            assertEquals(0, trusted.getInt(0));
        } finally {
            trusted.close();
        }

        store.closeStore();
        store = new LanChatStore(context);
        assertEquals("0010000100",
                store.loadTargetFragmentAckBitmap("broadcast-fragment-ledger", "android-a"));
        assertEquals("1100000000",
                store.loadTargetFragmentAckBitmap("broadcast-fragment-ledger", "android-b"));
        assertFalse(store.allTargetsAcked("broadcast-fragment-ledger"));
    }

    @Test
    public void broadcastFragmentAckAdmissionIsBoundedAndCannotReopenFrozenMessages() {
        assertTrue(store.enqueue(
                "broadcast-fragment-bounds", "desktop-peer", "x".repeat(3000), 1234L));
        assertEquals(LanChatStore.DISCOVERY_WINDOW_STARTED,
                store.beginOrReadDiscoveryWindow("broadcast-fragment-bounds"));
        assertFalse(store.admitBroadcastFragmentAckTarget(
                "broadcast-fragment-bounds", "android-a", "192.0.2.10", 8888, -1));
        assertFalse(store.admitBroadcastFragmentAckTarget(
                "broadcast-fragment-bounds", "android-a", "192.0.2.10", 8888, 8));
        assertFalse(store.admitBroadcastFragmentAckTarget(
                "broadcast-fragment-bounds", "android-a", "192.0.2.10", 70000, 0));

        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id=?",
                new Object[]{"broadcast-fragment-bounds"});
        assertTrue(store.finalizeDiscoveryWindow("broadcast-fragment-bounds"));
        assertFalse(store.admitBroadcastFragmentAckTarget(
                "broadcast-fragment-bounds", "android-late", "192.0.2.13", 8888, 0));
        assertEquals(0, store.loadTargets("broadcast-fragment-bounds").size());
    }

}
