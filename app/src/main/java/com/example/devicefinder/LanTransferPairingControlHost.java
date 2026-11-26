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

    public List<Pending> pendingSessions() {
        long now = nowElapsedMs();
        pruneExpired(now);
        List<Pending> result = new ArrayList<>();
        for (Entry entry : pending.values()) {
            result.add(new Pending(entry.sessionId, entry.remotePeerId, entry.sas,
                    entry.expiresAtElapsedMs, entry.session.state(now)));
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
        if (entry != null) entry.session.cancel(nowElapsedMs());
    }

    /** Records the local SAS decision and returns its transcript-bound proof for the peer. */
    public byte[] confirmLocalProof(String sessionId, String remotePeerId, String sas)
            throws java.security.GeneralSecurityException {
        Entry entry = pending.get(key(sessionId, remotePeerId));
        if (entry == null) throw new IllegalArgumentException("Unknown or expired pairing session");
        LanTransferPairingSession.State state = confirmLocal(sessionId, remotePeerId, sas);
        if (state != LanTransferPairingSession.State.WAITING_REMOTE_CONFIRMATION)
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
        return secret;
    }

    private LanTransferPairingControlProtocol.Message handle(
            LanTransferPairingControlProtocol.Message request) throws Exception {
        if (closed || request.type != LanTransferPairingControlProtocol.Type.REQUEST) return null;
        long now = nowElapsedMs();
        pruneExpired(now);
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
            if (entry.session.state(now) == LanTransferPairingSession.State.EXPIRED)
                pending.remove(entry.key);
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
        for (Entry entry : pending.values()) entry.session.cancel(now);
        pending.clear();
    }
}
