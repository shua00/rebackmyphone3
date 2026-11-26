package com.example.devicefinder;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service-owned responder for the independent pairing control plane.
 * It holds provisional sessions in memory only; it never persists credentials.
 */
public final class LanTransferPairingControlHost implements AutoCloseable {
    public interface Listener {
        void onPairingRequested(String sessionId, String remotePeerId, String sas,
                long expiresAtElapsedMs);
        default void onPairingStateChanged(String sessionId, String remotePeerId,
                LanTransferPairingSession.State state) { }
        default boolean onPairingReady(String sessionId, String remotePeerId,
                byte[] peerSecret, byte[] localProof, byte[] remoteProof,
                long expiresAtElapsedMs) { return true; }
        default boolean onPairingCommitRequested(String sessionId, String remotePeerId,
                byte[] peerSecret) throws Exception { return false; }
        default LanTransferPairingControlProtocol.Message onPairingRecoveryMessage(
                LanTransferPairingControlProtocol.Message request) throws Exception { return null; }
    }

    public static final long SESSION_TIMEOUT_MS = 120_000L;

    public static final class Pending {
        public final String sessionId;
        public final String remotePeerId;
        public final String sas;
        public final long expiresAtElapsedMs;
        public final LanTransferPairingSession.State state;

        private Pending(String sessionId, String remotePeerId, String sas,
                long expiresAtElapsedMs, LanTransferPairingSession.State state) {
            this.sessionId = sessionId;
            this.remotePeerId = remotePeerId;
            this.sas = sas;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
            this.state = state;
        }
    }

    private static final class Entry {
        final String key;
        final String sessionId;
        final String remotePeerId;
        final String sas;
        final long expiresAtElapsedMs;
        final LanTransferPairingSession session;
        volatile byte[] terminalAckPayload;
        volatile byte[] remoteConfirmationProof;

        Entry(String key, String sessionId, String remotePeerId, String sas,
                long expiresAtElapsedMs, LanTransferPairingSession session) {
            this.key = key;
            this.sessionId = sessionId;
            this.remotePeerId = remotePeerId;
            this.sas = sas;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
            this.session = session;
        }
    }

    private final String localPeerId;
    private final Listener listener;
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    private volatile LanTransferPairingControlTransport.Server server;
    private volatile boolean closed;

    public LanTransferPairingControlHost(String localPeerId, Listener listener) {
        if (localPeerId == null || !localPeerId.matches("[A-Za-z0-9._:-]{1,128}") || listener == null)
            throw new IllegalArgumentException("Local peer identity and listener are required");
        this.localPeerId = localPeerId;
        this.listener = listener;
    }

    public synchronized void start(InetAddress bindAddress, int port) throws java.io.IOException {
        if (closed || server != null) throw new IllegalStateException("Pairing host is not startable");
        LanTransferPairingControlTransport.Server created =
                new LanTransferPairingControlTransport.Server(localPeerId, this::handle, 120_000L);
        try {
            created.start(bindAddress, port);
            server = created;
        } catch (java.io.IOException | RuntimeException failure) {
            created.close();
            throw failure;
        }
    }

    public boolean isRunning() {
        LanTransferPairingControlTransport.Server current = server;
        return !closed && current != null && current.isRunning();
    }

    public int getBoundPort() {
        LanTransferPairingControlTransport.Server current = server;
        return current == null ? -1 : current.getBoundPort();
    }

    public LanTransferPairingSession.State state(String sessionId, String remotePeerId) {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        return entry == null ? null : entry.session.state(nowElapsedMs());
    }

    public List<Pending> pendingSessions() {
        long now = nowElapsedMs();
        pruneExpired(now);
        List<Pending> result = new ArrayList<>();
        for (Entry entry : pending.values()) {
            LanTransferPairingSession.State state = entry.session.state(now);
            if (state != LanTransferPairingSession.State.REJECTED
                    && state != LanTransferPairingSession.State.CANCELLED
                    && state != LanTransferPairingSession.State.EXPIRED)
                result.add(new Pending(entry.sessionId, entry.remotePeerId, entry.sas,
                        entry.expiresAtElapsedMs, state));
        }
        return Collections.unmodifiableList(result);
    }

    /** Local SAS decision only changes provisional in-memory state; it does not commit credentials. */
    public LanTransferPairingSession.State confirmLocal(String sessionId, String remotePeerId,
            String sas) {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        entry.session.confirmLocal(sas, nowElapsedMs());
        LanTransferPairingSession.State state = entry.session.state(nowElapsedMs());
        if (state == LanTransferPairingSession.State.REJECTED
                || state == LanTransferPairingSession.State.EXPIRED
                || state == LanTransferPairingSession.State.CANCELLED)
            pending.remove(entry.key);
        return state;
    }

