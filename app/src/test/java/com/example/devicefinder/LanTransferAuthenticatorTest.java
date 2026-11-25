package com.example.devicefinder;
import org.junit.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LanTransferAuthenticatorTest {
    private static final String SECRET="lan-group-secret-for-auth-tests";
    @Test public void clientAndServerDeriveSameSessionKey() throws Exception {
        ServerSocket listener=new ServerSocket(0);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<LanTransferAuthenticator.AuthenticatedPeer> server=executor.submit(()->{
                try(Socket s=listener.accept()) { s.setSoTimeout(2000);
                    return LanTransferAuthenticator.authenticateServer(s,"receiver",SECRET,"sender"::equals); }
            });
            try(Socket clientSocket=new Socket("127.0.0.1",listener.getLocalPort())) {
                clientSocket.setSoTimeout(2000);
                LanTransferAuthenticator.AuthenticatedPeer client=LanTransferAuthenticator.authenticateClient(
                    clientSocket,"sender","receiver",SECRET);
                LanTransferAuthenticator.AuthenticatedPeer remote=server.get(3,TimeUnit.SECONDS);
                assertEquals("receiver",client.peerId); assertEquals("sender",remote.peerId);
                assertArrayEquals(client.sessionKey,remote.sessionKey); assertEquals(32,client.sessionKey.length);
            }
        } finally { listener.close(); executor.shutdownNow(); }
    }
    @Test public void serverRejectsPeerOutsideTrustedRegistry() throws Exception {
        ServerSocket listener=new ServerSocket(0);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> server=executor.submit(()->{
                try(Socket s=listener.accept()) { s.setSoTimeout(2000);
                    LanTransferAuthenticator.authenticateServer(s,"receiver",SECRET,ignored->false); return false;
                } catch(SecurityException expected) { return true; }
            });
            try(Socket clientSocket=new Socket("127.0.0.1",listener.getLocalPort())) {
                clientSocket.setSoTimeout(2000);
                try { LanTransferAuthenticator.authenticateClient(clientSocket,"sender","receiver",SECRET); }
                catch(Exception expected) { /* rejected before server proof */ }
            }
            assertTrue(server.get(3,TimeUnit.SECONDS));
        } finally { listener.close(); executor.shutdownNow(); }
    }
}
