package com.example.devicefinder;

import org.junit.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LanTransferAuthenticatorTest {
    private static final String SECRET = "lan-group-secret-for-auth-tests";

    @Test
    public void clientAndServerDeriveSameSessionKey() throws Exception {
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<LanTransferAuthenticator.AuthenticatedPeer> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    return LanTransferAuthenticator.authenticateServer(
                            socket, "receiver", SECRET, "sender"::equals);
                }
            });
            try (Socket clientSocket = new Socket("127.0.0.1", listener.getLocalPort())) {
                clientSocket.setSoTimeout(2000);
                LanTransferAuthenticator.AuthenticatedPeer client =
                        LanTransferAuthenticator.authenticateClient(
                                clientSocket, "sender", "receiver", SECRET);
                LanTransferAuthenticator.AuthenticatedPeer remote = server.get(3, TimeUnit.SECONDS);
                assertEquals("receiver", client.peerId);
                assertEquals("sender", remote.peerId);
                assertArrayEquals(client.sessionKey, remote.sessionKey);
                assertEquals(32, client.sessionKey.length);
            }
        } finally {
            listener.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void serverRejectsPeerOutsideTrustedRegistry() throws Exception {
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    LanTransferAuthenticator.authenticateServer(
                            socket, "receiver", SECRET, ignored -> false);
                    return false;
                } catch (SecurityException expected) {
                    return true;
                }
            });
            try (Socket clientSocket = new Socket("127.0.0.1", listener.getLocalPort())) {
                clientSocket.setSoTimeout(2000);
                try {
                    LanTransferAuthenticator.authenticateClient(
                            clientSocket, "sender", "receiver", SECRET);
                    fail("untrusted peer must not authenticate");
                } catch (java.io.IOException expected) {
                    // Server withholds its proof for an untrusted peer.
                }
            }
            assertTrue(server.get(3, TimeUnit.SECONDS));
        } finally {
            listener.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void clientRejectsServerProofWhenGroupSecretDiffers() throws Exception {
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    LanTransferAuthenticator.authenticateServer(
                            socket, "receiver", SECRET, "sender"::equals);
                    return false;
                } catch (java.io.IOException | SecurityException expected) {
                    return true;
                }
            });
            try (Socket clientSocket = new Socket("127.0.0.1", listener.getLocalPort())) {
                clientSocket.setSoTimeout(2000);
                try {
                    LanTransferAuthenticator.authenticateClient(
                            clientSocket, "sender", "receiver", "different-group-secret");
                    fail("wrong PSK must be rejected");
                } catch (SecurityException expected) {
                    // Client rejects the server proof before returning a session key.
                }
            }
            assertTrue(server.get(3, TimeUnit.SECONDS));
        } finally {
            listener.close();
            executor.shutdownNow();
        }
    }
}
