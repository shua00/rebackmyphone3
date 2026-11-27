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

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) throw new AssertionError("Unable to clean test path " + file);
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
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
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
    public void androidSenderOffersThenTransfersOnlyAfterExplicitReceiverAccept() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        File source = new File(context.getCacheDir(), "lan-transfer-client-source.txt");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(source)) {
            out.write(PAYLOAD);
        }
        LanTransferStore store = new LanTransferStore(context);
        String id = "android-client-test";
        LanTransferStore.Offer offer = new LanTransferStore.Offer(id, "sender", "receiver",
                "from-client.txt", PAYLOAD.length, sha(PAYLOAD), 1L, "text/plain");
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, "sender"::equals, () -> 10L);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> served = executor.submit(() -> {
                int total = 0;
                for (int i = 0; i < 2; i++) {
                    try (Socket accepted = listener.accept()) { total += session.serve(accepted); }
                }
                return total;
            });
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                JSONObject ack = LanTransferTcpClient.sendOffer(client, source, offer,
                        "sender", "receiver", SECRET, LanTransferStore.DEFAULT_CHUNK_SIZE, 1024 * 1024);
                assertEquals("offered", ack.getString("state"));
            }
            assertEquals("offered", store.getTransfer(id).state);
            // Receiving an authenticated Offer never silently accepts it.
            store.transition(id, "accepted", 11L);
            store.transition(id, "transferring", 12L);
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                java.util.List<Integer> remaining = LanTransferTcpClient.sendFile(client, source,
                        offer, "sender", "receiver", SECRET, LanTransferStore.DEFAULT_CHUNK_SIZE,
                        LanTransferTcpClient.DEFAULT_CHUNKS_PER_SESSION, 1024 * 1024, null);
                assertTrue(remaining.isEmpty());
            }
            assertEquals(4, (int) served.get(5, TimeUnit.SECONDS));
            assertEquals("completed", store.getTransfer(id).state);
            File[] published = new File(context.getFilesDir(), "lan-transfer-received").listFiles();
            assertNotNull(published);
            assertEquals(1, published.length);
            assertArrayEquals(PAYLOAD, Files.readAllBytes(published[0].toPath()));
        } finally {
            listener.close();
            executor.shutdownNow();
            store.closeStore();
            source.delete();
        }
    }

    @Test
    public void emptyOfferCannotBePublishedBeforeExplicitAcceptance() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        LanTransferStore store = new LanTransferStore(context);
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String id = "empty-offer-before-accept";
        store.createOffer(new LanTransferStore.Offer(id, "sender", "receiver",
                "empty.bin", 0L, emptySha, 1L, "application/octet-stream"),
                10L, LanTransferStore.DEFAULT_CHUNK_SIZE);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, "sender"::equals, () -> 10L);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> served = executor.submit(() -> {
                try (Socket accepted = listener.accept()) {
                    session.serve(accepted);
                    return false;
                } catch (java.io.IOException expected) {
                    return true;
                }
            });
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                LanTransferAuthenticator.AuthenticatedPeer peer =
                        LanTransferAuthenticator.authenticateClient(client, "sender", "receiver", SECRET);
                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "complete", id, "sender", "receiver", null, new byte[0], null),
                        peer.sessionKey, 1024 * 1024);
            }
            assertTrue(served.get(4, TimeUnit.SECONDS));
            assertEquals("offered", store.getTransfer(id).state);
            File[] published = new File(context.getFilesDir(), "lan-transfer-received").listFiles();
            assertTrue(published == null || published.length == 0);
        } finally {
            listener.close();
            executor.shutdownNow();
            store.closeStore();
        }
    }

    @Test
    public void replayedOfferAndChunkAreAcknowledgedIdempotently() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        LanTransferStore store = new LanTransferStore(context);
        String id = "session-replay-idempotent";
        LanTransferStore.Offer offer = new LanTransferStore.Offer(id, "sender", "receiver",
                "replay.bin", PAYLOAD.length, sha(PAYLOAD), 1L, "text/plain");
        store.createOffer(offer, 1L, LanTransferStore.DEFAULT_CHUNK_SIZE);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, "sender"::equals, () -> 1000L, 3, 1024 * 1024, 3000);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> served = executor.submit(() -> {
                try (Socket accepted = listener.accept()) { return session.serve(accepted); }
            });
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                client.setSoTimeout(3000);
                LanTransferAuthenticator.AuthenticatedPeer peer =
                        LanTransferAuthenticator.authenticateClient(client, "sender", "receiver", SECRET);
                JSONObject metadata = new JSONObject()
                        .put("filename", offer.filename)
                        .put("size_bytes", offer.sizeBytes)
                        .put("sha256", offer.sha256)
                        .put("created_at_ms", offer.createdAtMs)
                        .put("mime_type", offer.mimeType)
                        .put("chunk_size_bytes", LanTransferStore.DEFAULT_CHUNK_SIZE);
                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "offer", id, "sender", "receiver", null, new byte[0], metadata),
                        peer.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame offerAck = LanTransferFrameCodec.receive(client,
                        peer.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("offer_ack", offerAck.kind);
                assertFalse(offerAck.metadata.getBoolean("new_offer"));
                assertEquals("transferring", offerAck.metadata.getString("state"));

                LanTransferFrameCodec.Frame chunk = new LanTransferFrameCodec.Frame(
                        "chunk", id, "sender", "receiver", 0, PAYLOAD, null);
                LanTransferFrameCodec.send(client, chunk, peer.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame firstAck = LanTransferFrameCodec.receive(client,
                        peer.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("chunk_ack", firstAck.kind);
                assertEquals(0, firstAck.metadata.getInt("index"));
                assertTrue(firstAck.metadata.getBoolean("new_receipt"));

                LanTransferFrameCodec.send(client, chunk, peer.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame replayAck = LanTransferFrameCodec.receive(client,
                        peer.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("chunk_ack", replayAck.kind);
                assertEquals(0, replayAck.metadata.getInt("index"));
                assertFalse(replayAck.metadata.getBoolean("new_receipt"));
            }
            assertEquals(3, (int) served.get(4, TimeUnit.SECONDS));
            assertNotNull(store.getChunkReceipt(id, 0));
            assertEquals("transferring", store.getTransfer(id).state);
        } finally {
            listener.close();
            executor.shutdownNow();
            store.closeStore();
        }
    }

    @Test
    public void completionResumesAfterProcessRestartLeftTransferVerifying() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        LanTransferStore store = new LanTransferStore(context);
        String id = "resume-verifying-state";
        store.createOffer(new LanTransferStore.Offer(id, "sender", "receiver",
                "resume.txt", PAYLOAD.length, sha(PAYLOAD), 1L, "text/plain"),
                1L, LanTransferStore.DEFAULT_CHUNK_SIZE);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        filesystem.writeChunk(id, 0, PAYLOAD, 4L);
        // Simulate a process death after entering VERIFYING but before publish/ledger commit.
        store.transition(id, "verifying", 5L);
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, "sender"::equals, () -> 10L, 1, 1024 * 1024, 3000);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> served = executor.submit(() -> {
                try (Socket accepted = listener.accept()) { return session.serve(accepted); }
            });
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                client.setSoTimeout(3000);
                LanTransferAuthenticator.AuthenticatedPeer peer =
                        LanTransferAuthenticator.authenticateClient(client, "sender", "receiver", SECRET);
                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "complete", id, "sender", "receiver", null, new byte[0], null),
                        peer.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame response = LanTransferFrameCodec.receive(client,
                        peer.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("complete", response.kind);
                assertEquals("completed", response.metadata.getString("state"));
            }
            assertEquals(1, (int) served.get(4, TimeUnit.SECONDS));
            assertEquals("completed", store.getTransfer(id).state);
            File[] published = new File(context.getFilesDir(), "lan-transfer-received").listFiles();
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
        Context context = RuntimeEnvironment.getApplication();
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
