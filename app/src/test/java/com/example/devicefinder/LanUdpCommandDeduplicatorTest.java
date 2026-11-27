package com.example.devicefinder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanUdpCommandDeduplicatorTest {
    @Test public void sameCommandIsSuppressedAcrossSharedIngressPaths() {
        LanUdpCommandDeduplicator shared = new LanUdpCommandDeduplicator();
        assertTrue(shared.shouldAccept("FIND_ME_ALARM:secret:device", 1000L));
        // A second listener sees the same broadcast and uses the same service-scoped owner.
        assertFalse(shared.shouldAccept("FIND_ME_ALARM:secret:device", 1200L));
        assertEquals(1, shared.size());
    }

    @Test public void commandCanBeAcceptedAgainAfterDuplicateWindow() {
        LanUdpCommandDeduplicator dedup = new LanUdpCommandDeduplicator();
        assertTrue(dedup.shouldAccept("command-A", 1000L));
        assertFalse(dedup.shouldAccept("command-A", 3999L));
        assertTrue(dedup.shouldAccept("command-A", 4000L));
    }

    @Test public void invalidKeysAndNegativeClockValuesAreRejected() {
        LanUdpCommandDeduplicator dedup = new LanUdpCommandDeduplicator();
        assertFalse(dedup.shouldAccept(null, 1L));
        assertFalse(dedup.shouldAccept(" ", 1L));
        assertFalse(dedup.shouldAccept("valid", -1L));
        assertEquals(0, dedup.size());
    }

    @Test public void retainedEntriesAreBounded() {
        LanUdpCommandDeduplicator dedup = new LanUdpCommandDeduplicator();
        for (int i = 0; i < 100; i++) {
            assertTrue(dedup.shouldAccept("command-" + i, 1000L + i));
        }
        assertTrue(dedup.size() <= 50);
    }

    @Test public void concurrentIngressPathsAdmitOnlyOneCopy() throws Exception {
        final LanUdpCommandDeduplicator shared = new LanUdpCommandDeduplicator();
        final java.util.concurrent.CountDownLatch ready =
                new java.util.concurrent.CountDownLatch(12);
        final java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger accepted =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread[] workers = new Thread[12];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (shared.shouldAccept("same-broadcast", 5000L)) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "udp-dedup-test-" + i);
            workers[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread worker : workers) worker.join(2000L);
        assertEquals(1, accepted.get());
        assertEquals(1, shared.size());
    }
}
