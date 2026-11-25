package com.example.devicefinder;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanTransferTcpSessionTest {
    private static final String SECRET = "lan-group-secret-for-session-tests";
    private static final byte[] PAYLOAD = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return out.toString();
    }

    private static LanTransferFilesystem.DurableFileOps fileOps() {
        return new LanTransferFilesystem.DurableFileOps() {
            @Override public void syncDirectory(File directory) {}
            @Override public void atomicRename(File source, File target) throws java.io.IOException {
                if (!source.renameTo(target)) throw new java.io.IOException("rename failed");
            }
        };
    }

    @Test
    public void authenticatedSessionDurablyAcksChunkAndPublishesVerifiedFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        LanTransferStore store = new LanTransferStore(context);
        String id = "session-test-1";
        store.createOffer(new LanTransferStore.Offer(id, "sender", "receiver",
                "hello.txt", PAYLOAD.length, sha(PAYLOAD), 1L, "text/plain"),
                1L, LanTransferStore.DEFAULT_CHUNK_SIZE);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, "sender"::equals, () -> 10L,
                3, 1024 * 1024, 3000);

        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> served = executor.submit(() -> {
                try (Socket accepted = listener.accept()) {
                    return session.serve(accepted);
                }
            });
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                client.setSoTimeout(3000);
                LanTransferAuthenticator.AuthenticatedPeer authenticated =
                        LanTransferAuthenticator.authenticateClient(client, "sender", "receiver", SECRET);
                JSONObject request = new JSONObject().put("limit", 8);
                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "missing", id, "sender", "receiver", null, new byte[0], request),
                        authenticated.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame missing = LanTransferFrameCodec.receive(client,
                        authenticated.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("missing", missing.kind);
                assertEquals(1, missing.metadata.getJSONArray("indexes").length());

                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "chunk", id, "sender", "receiver", 0, PAYLOAD, null),
                        authenticated.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame ack = LanTransferFrameCodec.receive(client,
                        authenticated.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("chunk_ack", ack.kind);
                assertEquals(0, ack.metadata.getInt("index"));
                assertTrue(store.getChunkReceipt(id, 0) != null);

                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "complete", id, "sender", "receiver", null, new byte[0], null),
                        authenticated.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame complete = LanTransferFrameCodec.receive(client,
                        authenticated.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("complete", complete.kind);
                assertEquals("completed", complete.metadata.getString("state"));
            }
            assertEquals(3, (int) served.get(4, TimeUnit.SECONDS));
            assertEquals("completed", store.getTransfer(id).state);
            File publishedDir = new File(context.getFilesDir(), "lan-transfer-received");
            File[] published = publishedDir.listFiles();
            assertNotNull(published);
            assertEquals(1, published.length);
            assertArrayEquals(PAYLOAD, Files.readAllBytes(published[0].toPath()));
        } finally {
            listener.close();
            executor.shutdownNow();
            store.closeStore();
        }
    }

    @Test
    public void untrustedPeerIsRejectedBeforeAnyTransferFrameIsProcessed() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        LanTransferStore store = new LanTransferStore(context);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, ignored -> false, () -> 10L, 1, 1024 * 1024, 2000);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> served = executor.submit(() -> {
                try (Socket accepted = listener.accept()) {
                    session.serve(accepted);
                    return false;
                } catch (SecurityException expected) {
                    return true;
                }
            });
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                try {
                    LanTransferAuthenticator.authenticateClient(client, "attacker", "receiver", SECRET);
                    fail("untrusted peer must not complete authentication");
                } catch (java.io.IOException expected) {
                    // The server intentionally withholds its proof.
                }
            }
            assertTrue(served.get(3, TimeUnit.SECONDS));
        } finally {
            listener.close();
            executor.shutdownNow();
            store.closeStore();
        }
    }
}
