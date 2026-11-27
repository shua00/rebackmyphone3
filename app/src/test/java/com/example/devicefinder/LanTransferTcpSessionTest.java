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


    private static File repositoryRoot() {
        File current = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (current != null && !new File(current, "lan_p2p_transfer.py").isFile())
            current = current.getParentFile();
        if (current == null) throw new AssertionError("Unable to locate Python LAN P2P repository root");
        return current;
    }

    private static Process startPythonIntegration(String script, File workingDirectory, String... args)
            throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("python3");
        command.add("-u");
        command.add("-c");
        command.add(script);
        java.util.Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory)
                .redirectErrorStream(true);
        builder.environment().put("PYTHONPATH", workingDirectory.getAbsolutePath());
        return builder.start();
    }

    private static String finishPythonIntegration(Process process) throws Exception {
        assertTrue("Python integration process timed out", process.waitFor(30, TimeUnit.SECONDS));
        byte[] output = process.getInputStream().readAllBytes();
        String text = new String(output, java.nio.charset.StandardCharsets.UTF_8);
        if (process.exitValue() != 0) System.err.println("Python integration process failed: " + text);
        assertEquals("Python integration process failed: " + text, 0, process.exitValue());
        return text;
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
    public void reconnectAfterUnobservedChunkAckRequeriesDurableReceipts() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        byte[] payload = new byte[4096 + 17];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        File source = new File(context.getCacheDir(), "lan-transfer-lost-ack.bin");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(source)) { out.write(payload); }
        LanTransferStore store = new LanTransferStore(context);
        String id = "session-lost-ack-recovery";
        LanTransferStore.Offer offer = new LanTransferStore.Offer(id, "sender", "receiver",
                "lost-ack.bin", payload.length, sha(payload), 1L, "application/octet-stream");
        store.createOffer(offer, 1L, 4096);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());

        ServerSocket firstListener = new ServerSocket(0);
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        try {
            LanTransferTcpSession firstSession = new LanTransferTcpSession(context, store, filesystem,
                    "receiver", SECRET, "sender"::equals, () -> 10L, 2, 1024 * 1024, 3000);
            Future<Integer> firstServed = firstExecutor.submit(() -> {
                try (Socket accepted = firstListener.accept()) { return firstSession.serve(accepted); }
                catch (java.io.IOException ackMayBeUnobserved) { return -1; }
            });
            try (Socket client = new Socket("127.0.0.1", firstListener.getLocalPort())) {
                client.setSoTimeout(3000);
                LanTransferAuthenticator.AuthenticatedPeer peer =
                        LanTransferAuthenticator.authenticateClient(client, "sender", "receiver", SECRET);
                JSONObject request = new JSONObject().put("limit", 62);
                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "missing", id, "sender", "receiver", null, new byte[0], request),
                        peer.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame missing = LanTransferFrameCodec.receive(client,
                        peer.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals(2, missing.metadata.getJSONArray("indexes").length());
                byte[] firstChunk = java.util.Arrays.copyOfRange(payload, 0, 4096);
                LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                        "chunk", id, "sender", "receiver", 0, firstChunk, null),
                        peer.sessionKey, 1024 * 1024);
                // Deliberately close without reading the chunk ACK.
            }
            assertTrue(firstServed.get(4, TimeUnit.SECONDS) == 2
                    || firstServed.get(4, TimeUnit.SECONDS) == -1);
            assertNotNull(store.getChunkReceipt(id, 0));
        } finally {
            firstListener.close();
            firstExecutor.shutdownNow();
        }

        ServerSocket secondListener = new ServerSocket(0);
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        try {
            LanTransferTcpSession secondSession = new LanTransferTcpSession(context, store, filesystem,
                    "receiver", SECRET, "sender"::equals, () -> 11L, 3, 1024 * 1024, 3000);
            Future<Integer> secondServed = secondExecutor.submit(() -> {
                try (Socket accepted = secondListener.accept()) { return secondSession.serve(accepted); }
            });
            try (Socket client = new Socket("127.0.0.1", secondListener.getLocalPort())) {
                java.util.List<Integer> remaining = LanTransferTcpClient.sendFile(client, source,
                        offer, "sender", "receiver", SECRET, 4096,
                        LanTransferTcpClient.DEFAULT_CHUNKS_PER_SESSION, 1024 * 1024, null);
                assertTrue(remaining.isEmpty());
            }
            assertEquals(3, (int) secondServed.get(4, TimeUnit.SECONDS));
            assertEquals("completed", store.getTransfer(id).state);
            File[] published = new File(context.getFilesDir(), "lan-transfer-received").listFiles();
            assertNotNull(published);
            assertEquals(1, published.length);
            assertArrayEquals(payload, Files.readAllBytes(published[0].toPath()));
        } finally {
            secondListener.close();
            secondExecutor.shutdownNow();
            store.closeStore();
            source.delete();
        }
    }

    @Test
    public void cancelAcknowledgementFollowsStagingCleanup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        LanTransferStore store = new LanTransferStore(context);
        String id = "session-cancel-cleanup";
        store.createOffer(new LanTransferStore.Offer(id, "sender", "receiver",
                "cancel.bin", PAYLOAD.length, sha(PAYLOAD), 1L, "text/plain"),
                1L, LanTransferStore.DEFAULT_CHUNK_SIZE);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        filesystem.writeChunk(id, 0, PAYLOAD, 4L);
        File stagingTask = new File(context.getFilesDir(), "lan-transfer-staging/" + id);
        assertTrue(stagingTask.exists());
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
                        "cancel", id, "sender", "receiver", null, new byte[0], null),
                        peer.sessionKey, 1024 * 1024);
                LanTransferFrameCodec.Frame response = LanTransferFrameCodec.receive(client,
                        peer.sessionKey, 1024 * 1024, "receiver", "sender");
                assertEquals("cancel", response.kind);
                assertEquals("cancelled", response.metadata.getString("state"));
            }
            assertEquals(1, (int) served.get(4, TimeUnit.SECONDS));
            assertEquals("cancelled", store.getTransfer(id).state);
            assertFalse(stagingTask.exists());
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

    @Test
    public void corruptedDurableReceiptIsInvalidatedAndChunkCanBeRepaired() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        LanTransferStore store = new LanTransferStore(context);
        String id = "corrupt-receipt-repair";
        byte[] payload = "repair-me".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.createOffer(new LanTransferStore.Offer(id, "sender", "receiver",
                "repair.txt", payload.length, sha(payload), 1L, "text/plain"),
                1L, 4096);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        assertTrue(filesystem.writeChunk(id, 0, payload, 4L));
        File staged = new File(new File(new File(context.getFilesDir(),
                "lan-transfer-staging"), id), "payload.part");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(staged, "rw")) {
            raf.seek(0);
            raf.write('X');
            raf.getFD().sync();
        }

        LanTransferStore.MissingPage missing = filesystem.reconcilePage(id, 5L);
        assertEquals(java.util.Collections.singletonList(0), missing.indexes);
        assertFalse(missing.more);
        assertNull(store.getChunkReceipt(id, 0));
        assertEquals("transferring", store.getTransfer(id).state);

        assertTrue(filesystem.writeChunk(id, 0, payload, 6L));
        LanTransferStore.MissingPage repaired = filesystem.reconcilePage(id, 7L);
        assertTrue(repaired.indexes.isEmpty());
        assertFalse(repaired.more);
        assertNotNull(store.getChunkReceipt(id, 0));
        store.closeStore();
    }

    @Test
    public void missingPagesAdvanceAcrossThreeAuthenticatedReconnects() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        byte[] payload = new byte[130 * 4096];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        File source = new File(context.getCacheDir(), "lan-transfer-multipage-source.bin");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(source)) {
            out.write(payload);
        }
        LanTransferStore store = new LanTransferStore(context);
        String id = "android-multipage-resume";
        LanTransferStore.Offer offer = new LanTransferStore.Offer(id, "sender", "receiver",
                source.getName(), payload.length, sha(payload), 1L, "application/octet-stream");
        store.createOffer(offer, 1L, 4096);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, "sender"::equals, () -> 10L, 64, 1024 * 1024, 5000);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> served = executor.submit(() -> {
                int total = 0;
                for (int i = 0; i < 3; i++) {
                    try (Socket accepted = listener.accept()) { total += session.serve(accepted); }
                }
                return total;
            });
            java.util.List<Integer> remaining = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                    remaining = LanTransferTcpClient.sendFile(client, source, offer,
                            "sender", "receiver", SECRET, 4096, 62, 1024 * 1024, null);
                }
                if (attempt == 0) assertEquals(62, remaining.size());
                else if (attempt == 1) assertEquals(6, remaining.size());
                else assertTrue(remaining.isEmpty());
            }
            assertNotNull(remaining);
            assertTrue(remaining.isEmpty());
            assertEquals(136, (int) served.get(15, TimeUnit.SECONDS));
            assertEquals("completed", store.getTransfer(id).state);
            File[] published = new File(context.getFilesDir(), "lan-transfer-received").listFiles();
            assertNotNull(published);
            assertEquals(1, published.length);
            assertArrayEquals(payload, Files.readAllBytes(published[0].toPath()));
        } finally {
            listener.close();
            executor.shutdownNow();
            store.closeStore();
        }
    }


    @Test
    public void completedTransferReplaysCompleteAndCancelWithoutRegression() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        LanTransferStore store = new LanTransferStore(context);
        String id = "terminal-replay";
        store.createOffer(new LanTransferStore.Offer(id, "sender", "receiver",
                "terminal.txt", PAYLOAD.length, sha(PAYLOAD), 1L, "text/plain"),
                1L, 4096);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        filesystem.writeChunk(id, 0, PAYLOAD, 4L);
        File published = filesystem.assembleAndPublish(id, 5L);
        assertTrue(published.isFile());

        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "receiver", SECRET, "sender"::equals, () -> 10L, 2, 1024 * 1024, 3000);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> served = executor.submit(() -> {
                try (Socket accepted = listener.accept()) { return session.serve(accepted); }
            });
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                LanTransferAuthenticator.AuthenticatedPeer authenticated =
                        LanTransferAuthenticator.authenticateClient(client, "sender", "receiver", SECRET);
                for (String kind : new String[]{"complete", "cancel"}) {
                    LanTransferFrameCodec.send(client, new LanTransferFrameCodec.Frame(
                            kind, id, "sender", "receiver", null, new byte[0], null),
                            authenticated.sessionKey, 1024 * 1024);
                    LanTransferFrameCodec.Frame response = LanTransferFrameCodec.receive(client,
                            authenticated.sessionKey, 1024 * 1024, "receiver", "sender");
                    assertEquals(kind, response.kind);
                    assertEquals("completed", response.metadata.getString("state"));
                }
            }
            assertEquals(2, (int) served.get(4, TimeUnit.SECONDS));
            assertEquals("completed", store.getTransfer(id).state);
            assertTrue(published.isFile());
            assertArrayEquals(PAYLOAD, Files.readAllBytes(published.toPath()));
        } finally {
            listener.close();
            executor.shutdownNow();
            store.closeStore();
        }
    }


    @Test
    public void orphanStagingBytesWithoutReceiptAreRepairedOnChunkRetry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        LanTransferStore store = new LanTransferStore(context);
        String id = "orphan-staging-repair";
        store.createOffer(new LanTransferStore.Offer(id, "sender", "receiver",
                "orphan.txt", PAYLOAD.length, sha(PAYLOAD), 1L, "text/plain"),
                1L, 4096);
        store.transition(id, "accepted", 2L);
        store.transition(id, "transferring", 3L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        File staged = new File(new File(new File(context.getFilesDir(),
                "lan-transfer-staging"), id), "payload.part");
        staged.getParentFile().mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(staged)) {
            out.write("partial-crash-data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        assertNull(store.getChunkReceipt(id, 0));

        assertTrue(filesystem.writeChunk(id, 0, PAYLOAD, 4L));
        assertArrayEquals(PAYLOAD, Files.readAllBytes(staged.toPath()));
        assertNotNull(store.getChunkReceipt(id, 0));
        LanTransferStore.MissingPage missing = filesystem.reconcilePage(id, 5L);
        assertTrue(missing.indexes.isEmpty());
        assertFalse(missing.more);
        store.closeStore();
    }


    @Test
    public void androidSenderInteroperatesWithPythonReceiverOverRealTcp() throws Exception {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.US)
                .contains("linux"));
        File root = Files.createTempDirectory("lan-p2p-android-python-").toFile();
        File source = new File(root, "android-source.bin");
        byte[] payload = new byte[10000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(source)) { out.write(payload); }
        File portFile = new File(root, "port");
        String transferId = "android-python-real-tcp";
        String secret = SECRET;
        String script = String.join("\n",
                "import hashlib, socket, sys",
                "from pathlib import Path",
                "from lan_p2p_transfer import TransferState",
                "from lan_p2p_transfer_store import TransferStore, TransferLimits",
                "from lan_p2p_transfer_files import TransferFilesystem",
                "from lan_p2p_transfer_auth import authenticate_tcp_server",
                "from lan_p2p_transfer_session import serve_transfer_session",
                "root=Path(sys.argv[1]); port_file=Path(sys.argv[2])",
                "secret=" + JSONObject.quote(secret),
                "store=TransferStore(str(root/'ledger.db'), TransferLimits(chunk_size_bytes=4096))",
                "files=TransferFilesystem(store, str(root/'stage'), str(root/'received'))",
                "listener=socket.socket(); listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)",
                "listener.bind(('127.0.0.1',0)); listener.listen(4); port_file.write_text(str(listener.getsockname()[1]))",
                "for n in range(2):",
                "  conn,_=listener.accept()",
                "  try:",
                "    peer,key=authenticate_tcp_server(conn,'python',None,lambda p:p=='android',secret_provider=lambda p:secret)",
                "    serve_transfer_session(conn,store,files,local_peer_id='python',authenticated_peer_id=peer,session_secret=key,now_ms=lambda:1000,max_frames=64)",
                "  finally: conn.close()",
                "  if n==0:",
                "    store.transition(" + JSONObject.quote(transferId) + ",TransferState.ACCEPTED,1001)",
                "    store.transition(" + JSONObject.quote(transferId) + ",TransferState.TRANSFERRING,1002)",
                "record=store.get_transfer(" + JSONObject.quote(transferId) + ")",
                "assert record.state==TransferState.COMPLETED, record.state",
                "published=next((root/'received').iterdir())",
                "assert published.read_bytes()==" + "bytes((i*31)%256 for i in range(10000))",
                "listener.close(); print('ANDROID_TO_PYTHON_TCP_OK')");
        Process python = startPythonIntegration(script, repositoryRoot(),
                root.getAbsolutePath(), portFile.getAbsolutePath());
        try {
            long deadline = System.currentTimeMillis() + 10000;
            while (!portFile.isFile() && System.currentTimeMillis() < deadline) Thread.sleep(25);
            assertTrue("Python receiver did not publish its listening port", portFile.isFile());
            int port = Integer.parseInt(new String(Files.readAllBytes(portFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim());
            try (Socket socket = new Socket("127.0.0.1", port)) {
                LanTransferStore.Offer offer = new LanTransferStore.Offer(transferId, "android",
                        "python", source.getName(), payload.length, sha(payload), 1000L,
                        "application/octet-stream");
                JSONObject ack = LanTransferTcpClient.sendOffer(socket, source, offer,
                        "android", "python", secret, 4096, 1024 * 1024);
                assertEquals("offered", ack.getString("state"));
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                LanTransferStore.Offer offer = new LanTransferStore.Offer(transferId, "android",
                        "python", source.getName(), payload.length, sha(payload), 1000L,
                        "application/octet-stream");
                assertTrue(LanTransferTcpClient.sendFile(socket, source, offer, "android",
                        "python", secret, 4096, 62, 1024 * 1024, null).isEmpty());
            }
            assertTrue(finishPythonIntegration(python).contains("ANDROID_TO_PYTHON_TCP_OK"));
        } finally {
            python.destroyForcibly();
            deleteTree(root);
        }
    }

    @Test
    public void pythonSenderInteroperatesWithAndroidReceiverOverRealTcp() throws Exception {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.US)
                .contains("linux"));
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        deleteTree(new File(context.getFilesDir(), "lan-transfer-staging"));
        deleteTree(new File(context.getFilesDir(), "lan-transfer-received"));
        byte[] payload = new byte[10000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (255 - i * 7);
        File root = Files.createTempDirectory("lan-p2p-python-android-").toFile();
        File source = new File(root, "python-source.bin");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(source)) { out.write(payload); }
        String transferId = "python-android-real-tcp";
        LanTransferStore store = new LanTransferStore(context);
        store.createOffer(new LanTransferStore.Offer(transferId, "python", "android",
                source.getName(), payload.length, sha(payload), 1000L, "application/octet-stream"),
                1000L, 4096);
        store.transition(transferId, "accepted", 1001L);
        store.transition(transferId, "transferring", 1002L);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, fileOps());
        LanTransferTcpSession session = new LanTransferTcpSession(context, store, filesystem,
                "android", SECRET, "python"::equals, () -> 1003L, 64, 1024 * 1024, 10000);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String script = String.join("\n",
                "import socket,sys",
                "from pathlib import Path",
                "from lan_p2p_transfer import TransferOffer",
                "from lan_p2p_transfer_auth import authenticate_tcp_client",
                "from lan_p2p_transfer_sender import send_file_over_session",
                "source=Path(sys.argv[3]); payload=source.read_bytes()",
                "secret=" + JSONObject.quote(SECRET),
                "offer=TransferOffer(" + JSONObject.quote(transferId) + ",'python','android',source.name,len(payload),__import__('hashlib').sha256(payload).hexdigest(),1000,'application/octet-stream')",
                "sock=socket.create_connection(('127.0.0.1',int(sys.argv[1])),timeout=10)",
                "peer,key=authenticate_tcp_client(sock,'python','android',secret)",
                "assert peer=='android'",
                "remaining=send_file_over_session(sock,str(source),offer,local_peer_id='python',remote_peer_id='android',session_secret=key,chunk_size_bytes=4096,max_chunks_per_session=62)",
                "assert remaining==(), remaining",
                "sock.close(); print('PYTHON_TO_ANDROID_TCP_OK')");
        Process python = null;
        try {
            Future<Integer> served = executor.submit(() -> {
                try (Socket accepted = listener.accept()) { return session.serve(accepted); }
            });
            python = startPythonIntegration(script, new File(System.getProperty("user.dir")),
                    Integer.toString(listener.getLocalPort()), "", source.getAbsolutePath());
            assertTrue(finishPythonIntegration(python).contains("PYTHON_TO_ANDROID_TCP_OK"));
            assertEquals(3, (int) served.get(10, TimeUnit.SECONDS));
            assertEquals("completed", store.getTransfer(transferId).state);
            File[] published = new File(context.getFilesDir(), "lan-transfer-received").listFiles();
            assertNotNull(published);
            assertEquals(1, published.length);
            assertArrayEquals(payload, Files.readAllBytes(published[0].toPath()));
        } finally {
            if (python != null) python.destroyForcibly();
            listener.close();
            executor.shutdownNow();
            store.closeStore();
            deleteTree(root);
        }
    }

}
