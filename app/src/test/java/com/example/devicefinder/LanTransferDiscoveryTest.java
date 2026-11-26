package com.example.devicefinder;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LanTransferDiscoveryTest {
    @Test
    public void firstBeaconIsAllowedAndRapidRefreshIsThrottled() {
        AtomicLong last = new AtomicLong(Long.MIN_VALUE);
        assertTrue(LanTransferDiscovery.claimBeaconSlot(last, 10_000L));
        assertFalse(LanTransferDiscovery.claimBeaconSlot(last, 10_000L + LanTransferDiscovery.MIN_BEACON_INTERVAL_MS - 1L));
        assertEquals(10_000L, last.get());
    }

    @Test
    public void beaconCanBeSentAtOneSecondBoundary() {
        AtomicLong last = new AtomicLong(20_000L);
        assertTrue(LanTransferDiscovery.claimBeaconSlot(last, 20_000L + LanTransferDiscovery.MIN_BEACON_INTERVAL_MS));
        assertEquals(21_000L, last.get());
    }

    @Test
    public void concurrentRefreshesClaimOnlyOneBeaconSlot() throws Exception {
        final int callers = 16;
        AtomicLong last = new AtomicLong(Long.MIN_VALUE);
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(callers);
        for (int i = 0; i < callers; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (LanTransferDiscovery.claimBeaconSlot(last, 30_000L))
                        accepted.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            worker.start();
        }
        ready.await();
        start.countDown();
        finished.await();
        assertEquals(1, accepted.get());
        assertEquals(30_000L, last.get());
    }
}
