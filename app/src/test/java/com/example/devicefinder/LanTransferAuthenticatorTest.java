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
    @Test public void serverSelectsCredentialByAuthenticatedPeerId() throws Exception {
        final String senderSecret="sender-specific-secret-0123456789", otherSecret="other-peer-secret-0123456789012";
        ServerSocket listener=new ServerSocket(0);ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<LanTransferAuthenticator.AuthenticatedPeer> server=executor.submit(()->{try(Socket socket=listener.accept()){
                socket.setSoTimeout(2000);return LanTransferAuthenticator.authenticateServer(socket,"receiver",
                        id->"sender".equals(id)?senderSecret:otherSecret,"sender"::equals);}});
            try(Socket clientSocket=new Socket("127.0.0.1",listener.getLocalPort())){
                clientSocket.setSoTimeout(2000);
                LanTransferAuthenticator.AuthenticatedPeer client=LanTransferAuthenticator.authenticateClient(clientSocket,"sender","receiver",senderSecret);
                assertArrayEquals(client.sessionKey,server.get(3,TimeUnit.SECONDS).sessionKey);
            }
        } finally {listener.close();executor.shutdownNow();}
    }
    @Test public void serverRejectsTrustedIdentityWithoutStoredCredential() throws Exception {
        ServerSocket listener=new ServerSocket(0);ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> server=executor.submit(()->{try(Socket socket=listener.accept()){
                socket.setSoTimeout(2000);LanTransferAuthenticator.authenticateServer(socket,"receiver",id->null,"sender"::equals);return false;
            }catch(SecurityException expected){return true;}});
            try(Socket clientSocket=new Socket("127.0.0.1",listener.getLocalPort())){
                clientSocket.setSoTimeout(2000);
                try{LanTransferAuthenticator.authenticateClient(clientSocket,"sender","receiver","sender-specific-secret-0123456789");fail("missing key must reject");}
                catch(java.io.IOException expected){}
            }
            assertTrue(server.get(3,TimeUnit.SECONDS));
        } finally {listener.close();executor.shutdownNow();}
    }
    @Test
    public void androidMatchesSharedPythonAuthenticationDerivationVector() throws Exception {
        byte[] secretBytes = new byte[32];
        java.util.Arrays.fill(secretBytes,(byte)'x');
        String secret = android.util.Base64.encodeToString(secretBytes,android.util.Base64.NO_WRAP);
        byte[] key = invokePrivate("deriveKey", new Class<?>[]{String.class}, secret);
        assertEquals("80d0e9e112555fb1910dc6a60c103f1a0b1e3639ac1060517fcaf215263c5af7",
                hex(key));
        byte[] clientId = "android-A".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] serverId = "python-B".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] clientNonce = new byte[32], serverNonce = new byte[32];
        for (int i=0;i<32;i++) { clientNonce[i]=(byte)i; serverNonce[i]=(byte)(i+32); }
        byte[] transcript = invokePrivate("transcript",
                new Class<?>[]{byte[].class,byte[].class,byte[].class,byte[].class},
                clientId,serverId,clientNonce,serverNonce);
        byte[] serverDomain = "lan-transfer-v1/server\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] clientDomain = "lan-transfer-v1/client\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sessionDomain = "lan-transfer-v1/session\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("51d864d2fc5cef2474309c7b0188f743789a05227f33258213ac197e716cd23e",
                hex(invokePrivate("hmac",new Class<?>[]{byte[].class,byte[].class},key,concat(serverDomain,transcript))));
        assertEquals("585ca4ddbad267eab82efd459a0893b3b3cb95d8ae013683d19a4a1ecc999f0d",
                hex(invokePrivate("hmac",new Class<?>[]{byte[].class,byte[].class},key,concat(clientDomain,transcript))));
        assertEquals("6c962607aa55ed1c48952647b0c759a7efc179c6921b30fad4e204cce704ef8a",
                hex(invokePrivate("hmac",new Class<?>[]{byte[].class,byte[].class},key,concat(sessionDomain,transcript))));
    }

    private static byte[] invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = LanTransferAuthenticator.class.getDeclaredMethod(name,types);
        method.setAccessible(true);
        return (byte[]) method.invoke(null,args);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first,first.length+second.length);
        System.arraycopy(second,0,result,first.length,second.length);
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format(java.util.Locale.ROOT,"%02x",item & 0xff));
        return result.toString();
    }

}
