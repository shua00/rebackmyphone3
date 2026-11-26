package com.example.devicefinder;

import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Initiator-side provisional pairing ceremony; durable commit belongs to the later coordinator. */
public final class LanTransferPairingInitiator implements AutoCloseable {
    private final String localPeerId, remotePeerId, sessionId, sas;
    private final InetAddress address;
    private final int port;
    private final LanTransferPairingSession session;
    private long nextSequence = 2L;
    private boolean closed;

    private LanTransferPairingInitiator(String localPeerId, String remotePeerId,
            InetAddress address, int port, LanTransferPairingCrypto.Result material,
            String sessionId, long nowElapsedMs) {
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId;
        this.address = address;
        this.port = port;
        this.sas = material.shortAuthenticationCode;
        this.sessionId = sessionId;
        this.session = new LanTransferPairingSession(localPeerId, remotePeerId,
                LanTransferPairingSession.Role.INITIATOR, material, nowElapsedMs,
                LanTransferPairingControlHost.SESSION_TIMEOUT_MS);
    }

    public static LanTransferPairingInitiator connect(String localPeerId, String remotePeerId,
            InetAddress address, int port, int connectTimeoutMs, int readTimeoutMs)
            throws GeneralSecurityException, IOException {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()
                || port < 1 || port > 65535)
            throw new IllegalArgumentException("A concrete pairing endpoint is required");
        String sessionId = UUID.randomUUID().toString();
        LanTransferPairingHandshake.InitiatorStart start =
                LanTransferPairingHandshake.begin(localPeerId, remotePeerId, sessionId, 1L);
        LanTransferPairingControlProtocol.Message response =
                LanTransferPairingControlTransport.exchange(address, port, start.request,
                        connectTimeoutMs, readTimeoutMs);
        LanTransferPairingCrypto.Result material =
                LanTransferPairingHandshake.completeInitiator(start, response);
        return new LanTransferPairingInitiator(localPeerId, remotePeerId, address, port,
                material, sessionId, nowElapsedMs());
    }

    public String sessionId() { return sessionId; }
    public String remotePeerId() { return remotePeerId; }
    public String shortAuthenticationCode() { return sas; }
    public synchronized LanTransferPairingSession.State state() {
        ensureOpen();
        return session.state(nowElapsedMs());
    }

    public synchronized LanTransferPairingSession.State confirmLocal(String suppliedSas,
            int connectTimeoutMs, int readTimeoutMs)
            throws GeneralSecurityException, IOException {
        ensureOpen();
        LanTransferPairingSession.State current = session.state(nowElapsedMs());
        if (current == LanTransferPairingSession.State.WAITING_LOCAL_CONFIRMATION) {
            session.confirmLocal(suppliedSas, nowElapsedMs());
            if (session.state(nowElapsedMs()) == LanTransferPairingSession.State.REJECTED)
                throw new SecurityException("Local SAS did not match the derived pairing code");
        } else if (current != LanTransferPairingSession.State.WAITING_REMOTE_CONFIRMATION
                && current != LanTransferPairingSession.State.READY_TO_COMMIT) {
            throw new IllegalStateException("Pairing session is not confirmable");
        }
        return sendConfirmation(connectTimeoutMs, readTimeoutMs);
    }

    /** Retry with a fresh transport sequence while retaining the same transcript proof. */
    public synchronized LanTransferPairingSession.State retryConfirmation(
            int connectTimeoutMs, int readTimeoutMs)
            throws GeneralSecurityException, IOException {
        ensureOpen();
        LanTransferPairingSession.State current = session.state(nowElapsedMs());
        if (current != LanTransferPairingSession.State.WAITING_REMOTE_CONFIRMATION
                && current != LanTransferPairingSession.State.READY_TO_COMMIT)
            throw new IllegalStateException("Pairing session is not awaiting peer confirmation");
        return sendConfirmation(connectTimeoutMs, readTimeoutMs);
    }

    private LanTransferPairingSession.State sendConfirmation(int connectTimeoutMs,
            int readTimeoutMs) throws GeneralSecurityException, IOException {
        byte[] proof = session.createLocalConfirmationProof(nowElapsedMs());
        long sequence = nextSequence++;
        try {
            LanTransferPairingControlProtocol.Message request =
                    new LanTransferPairingControlProtocol.Message(
                            LanTransferPairingControlProtocol.Type.CONFIRM, sessionId,
                            localPeerId, remotePeerId, sequence, proof);
            LanTransferPairingControlProtocol.Message ack =
                    LanTransferPairingControlTransport.exchange(address, port, request,
                            connectTimeoutMs, readTimeoutMs);
            if (ack.payload[0] == 1 || ack.payload[0] == 2) {
                byte[] remoteProof = Arrays.copyOfRange(ack.payload, 1, ack.payload.length);
                try { session.applyRemoteConfirmation(ack.payload[0] == 1, remoteProof, nowElapsedMs()); }
                finally { Arrays.fill(remoteProof, (byte) 0); }
            } else if (ack.payload[0] == 3) {
                session.cancel(nowElapsedMs());
            }
            return session.state(nowElapsedMs());
        } finally {
            Arrays.fill(proof, (byte) 0);
        }
    }

    /** Copies the provisional secret only after both SAS decisions have been authenticated. */
    public synchronized byte[] copySecretForCommit() {
        ensureOpen();
        if (session.state(nowElapsedMs()) != LanTransferPairingSession.State.READY_TO_COMMIT)
            throw new IllegalStateException("Both pairing confirmations are required before commit");
        return session.peerSecretForCommit(nowElapsedMs());
    }

    /** Send an authenticated durable commit; caller owns encrypted journaling and finalization. */
    public synchronized void sendCommit(byte[] peerSecret, int connectTimeoutMs, int readTimeoutMs)
            throws GeneralSecurityException, IOException {
        ensureOpen();
        if (session.state(nowElapsedMs()) != LanTransferPairingSession.State.READY_TO_COMMIT)
            throw new IllegalStateException("Pairing session is not ready for durable commit");
        LanTransferPairingCommitProof.sendCommit(address, port, localPeerId, remotePeerId,
                sessionId, peerSecret, nextSequence++, connectTimeoutMs, readTimeoutMs);
    }

    public synchronized void rejectLocal(int connectTimeoutMs, int readTimeoutMs)
            throws GeneralSecurityException, IOException {
        ensureOpen();
        byte[] proof = session.rejectLocal(nowElapsedMs());
        long sequence = nextSequence++;
        try {
            LanTransferPairingControlProtocol.Message request =
                    new LanTransferPairingControlProtocol.Message(
                            LanTransferPairingControlProtocol.Type.REJECT, sessionId,
                            localPeerId, remotePeerId, sequence, proof);
            LanTransferPairingControlTransport.exchange(address, port, request,
                    connectTimeoutMs, readTimeoutMs);
        } finally {
            Arrays.fill(proof, (byte) 0);
            closed = true;
        }
    }

    public synchronized void cancel(int connectTimeoutMs, int readTimeoutMs) {
        if (closed) return;
        long sequence = nextSequence++;
        try {
            LanTransferPairingControlProtocol.Message request =
                    new LanTransferPairingControlProtocol.Message(
                            LanTransferPairingControlProtocol.Type.CANCEL, sessionId,
                            localPeerId, remotePeerId, sequence, new byte[0]);
            LanTransferPairingControlTransport.exchange(address, port, request,
                    connectTimeoutMs, readTimeoutMs);
        } catch (Exception ignored) {
            // Local cancellation is authoritative locally; the remote session expires otherwise.
        } finally {
            session.cancel(nowElapsedMs());
            closed = true;
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Pairing initiator is closed");
    }
    private static long nowElapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
    @Override public synchronized void close() {
        if (closed) return;
        session.cancel(nowElapsedMs());
        closed = true;
    }
}
