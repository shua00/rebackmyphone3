package com.example.devicefinder;

import android.content.Context;
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
}
