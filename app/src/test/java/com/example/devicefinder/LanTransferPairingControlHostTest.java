package com.example.devicefinder;

import org.junit.Test;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LanTransferPairingControlHostTest {
    @Test public void serviceHostCreatesPendingResponderSessionWithoutPersistingCredentials()
            throws Exception {
        final String local = "receiver";
        final String remote = "sender";
        final CountDownLatch requested = new CountDownLatch(1);
        final String[] callback = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost(local,
                (sessionId, peerId, sas, expiresAt) -> {
                    callback[0] = sessionId;
                    callback[1] = peerId;
                    callback[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingHandshake.InitiatorStart start =
                    LanTransferPairingHandshake.begin(remote, local, 1L);
            LanTransferPairingControlProtocol.Message response =
                    LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                            host.getBoundPort(), start.request, 2000, 3000);
            LanTransferPairingCrypto.Result initiatorMaterial =
                    LanTransferPairingHandshake.completeInitiator(start, response);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            assertEquals(start.request.sessionId, callback[0]);
            assertEquals(remote, callback[1]);
            assertEquals(initiatorMaterial.shortAuthenticationCode, callback[2]);
            assertEquals(1, host.pendingSessions().size());
            LanTransferPairingControlHost.Pending pending = host.pendingSessions().get(0);
            assertEquals(LanTransferPairingSession.State.WAITING_LOCAL_CONFIRMATION, pending.state);
            assertEquals(callback[0], pending.sessionId);
            assertEquals(callback[1], pending.remotePeerId);
            assertEquals(callback[2], pending.sas);
        } finally {
            host.close();
        }
        assertFalse(host.isRunning());
        assertTrue(host.pendingSessions().isEmpty());
    }

    @Test public void localSasMismatchRejectsAndRemovesPendingSession() throws Exception {
        final CountDownLatch requested = new CountDownLatch(1);
        final String[] sessionAndPeer = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    sessionAndPeer[0] = sessionId;
                    sessionAndPeer[1] = peerId;
                    sessionAndPeer[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingHandshake.InitiatorStart start =
                    LanTransferPairingHandshake.begin("sender", "receiver", UUID.randomUUID().toString(), 1L);
            LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), start.request, 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            assertEquals(LanTransferPairingSession.State.REJECTED,
                    host.confirmLocal(sessionAndPeer[0], sessionAndPeer[1],
                            sessionAndPeer[2].equals("000000") ? "000001" : "000000"));
            assertTrue(host.pendingSessions().isEmpty());
        } finally {
            host.close();
        }
    }
}
