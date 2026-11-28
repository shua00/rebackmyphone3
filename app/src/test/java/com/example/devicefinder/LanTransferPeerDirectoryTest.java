package com.example.devicefinder;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanTransferPeerDirectoryTest {
    private Context context;
    private static final long NOW = 1_000_000L;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LanTransferPeerDirectory.clear(context);
        LanTransferPeerDirectory.activateNetwork(context, "wifi:test");
    }
    @After public void tearDown() { LanTransferPeerDirectory.clear(context); }

    @Test public void legacyDiscoveryRecordsDefaultToTheDedicatedPairingPort() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "legacy-peer",
                "192.168.1.12", 0, "wifi:test", NOW);
        LanTransferPeerDirectory.Candidate candidate =
                LanTransferPeerDirectory.findCurrent(context, "legacy-peer", NOW + 1);
        assertNotNull(candidate);
        assertEquals(0, candidate.port);
        assertTrue(candidate.isPairingConnectable());
        assertEquals(LanTransferPairingControlProtocol.DEFAULT_PORT, candidate.pairingPort);
    }

    @Test public void pairingEndpointRemainsConnectableBeforeCredentialGatedFileListenerStarts() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-a",
                "192.168.1.10", 0, LanTransferPairingControlProtocol.DEFAULT_PORT,
                "wifi:test", NOW);
        LanTransferPeerDirectory.Candidate candidate =
                LanTransferPeerDirectory.findCurrent(context, "peer-a", NOW + 1);
        assertNotNull(candidate);
        assertFalse(candidate.isConnectable());
        assertTrue(candidate.isPairingConnectable());
        assertEquals(LanTransferPairingControlProtocol.DEFAULT_PORT, candidate.pairingPort);
    }

    @Test public void chatHintCannotOverwriteFreshFileDiscoveryEndpoint() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context,"peer-a","192.168.1.10",38471,"wifi:test",NOW);
        LanTransferPeerDirectory.recordChatDiscoveryHint(context,"peer-a","192.168.1.11",8888,"wifi:test",NOW+1000);
        LanTransferPeerDirectory.Candidate c=LanTransferPeerDirectory.findCurrent(context,"peer-a",NOW+1000);
        assertEquals("192.168.1.10",c.host);
        assertEquals(38471,c.port);
        assertEquals(LanTransferPeerDirectory.SOURCE_FILE_DISCOVERY,c.source);
    }
    @Test public void chatHintWorksAsFallbackWhenFileDiscoveryIsAbsent() {
        LanTransferPeerDirectory.recordChatDiscoveryHint(context,"peer-a","192.168.1.11",38471,"wifi:test",NOW);
        LanTransferPeerDirectory.Candidate c=LanTransferPeerDirectory.findCurrent(context,"peer-a",NOW+1);
        assertNotNull(c);
        assertEquals(LanTransferPeerDirectory.SOURCE_CHAT_HINT,c.source);
    }
    @Test public void expiredFileLeaseFallsBackToFreshChatHint() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context,"peer-a","192.168.1.10",38471,"wifi:test",NOW);
        LanTransferPeerDirectory.recordChatDiscoveryHint(context,"peer-a","192.168.1.11",38471,"wifi:test",NOW+1000);
        LanTransferPeerDirectory.Candidate c=LanTransferPeerDirectory.findCurrent(context,"peer-a",NOW+120001);
        assertNotNull(c);
        assertEquals("192.168.1.11",c.host);
        assertEquals(LanTransferPeerDirectory.SOURCE_CHAT_HINT,c.source);
    }
    @Test public void candidatesFromPreviousWifiFingerprintAreNotVisible() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-a",
                "192.168.1.10", 38471, "wifi:old", NOW);
        LanTransferPeerDirectory.activateNetwork(context, "wifi:new");
        assertTrue(LanTransferPeerDirectory.loadCurrent(context, NOW + 1).isEmpty());
        assertNull(LanTransferPeerDirectory.findCurrent(context, "peer-a", NOW + 1));
        LanTransferPeerDirectory.activateNetwork(context, "wifi:old");
        assertNotNull(LanTransferPeerDirectory.findCurrent(context, "peer-a", NOW + 1));
    }

    @Test public void deactivatedNetworkHidesCachedCandidatesUntilANewNetworkIsActivated() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-a",
                "192.168.1.10", 38471, "wifi:test", NOW);
        assertEquals(1, LanTransferPeerDirectory.loadCurrent(context, NOW + 1).size());
        LanTransferPeerDirectory.deactivateNetwork(context);
        assertNull(LanTransferPeerDirectory.getCurrentNetwork(context));
        assertTrue(LanTransferPeerDirectory.loadCurrent(context, NOW + 1).isEmpty());
        assertNull(LanTransferPeerDirectory.findCurrent(context, "peer-a", NOW + 1));
        LanTransferPeerDirectory.activateNetwork(context, "wifi:test");
        assertNotNull(LanTransferPeerDirectory.findCurrent(context, "peer-a", NOW + 1));
    }

    @Test public void endpointLeaseExpiresAtTtlAndRejectsFutureTimestamp() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-a",
                "192.168.1.10", 38471, "wifi:test", NOW);
        assertNotNull(LanTransferPeerDirectory.findCurrent(context, "peer-a",
                NOW + LanTransferPeerDirectory.ENDPOINT_TTL_MS));
        assertNull(LanTransferPeerDirectory.findCurrent(context, "peer-a",
                NOW + LanTransferPeerDirectory.ENDPOINT_TTL_MS + 1));
        LanTransferPeerDirectory.clear(context);
        LanTransferPeerDirectory.activateNetwork(context, "wifi:test");
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-future",
                "192.168.1.11", 38471, "wifi:test", NOW + 10_000);
        assertNull(LanTransferPeerDirectory.findCurrent(context, "peer-future", NOW + 1));
    }

    @Test public void devicePickerDeduplicatesAndPrefersFileDiscovery() {
        LanTransferPeerDirectory.recordChatDiscoveryHint(context,"peer-a","192.168.1.11",38471,"wifi:test",NOW+1000);
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context,"peer-a","192.168.1.10",38471,"wifi:test",NOW);
        assertEquals(1,LanTransferPeerDirectory.loadCurrent(context,NOW+1000).size());
        assertEquals(LanTransferPeerDirectory.SOURCE_FILE_DISCOVERY,
                LanTransferPeerDirectory.loadCurrent(context,NOW+1000).get(0).source);
    }

    @Test public void onePeerCanRetainEndpointsOnStaAndHotspotPaths() {
        LanTransferPeerDirectory.activateNetworks(context,
                java.util.Arrays.asList("wifi:sta", "ap:hotspot"));
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-multi",
                "192.168.1.20", 38471, 38472, "wifi:sta", NOW);
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-multi",
                "192.168.43.20", 38471, 38472, "ap:hotspot", NOW + 10);
        java.util.List<LanTransferPeerDirectory.Candidate> endpoints =
                LanTransferPeerDirectory.findCurrentAll(context, "peer-multi", NOW + 20);
        assertEquals(2, endpoints.size());
        assertEquals(2, LanTransferPeerDirectory.loadCurrentAll(context, NOW + 20).size());
        assertEquals(1, new java.util.HashSet<String>() {{
            for (LanTransferPeerDirectory.Candidate endpoint : endpoints) add(endpoint.peerId);
        }}.size());
    }

    @Test public void removingOnePathDoesNotRevokeOtherPathEndpoint() {
        LanTransferPeerDirectory.activateNetworks(context,
                java.util.Arrays.asList("wifi:sta", "ap:hotspot"));
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-multi",
                "192.168.1.20", 38471, "wifi:sta", NOW);
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-multi",
                "192.168.43.20", 38471, "ap:hotspot", NOW);
        LanTransferPeerDirectory.deactivateNetwork(context, "ap:hotspot");
        java.util.List<LanTransferPeerDirectory.Candidate> endpoints =
                LanTransferPeerDirectory.findCurrentAll(context, "peer-multi", NOW + 1);
        assertEquals(1, endpoints.size());
        assertEquals("wifi:sta", endpoints.get(0).networkFingerprint);
    }

    @Test public void samePathCanRetainDistinctEndpointsForOnePeer() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-multi",
                "192.168.1.20", 38471, "wifi:test", NOW);
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context, "peer-multi",
                "192.168.1.21", 38471, "wifi:test", NOW + 1);
        assertEquals(2, LanTransferPeerDirectory.findCurrentAll(context, "peer-multi", NOW + 2).size());
    }
}
