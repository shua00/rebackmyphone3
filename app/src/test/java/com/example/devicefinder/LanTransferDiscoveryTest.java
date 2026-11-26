package com.example.devicefinder;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LanTransferDiscoveryTest {
    @Test public void firstBeaconIsAllowedAndRapidRefreshIsThrottled() {
        AtomicLong last=new AtomicLong(Long.MIN_VALUE);
        assertTrue(LanTransferDiscovery.claimBeaconSlot(last,10_000L));
        assertFalse(LanTransferDiscovery.claimBeaconSlot(last,10_000L+LanTransferDiscovery.MIN_BEACON_INTERVAL_MS-1L));
        assertEquals(10_000L,last.get());
    }
    @Test public void beaconCanBeSentAtOneSecondBoundary() {
        AtomicLong last=new AtomicLong(20_000L);
        assertTrue(LanTransferDiscovery.claimBeaconSlot(last,20_000L+LanTransferDiscovery.MIN_BEACON_INTERVAL_MS));
        assertEquals(21_000L,last.get());
    }
    @Test public void concurrentBeaconClaimsAreSerialized() throws Exception {
        AtomicLong last=new AtomicLong(Long.MIN_VALUE); AtomicInteger accepted=new AtomicInteger();
        CountDownLatch ready=new CountDownLatch(8), start=new CountDownLatch(1);
        Thread[] workers=new Thread[8];
        for(int i=0;i<workers.length;i++){ workers[i]=new Thread(()->{
            ready.countDown(); try { start.await(); if(LanTransferDiscovery.claimBeaconSlot(last,30_000L)) accepted.incrementAndGet(); }
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        }); workers[i].start(); }
        ready.await(); start.countDown(); for(Thread worker:workers)worker.join();
        assertEquals(1,accepted.get());
    }
    @Test public void parsesCurrentBeaconWithBothPorts() {
        LanTransferDiscovery.Beacon b=LanTransferDiscovery.parseBeacon("LTFD1|peer-B|38471|38473","peer-A");
        assertNotNull(b); assertEquals("peer-B",b.peerId); assertEquals(38471,b.filePort); assertEquals(38473,b.pairingPort);
    }
    @Test public void parsesLegacyBeaconWithDefaultPairingPort() {
        LanTransferDiscovery.Beacon b=LanTransferDiscovery.parseBeacon("LTFD1|peer-B|38471","peer-A");
        assertNotNull(b); assertEquals(LanTransferPairingControlProtocol.DEFAULT_PORT,b.pairingPort);
    }
    @Test public void rejectsMalformedOrUnsafeBeaconFields() {
        assertNull(LanTransferDiscovery.parseBeacon(null,"peer-A"));
        assertNull(LanTransferDiscovery.parseBeacon("LTFD1|peer-B|abc|38473","peer-A"));
        assertNull(LanTransferDiscovery.parseBeacon("LTFD1|peer-B|65536|38473","peer-A"));
        assertNull(LanTransferDiscovery.parseBeacon("LTFD1|peer-B|38471|0","peer-A"));
        assertNull(LanTransferDiscovery.parseBeacon("LTFD1|bad peer|38471|38473","peer-A"));
        assertNull(LanTransferDiscovery.parseBeacon("LTFD1|peer-A|38471|38473","peer-A"));
        assertNull(LanTransferDiscovery.parseBeacon("OTHER|peer-B|38471|38473","peer-A"));
        assertNull(LanTransferDiscovery.parseBeacon("LTFD1|peer-B|38471|38473|extra","peer-A"));
    }
}