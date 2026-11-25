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
public class LanTransferRuntimeDiagnosticsTest {
    private Context context;
    private static final long NOW = 1_000_000L;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LanTransferPeerDirectory.clear(context);
        LanTransferPeerDirectory.activateNetwork(context, "wifi:diag");
        LanTransferRuntimeDiagnostics.resetForTests();
    }
    @After public void tearDown() {
        LanTransferPeerDirectory.clear(context);
        LanTransferRuntimeDiagnostics.resetForTests();
    }
    @Test public void uninitializedSubsystemIsUnknownAndNoPeerIsNotFailure() {
        LanTransferRuntimeDiagnostics.Snapshot snapshot =
                LanTransferRuntimeDiagnostics.snapshot(context, NOW);
        assertEquals(LanTransferRuntimeDiagnostics.STATE_UNKNOWN, snapshot.discoveryState);
        assertEquals(LanTransferRuntimeDiagnostics.STATE_UNKNOWN, snapshot.tcpListenerState);
        assertEquals(0, snapshot.freshPeerCount);
        assertEquals(0L, snapshot.latestPeerAgeMs);
    }
    @Test public void runningDiscoveryWithoutPeersRemainsRunning() {
        LanTransferRuntimeDiagnostics.publishDiscoveryState(LanTransferRuntimeDiagnostics.STATE_RUNNING);
        LanTransferRuntimeDiagnostics.Snapshot snapshot =
                LanTransferRuntimeDiagnostics.snapshot(context, NOW);
        assertEquals(LanTransferRuntimeDiagnostics.STATE_RUNNING, snapshot.discoveryState);
        assertEquals(0, snapshot.freshPeerCount);
    }
    @Test public void freshPeerIsCountedWithoutExposingEndpoint() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context,
                "peer-secret-id", "192.168.1.9", 38471, "wifi:diag", NOW - 5000);
        LanTransferRuntimeDiagnostics.Snapshot snapshot =
                LanTransferRuntimeDiagnostics.snapshot(context, NOW);
        assertEquals(1, snapshot.freshPeerCount);
        assertEquals(5000L, snapshot.latestPeerAgeMs);
    }
    @Test public void expiredPeerIsNotReportedAsFresh() {
        LanTransferPeerDirectory.recordFileDiscoveryCandidate(context,
                "peer-secret-id", "192.168.1.9", 38471, "wifi:diag", NOW - 120001);
        LanTransferRuntimeDiagnostics.Snapshot snapshot =
                LanTransferRuntimeDiagnostics.snapshot(context, NOW);
        assertEquals(0, snapshot.freshPeerCount);
    }
    @Test public void failedAndStoppedStatesRemainDistinct() {
        LanTransferRuntimeDiagnostics.publishDiscoveryState(LanTransferRuntimeDiagnostics.STATE_FAILED);
        LanTransferRuntimeDiagnostics.publishTcpState(LanTransferRuntimeDiagnostics.STATE_STOPPED);
        LanTransferRuntimeDiagnostics.Snapshot snapshot =
                LanTransferRuntimeDiagnostics.snapshot(context, NOW);
        assertEquals(LanTransferRuntimeDiagnostics.STATE_FAILED, snapshot.discoveryState);
        assertEquals(LanTransferRuntimeDiagnostics.STATE_STOPPED, snapshot.tcpListenerState);
    }
}