    public void cancelPending(String sessionId, String remotePeerId) {
        Entry entry = pending.remove(key(sessionId, remotePeerId));
        if (entry != null) {
            entry.session.cancel(nowElapsedMs());
            if (entry.remoteConfirmationProof != null) java.util.Arrays.fill(entry.remoteConfirmationProof, (byte) 0);
        }
    }

    /** Retain a short-lived cancellation tombstone so the initiator learns the decision on retry. */
    public void cancelPendingForRemote(String sessionId, String remotePeerId) {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null || entry.terminalAckPayload != null) return;
        entry.session.cancel(nowElapsedMs());
        if (entry.remoteConfirmationProof != null) java.util.Arrays.fill(entry.remoteConfirmationProof, (byte) 0);
        entry.terminalAckPayload = new byte[]{3};
    }

    /** Retain the transcript-bound rejection proof until the initiator's next retry. */
    public void rejectLocalForRemote(String sessionId, String remotePeerId)
            throws java.security.GeneralSecurityException {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        if (entry.terminalAckPayload != null
                || entry.session.state(nowElapsedMs()) == LanTransferPairingSession.State.REJECTED) return;
        byte[] proof = entry.session.rejectLocal(nowElapsedMs());
        byte[] terminal = new byte[33];
        terminal[0] = 2;
        System.arraycopy(proof, 0, terminal, 1, proof.length);
        java.util.Arrays.fill(proof, (byte) 0);
        entry.terminalAckPayload = terminal;
        if (entry.remoteConfirmationProof != null) java.util.Arrays.fill(entry.remoteConfirmationProof, (byte) 0);
    }

    /**
     * Records an explicit local rejection and returns its transcript-bound proof for delivery
     * to the initiator. The provisional session is destroyed immediately; callers must send
     * the returned proof before treating the remote side as informed.
     */
    public byte[] rejectLocalProof(String sessionId, String remotePeerId)
            throws java.security.GeneralSecurityException {
        String entryKey = key(sessionId, remotePeerId);
        Entry entry = pending.get(entryKey);
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        byte[] proof = entry.session.rejectLocal(nowElapsedMs());
        if (!pending.remove(entryKey, entry)) {
            java.util.Arrays.fill(proof, (byte) 0);
            throw new IllegalStateException("Pairing session changed before rejection handoff");
        }
        if (entry.remoteConfirmationProof != null) java.util.Arrays.fill(entry.remoteConfirmationProof, (byte) 0);
        return proof;
    }

    /** Non-destructive copies used by the durable commit coordinator while the session is READY. */
    public byte[] copySecretForCommit(String sessionId, String remotePeerId) {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        return entry.session.peerSecretForCommit(nowElapsedMs());
    }

    public byte[] copyLocalConfirmationProof(String sessionId, String remotePeerId)
            throws java.security.GeneralSecurityException {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        return entry.session.createLocalConfirmationProof(nowElapsedMs());
    }

    public byte[] copyRemoteConfirmationProof(String sessionId, String remotePeerId) {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null || entry.remoteConfirmationProof == null)
            throw new IllegalStateException("Remote confirmation proof is not available");
        return entry.remoteConfirmationProof.clone();
    }

    /** Records the local SAS decision and returns its transcript-bound proof for the peer. */
    public byte[] confirmLocalProof(String sessionId, String remotePeerId, String sas)
            throws java.security.GeneralSecurityException {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        LanTransferPairingSession.State state = confirmLocal(sessionId, remotePeerId, sas);
        if (state != LanTransferPairingSession.State.WAITING_REMOTE_CONFIRMATION
                && state != LanTransferPairingSession.State.READY_TO_COMMIT)
            throw new IllegalStateException("Local confirmation was rejected or session is not awaiting peer confirmation");
        return entry.session.createLocalConfirmationProof(nowElapsedMs());
    }

    /** Applies the peer's authenticated decision; a rejection permanently removes the provisional session. */
    public LanTransferPairingSession.State applyRemoteConfirmation(String sessionId,
            String remotePeerId, boolean accepted, byte[] proof)
            throws java.security.GeneralSecurityException {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        long now = nowElapsedMs();
        entry.session.applyRemoteConfirmation(accepted, proof, now);
        if (accepted && entry.session.state(now) != LanTransferPairingSession.State.REJECTED) {
            byte[] oldProof = entry.remoteConfirmationProof;
            if (oldProof != null) java.util.Arrays.fill(oldProof, (byte) 0);
            entry.remoteConfirmationProof = proof.clone();
        }
        LanTransferPairingSession.State state = entry.session.state(now);
        if (state == LanTransferPairingSession.State.REJECTED
                || state == LanTransferPairingSession.State.EXPIRED
                || state == LanTransferPairingSession.State.CANCELLED)
            pending.remove(entry.key, entry);
        return state;
    }

    /**
     * One-shot secret handoff for the credential coordinator. It is impossible before both
     * authenticated user confirmations; the provisional session is destroyed after handoff.
     */
    public byte[] takeSecretForCommit(String sessionId, String remotePeerId) {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        long now = nowElapsedMs();
        byte[] secret = entry.session.peerSecretForCommit(now);
        if (!pending.remove(entry.key, entry)) {
            java.util.Arrays.fill(secret, (byte) 0);
            throw new IllegalStateException("Pairing session changed before credential handoff");
        }
        entry.session.cancel(now);
        if (entry.remoteConfirmationProof != null) java.util.Arrays.fill(entry.remoteConfirmationProof, (byte) 0);
        return secret;
    }

    private LanTransferPairingControlProtocol.Message handle(
            LanTransferPairingControlProtocol.Message request) throws Exception {
        if (closed) return null;
        long now = nowElapsedMs();
        pruneExpired(now);
        if (request.type != LanTransferPairingControlProtocol.Type.REQUEST) {
            String controlKey = key(request.sessionId, request.senderPeerId);
            Entry active = pending.get(controlKey);
            if (active == null) {
                if (request.type == LanTransferPairingControlProtocol.Type.COMMIT
                        || request.type == LanTransferPairingControlProtocol.Type.CONFIRM)
                    return listener.onPairingRecoveryMessage(request);
                return null;
            }
            byte[] terminal = active.terminalAckPayload;
            if (terminal != null && request.type == LanTransferPairingControlProtocol.Type.CONFIRM) {
                return new LanTransferPairingControlProtocol.Message(
                        LanTransferPairingControlProtocol.Type.ACK, request.sessionId,
                        localPeerId, request.senderPeerId, request.sequence, terminal.clone());
            }
            if (request.type == LanTransferPairingControlProtocol.Type.COMMIT) {
                byte[] secret = active.session.peerSecretForCommit(now);
                try {
                    if (!LanTransferPairingCommitProof.verify(secret, request.sessionId,
                            request.senderPeerId, localPeerId, "COMMIT", request.payload))
                        throw new SecurityException("Pairing commit proof is invalid");
                    if (!listener.onPairingCommitRequested(active.sessionId,
                            active.remotePeerId, secret)) return null;
                    byte[] acknowledgement = LanTransferPairingCommitProof.create(secret,
                            request.sessionId, localPeerId, request.senderPeerId, "COMMIT_ACK");
                    pending.remove(active.key, active);
                    active.session.cancel(now);
                    byte[] remoteProof = active.remoteConfirmationProof;
                    if (remoteProof != null) java.util.Arrays.fill(remoteProof, (byte) 0);
                    LanTransferPairingControlProtocol.Message response =
                            new LanTransferPairingControlProtocol.Message(
                                    LanTransferPairingControlProtocol.Type.COMMIT_ACK,
                                    request.sessionId, localPeerId, request.senderPeerId,
                                    request.sequence, acknowledgement);
                    java.util.Arrays.fill(acknowledgement, (byte) 0);
                    return response;
                } finally { java.util.Arrays.fill(secret, (byte) 0); }
            }
            if (request.type == LanTransferPairingControlProtocol.Type.CONFIRM
                    || request.type == LanTransferPairingControlProtocol.Type.REJECT) {
                boolean accepted = request.type == LanTransferPairingControlProtocol.Type.CONFIRM;
                active.session.applyRemoteConfirmation(accepted, request.payload, now);
                if (accepted && active.session.state(now) != LanTransferPairingSession.State.REJECTED) {
                    byte[] oldProof = active.remoteConfirmationProof;
                    if (oldProof != null) java.util.Arrays.fill(oldProof, (byte) 0);
                    active.remoteConfirmationProof = request.payload.clone();
                }
                LanTransferPairingSession.State state = active.session.state(now);
                if (state == LanTransferPairingSession.State.REJECTED
                        || state == LanTransferPairingSession.State.EXPIRED
                        || state == LanTransferPairingSession.State.CANCELLED) {
                    pending.remove(controlKey, active);
                }
            } else if (request.type == LanTransferPairingControlProtocol.Type.CANCEL) {
                pending.remove(controlKey, active);
                active.session.cancel(now);
            } else {
                return null;
            }
            LanTransferPairingSession.State updatedState = active.session.state(now);
            try { listener.onPairingStateChanged(active.sessionId, active.remotePeerId, updatedState); }
            catch (RuntimeException ignored) { }
            boolean ready = updatedState == LanTransferPairingSession.State.READY_TO_COMMIT;
            byte[] acknowledgement;
            if (ready) {
                byte[] secret = active.session.peerSecretForCommit(now);
                byte[] proof = active.session.createLocalConfirmationProof(now);
                byte[] remoteProof = active.remoteConfirmationProof;
                boolean staged = false;
                try {
                    staged = listener.onPairingReady(active.sessionId, active.remotePeerId,
                            secret, proof, remoteProof,
                            active.expiresAtElapsedMs);
                } catch (Exception persistenceFailure) {
                    staged = false;
                } finally {
                    java.util.Arrays.fill(secret, (byte) 0);
                    java.util.Arrays.fill(proof, (byte) 0);
                }
                if (staged) {
                    acknowledgement = new byte[33];
                    acknowledgement[0] = 1;
                    byte[] responseProof = active.session.createLocalConfirmationProof(now);
                    System.arraycopy(responseProof, 0, acknowledgement, 1, responseProof.length);
                    java.util.Arrays.fill(responseProof, (byte) 0);
                } else {
                    acknowledgement = new byte[]{0};
                }
            } else {
                acknowledgement = new byte[]{0};
            }
            return new LanTransferPairingControlProtocol.Message(
                    LanTransferPairingControlProtocol.Type.ACK, request.sessionId,
                    localPeerId, request.senderPeerId, request.sequence, acknowledgement);
        }
        String entryKey = key(request.sessionId, request.senderPeerId);
        if (pending.containsKey(entryKey)) return null;
        LanTransferPairingHandshake.ResponderResult result =
                LanTransferPairingHandshake.respond(localPeerId, request, now);
        LanTransferPairingSession session = new LanTransferPairingSession(localPeerId,
                request.senderPeerId, LanTransferPairingSession.Role.RESPONDER,
                result.material, now, SESSION_TIMEOUT_MS);
        Entry entry = new Entry(entryKey, request.sessionId, request.senderPeerId,
                result.material.shortAuthenticationCode, now + SESSION_TIMEOUT_MS, session);
        if (closed || pending.putIfAbsent(entryKey, entry) != null) {
            session.cancel(nowElapsedMs());
            return null;
        }
        try {
            listener.onPairingRequested(entry.sessionId, entry.remotePeerId,
                    entry.sas, entry.expiresAtElapsedMs);
        } catch (RuntimeException ignored) {
            pending.remove(entryKey);
            session.cancel(nowElapsedMs());
            throw ignored;
        }
        return result.response;
    }

    private void pruneExpired(long now) {
        for (Entry entry : pending.values()) {
            if (now >= entry.expiresAtElapsedMs
                    || entry.session.state(now) == LanTransferPairingSession.State.EXPIRED) {
                pending.remove(entry.key, entry);
                byte[] terminal = entry.terminalAckPayload;
                if (terminal != null) java.util.Arrays.fill(terminal, (byte) 0);
                byte[] remoteProof = entry.remoteConfirmationProof;
                if (remoteProof != null) java.util.Arrays.fill(remoteProof, (byte) 0);
            }
        }
    }

    private static String key(String sessionId, String peerId) {
        if (sessionId == null || peerId == null) throw new IllegalArgumentException("Pairing key is required");
        return sessionId + "\u0000" + peerId;
    }

    private static long nowElapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        LanTransferPairingControlTransport.Server current = server;
        server = null;
        if (current != null) current.close();
        long now = nowElapsedMs();
        for (Entry entry : pending.values()) {
            entry.session.cancel(now);
            byte[] terminal = entry.terminalAckPayload;
            if (terminal != null) java.util.Arrays.fill(terminal, (byte) 0);
            byte[] remoteProof = entry.remoteConfirmationProof;
            if (remoteProof != null) java.util.Arrays.fill(remoteProof, (byte) 0);
        }
        pending.clear();
    }
}
