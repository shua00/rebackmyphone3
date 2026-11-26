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
        String secret = "base64-encoded-peer-secret-for-sender";
        byte[] key = invokePrivate("deriveKey", new Class<?>[]{String.class}, secret);
        assertEquals("a69a4ac48fa3fa1d8454c4732275bedc71b973d349b767515d05ac46c62ff330",
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
        assertEquals("bed8741fd9deae227eeae2cf44eae2cb12399ec6ca3c7e1920a65a76fc7dac57",
                hex(invokePrivate("hmac",new Class<?>[]{byte[].class,byte[].class},key,concat(serverDomain,transcript))));
        assertEquals("29230d3e9d9fda979422daf6f64214b520aa17cceee2cad7a1ea74c62ad39a48",
                hex(invokePrivate("hmac",new Class<?>[]{byte[].class,byte[].class},key,concat(clientDomain,transcript))));
        assertEquals("955200ba44ad7dd482ef90b9541247a0d27d59970cfdf1c9f17126be67d72be1",
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
