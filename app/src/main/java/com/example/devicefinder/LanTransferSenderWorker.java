package com.example.devicefinder;

import android.net.Network;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, resumable sender coordinator.
 *
 * The owner supplies the currently selected Wi-Fi Network on every attempt. The
 * worker never falls back to cellular/default routing and never touches chat queues.
 * Re-running it with the same immutable Offer and private source snapshot is safe:
 * the receiver's Offer is idempotent and every data session re-queries missing chunks.
 */
public final class LanTransferSenderWorker implements Runnable {
    public interface NetworkProvider {
        Network currentWifiNetwork() throws IOException;
    }
    public interface Listener {
        void onState(String state);
        void onProgress(int sentInBatch, int batchSize);
    }

    public static final int DEFAULT_MAX_TRANSFER_SESSIONS = 120;
    public static final long DEFAULT_OFFER_WAIT_MS = 15L * 60L * 1000L;
    private static final long CONNECT_TIMEOUT_MS = 8000L;
    private static final long MAX_BACKOFF_MS = 30000L;

    private final LanTransferStore store;
    private final LanTransferCredentials.Value credentials;
    private final NetworkProvider networks;
    private final File source;
    private final LanTransferStore.Offer offer;
    private final String localPeerId;
    private final String remoteHost;
    private final int remotePort;
    private final int chunkSizeBytes;
    private final int maxSessions;
    private final long offerWaitMs;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);

    public LanTransferSenderWorker(LanTransferStore store,
            LanTransferCredentials.Value credentials, NetworkProvider networks,
            File source, LanTransferStore.Offer offer, String localPeerId,
            String remoteHost, int remotePort, int chunkSizeBytes,
            int maxSessions, long offerWaitMs, Listener listener) {
        if (store == null || credentials == null || networks == null || source == null
                || offer == null || localPeerId == null || remoteHost == null
                || remoteHost.trim().isEmpty() || remotePort < 1 || remotePort > 65535
                || chunkSizeBytes < LanTransferStore.MIN_CHUNK_SIZE
                || chunkSizeBytes > LanTransferStore.MAX_CHUNK_SIZE
                || maxSessions < 1 || maxSessions > 4096 || offerWaitMs < 1000L
                || !localPeerId.equals(offer.senderPeerId)
                || !credentials.trustedPeerIds.contains(offer.targetPeerId)) {
            throw new IllegalArgumentException("Invalid or untrusted outgoing transfer");
        }
        this.store = store;
        this.credentials = credentials;
        this.networks = networks;
        this.source = source;
        this.offer = offer;
        this.localPeerId = localPeerId;
        this.remoteHost = remoteHost.trim();
        this.remotePort = remotePort;
        this.chunkSizeBytes = chunkSizeBytes;
        this.maxSessions = maxSessions;
        this.offerWaitMs = offerWaitMs;
        this.listener = listener;
    }

    public void cancel() { cancelled.set(true); }

    /** Stop at the next safe protocol boundary while retaining the durable task for resume. */
    public void pause() { pauseRequested.set(true); }

    @Override public void run() {
        try {
            execute();
            if (pauseRequested.get()) notifyPausedAtBoundary();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            notifyPausedAtBoundary();
        } catch (GeneralSecurityException securityFailure) {
            failIfTransferring();
            notifyState("failed");
        } catch (IOException failure) {
            if (pauseRequested.get()) notifyPausedAtBoundary();
            else { pauseIfTransferring(); notifyState("paused"); }
        } catch (RuntimeException invalidTask) {
            failIfTransferring();
            notifyState("failed");
        }
    }

    private void execute() throws IOException, GeneralSecurityException, InterruptedException {
        if (!source.isFile() || source.length() != offer.sizeBytes)
            throw new IOException("Private source snapshot is missing or has changed");
        store.createOffer(offer, System.currentTimeMillis(), chunkSizeBytes);
        LanTransferStore.Transfer local = requireLocalTask();
        if ("completed".equals(local.state)) { notifyState("completed"); return; }
        if ("cancelled".equals(local.state) || "failed".equals(local.state)
                || "rejected".equals(local.state) || "expired".equals(local.state))
            throw new IllegalStateException("Outgoing transfer is terminal: " + local.state);

        long offerDeadline = System.currentTimeMillis() + offerWaitMs;
        long backoff = 1000L;
        JSONObject acknowledgement = null;
        while (!cancelled.get() && !pauseRequested.get() && System.currentTimeMillis() < offerDeadline) {
            try {
                Network network = networks.currentWifiNetwork();
                if (network == null) throw new IOException("No selected Wi-Fi LAN network");
                acknowledgement = LanTransferTcpClient.sendOffer(network, remoteHost, remotePort,
                        source, offer, localPeerId, offer.targetPeerId, credentials.sharedSecret,
                        chunkSizeBytes, 1024 * 1024, (int) CONNECT_TIMEOUT_MS);
                String remoteState = acknowledgement.optString("state", "");
                if ("rejected".equals(remoteState) || "cancelled".equals(remoteState)
                        || "expired".equals(remoteState) || "failed".equals(remoteState)) {
                    if ("offered".equals(requireLocalTask().state))
                        store.transition(offer.transferId, remoteState, System.currentTimeMillis());
                    notifyState(remoteState);
                    return;
                }
                if ("accepted".equals(remoteState) || "transferring".equals(remoteState)
                        || "completed".equals(remoteState)) break;
                if (!"offered".equals(remoteState))
                    throw new IOException("Unexpected remote Offer state");
            } catch (IOException retryable) {
                // A missing route or transient socket failure does not alter the immutable Offer.
            }
            if (pauseRequested.get()) { notifyPausedAtBoundary(); return; }
            sleepBackoff(backoff);
            backoff = Math.min(MAX_BACKOFF_MS, backoff * 2L);
        }
        if (cancelled.get()) { cancelLedger(); return; }
        if (pauseRequested.get()) { notifyPausedAtBoundary(); return; }
        if (acknowledgement == null
                || !("accepted".equals(acknowledgement.optString("state"))
                    || "transferring".equals(acknowledgement.optString("state"))
                    || "completed".equals(acknowledgement.optString("state")))) {
            notifyState("waiting_for_acceptance");
            return; // Durable local Offer remains available for a later retry.
        }

        advanceLocalToTransferring();
        if ("completed".equals(acknowledgement.optString("state"))) {
            store.markSenderCompleted(offer.transferId, localPeerId, System.currentTimeMillis());
            notifyState("completed");
            return;
        }

        backoff = 1000L;
        for (int session = 0; session < maxSessions && !cancelled.get() && !pauseRequested.get(); session++) {
            try {
                Network network = networks.currentWifiNetwork();
                if (network == null) throw new IOException("No selected Wi-Fi LAN network");
                List<Integer> missing = LanTransferTcpClient.connectAndSend(network,
                        remoteHost, remotePort, source, offer, localPeerId, offer.targetPeerId,
                        credentials.sharedSecret, chunkSizeBytes,
                        LanTransferTcpClient.DEFAULT_CHUNKS_PER_SESSION, 1024 * 1024,
                        (int) CONNECT_TIMEOUT_MS,
                        (sent, batch) -> { if (listener != null) listener.onProgress(sent, batch); });
                if (missing == null) throw new IOException("Sender returned no transfer result");
                if (missing.isEmpty()) {
                    store.markSenderCompleted(offer.transferId, localPeerId, System.currentTimeMillis());
                    notifyState("completed");
                    return;
                }
                // The next authenticated session re-queries the receiver's current missing page.
                backoff = 1000L;
            } catch (IOException retryable) {
                if (pauseRequested.get()) { notifyPausedAtBoundary(); break; }
                pauseIfTransferring();
                notifyState("paused");
                sleepBackoff(backoff);
                if (pauseRequested.get()) break;
                backoff = Math.min(MAX_BACKOFF_MS, backoff * 2L);
                advanceLocalToTransferring();
            }
        }
        if (cancelled.get()) cancelLedger();
        else if (pauseRequested.get()) notifyPausedAtBoundary();
        else pauseIfTransferring();
    }

    private LanTransferStore.Transfer requireLocalTask() {
        LanTransferStore.Transfer task = store.getTransfer(offer.transferId);
        if (task == null || !localPeerId.equals(task.offer.senderPeerId)
                || !offer.targetPeerId.equals(task.offer.targetPeerId)
                || !offer.sha256.equals(task.offer.sha256))
            throw new SecurityException("Outgoing task identity or immutable Offer mismatch");
        return task;
    }

    private void advanceLocalToTransferring() {
        String state = requireLocalTask().state;
        long now = System.currentTimeMillis();
        if ("offered".equals(state)) {
            store.transition(offer.transferId, "accepted", now);
            state = "accepted";
        }
        if ("accepted".equals(state) || "paused".equals(state))
            store.transition(offer.transferId, "transferring", now);
        notifyState("transferring");
    }

    private void pauseIfTransferring() {
        try {
            String state = requireLocalTask().state;
            if ("accepted".equals(state) || "transferring".equals(state))
                store.transition(offer.transferId, "paused", System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    private void notifyPausedAtBoundary() {
        if (cancelled.get()) { cancelLedger(); return; }
        try {
            String state = requireLocalTask().state;
            if ("completed".equals(state) || "cancelled".equals(state) || "failed".equals(state)
                    || "rejected".equals(state) || "expired".equals(state)) return;
            if ("offered".equals(state)) {
                notifyState("waiting_for_acceptance");
                return;
            }
            pauseIfTransferring();
            notifyState("paused");
        } catch (Exception ignored) {}
    }

    private void failIfTransferring() {
        try {
            String state = requireLocalTask().state;
            if ("offered".equals(state) || "accepted".equals(state)
                    || "transferring".equals(state) || "paused".equals(state))
                store.transition(offer.transferId, "failed", System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    private void cancelLedger() {
        try {
            String state = requireLocalTask().state;
            if ("offered".equals(state) || "accepted".equals(state)
                    || "transferring".equals(state) || "paused".equals(state))
                store.transition(offer.transferId, "cancelled", System.currentTimeMillis());
        } catch (Exception ignored) {}
        notifyState("cancelled");
    }

    private void sleepBackoff(long delayMs) throws InterruptedException {
        long remaining = Math.max(250L, Math.min(MAX_BACKOFF_MS, delayMs));
        while (remaining > 0L && !cancelled.get() && !pauseRequested.get()) {
            long slice = Math.min(250L, remaining);
            Thread.sleep(slice);
            remaining -= slice;
        }
    }

    private void notifyState(String state) {
        if (listener != null) listener.onState(state);
    }
}
