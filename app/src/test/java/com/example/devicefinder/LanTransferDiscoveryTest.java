package com.example.devicefinder;

import static org.junit.Assert.*;
import android.content.Context;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanTransferDiscoveryTest {
    @Test public void loopbackUdpDatagramFlowsThroughPacketParserIntoPeerDirectory() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        LanTransferPeerDirectory.clear(context);
        LanTransferPeerDirectory.deactivateNetwork(context);
        String fingerprint = "wifi:udp-integration";
        try {
            LanTransferPeerDirectory.activateNetwork(context, fingerprint);
            byte[] payload = "LTFD1|peer-remote|38471|38473".getBytes(StandardCharsets.US_ASCII);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            try (DatagramSocket receiver = new DatagramSocket(0, loopback);
                 DatagramSocket sender = new DatagramSocket()) {
                sender.send(new DatagramPacket(payload, payload.length, loopback, receiver.getLocalPort()));
                byte[] buffer = new byte[256];
                DatagramPacket received = new DatagramPacket(buffer, buffer.length);
                receiver.setSoTimeout(1500);
                receiver.receive(received);
                assertTrue(LanTransferDiscovery.recordBeaconCandidate(context, received,
                        "peer-local", fingerprint, System.currentTimeMillis()));
            }
            LanTransferPeerDirectory.Candidate candidate =
                    LanTransferPeerDirectory.findCurrent(context, "peer-remote", System.currentTimeMillis());
            assertNotNull(candidate);
            assertEquals("127.0.0.1", candidate.host);
            assertEquals(38471, candidate.port);
            assertEquals(38473, candidate.pairingPort);
            assertEquals(LanTransferPeerDirectory.SOURCE_FILE_DISCOVERY, candidate.source);
        } finally {
            LanTransferPeerDirectory.clear(context);
            LanTransferPeerDirectory.deactivateNetwork(context);
        }
    }

    @Test public void packetDirectoryBoundaryRejectsInvalidBeaconAndEmptyNetwork() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] malformed = "LTFD1|bad peer|38471|38473".getBytes(StandardCharsets.US_ASCII);
        DatagramPacket packet = new DatagramPacket(malformed, malformed.length,
                InetAddress.getByName("127.0.0.1"), 38472);
        assertFalse(LanTransferDiscovery.recordBeaconCandidate(context, packet,
                "peer-local", "wifi:test", System.currentTimeMillis()));
        assertFalse(LanTransferDiscovery.recordBeaconCandidate(context, packet,
                "peer-local", "", System.currentTimeMillis()));
    }

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
    @Test public void manualRefreshAndPeriodicBeaconHaveIndependentThrottleWindows() {
        AtomicLong periodic = new AtomicLong(Long.MIN_VALUE);
        AtomicLong manual = new AtomicLong(Long.MIN_VALUE);
        assertTrue(LanTransferDiscovery.claimBeaconSlot(periodic, manual, false, 40_000L));
        assertTrue(LanTransferDiscovery.claimBeaconSlot(periodic, manual, true, 40_001L));
        assertFalse(LanTransferDiscovery.claimBeaconSlot(periodic, manual, false, 40_500L));
        assertFalse(LanTransferDiscovery.claimBeaconSlot(periodic, manual, true, 40_500L));
        assertTrue(LanTransferDiscovery.claimBeaconSlot(periodic, manual, false, 41_000L));
        assertTrue(LanTransferDiscovery.claimBeaconSlot(periodic, manual, true, 41_001L));
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