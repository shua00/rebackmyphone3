package com.example.devicefinder;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Role-aware P-256 pairing handshake over the dedicated control protocol.
 * It only derives provisional material; callers must show the SAS on both devices
 * and require both local confirmations before persisting the peer secret.
 */
public final class LanTransferPairingHandshake {
    public static final class InitiatorStart {
        public final LanTransferPairingControlProtocol.Message request;
        private final LanTransferPairingCrypto.Ephemeral ephemeral;
        private InitiatorStart(LanTransferPairingControlProtocol.Message request,
                LanTransferPairingCrypto.Ephemeral ephemeral) {
            this.request = request;
            this.ephemeral = ephemeral;
        }
    }

    public static final class ResponderResult {
        public final LanTransferPairingControlProtocol.Message response;
        public final LanTransferPairingCrypto.Result material;
        private ResponderResult(LanTransferPairingControlProtocol.Message response,
                LanTransferPairingCrypto.Result material) {
            this.response = response;
            this.material = material;
        }
    }

    private LanTransferPairingHandshake() {}

    public static InitiatorStart begin(String localPeerId, String remotePeerId,
            String sessionId, long sequence) throws GeneralSecurityException, IOException {
        requireIdentities(localPeerId, remotePeerId, sessionId, sequence);
        LanTransferPairingCrypto.Ephemeral ephemeral = LanTransferPairingCrypto.createEphemeral();
        byte[] payload = LanTransferPairingExchangePayload.encode(
                new LanTransferPairingExchangePayload.Material(ephemeral.publicKey(), ephemeral.nonce()));
        LanTransferPairingControlProtocol.Message request = new LanTransferPairingControlProtocol.Message(
                LanTransferPairingControlProtocol.Type.REQUEST, sessionId,
                localPeerId, remotePeerId, sequence, payload);
        return new InitiatorStart(request, ephemeral);
    }

    public static InitiatorStart begin(String localPeerId, String remotePeerId, long sequence)
            throws GeneralSecurityException, IOException {
        return begin(localPeerId, remotePeerId, UUID.randomUUID().toString(), sequence);
    }

    public static ResponderResult respond(String localPeerId,
            LanTransferPairingControlProtocol.Message request, long nowElapsedMs)
            throws GeneralSecurityException, IOException {
        if (request == null || request.type != LanTransferPairingControlProtocol.Type.REQUEST
                || !localPeerId.equals(request.targetPeerId)
                || localPeerId.equals(request.senderPeerId))
            throw new IOException("Pairing request type or peer identity mismatch");
        LanTransferPairingExchangePayload.Material remote =
                LanTransferPairingExchangePayload.decode(request.payload);
        LanTransferPairingCrypto.Ephemeral ephemeral = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Result material = LanTransferPairingCrypto.deriveAsResponder(
                request.senderPeerId, localPeerId, ephemeral,
                remote.publicKey(), remote.nonce());
        byte[] payload = LanTransferPairingExchangePayload.encode(
                new LanTransferPairingExchangePayload.Material(ephemeral.publicKey(), ephemeral.nonce()));
        LanTransferPairingControlProtocol.Message response = new LanTransferPairingControlProtocol.Message(
                LanTransferPairingControlProtocol.Type.RESPONSE, request.sessionId,
                localPeerId, request.senderPeerId, request.sequence, payload);
        return new ResponderResult(response, material);
    }

    public static LanTransferPairingCrypto.Result completeInitiator(InitiatorStart start,
            LanTransferPairingControlProtocol.Message response)
            throws GeneralSecurityException, IOException {
        if (start == null || response == null
                || response.type != LanTransferPairingControlProtocol.Type.RESPONSE
                || !response.sessionId.equals(start.request.sessionId)
                || !response.senderPeerId.equals(start.request.targetPeerId)
                || !response.targetPeerId.equals(start.request.senderPeerId)
                || response.sequence != start.request.sequence)
            throw new IOException("Pairing response type, session, sequence, or identity mismatch");
        LanTransferPairingExchangePayload.Material remote =
                LanTransferPairingExchangePayload.decode(response.payload);
        return LanTransferPairingCrypto.deriveAsInitiator(
                start.request.senderPeerId, start.request.targetPeerId,
                start.ephemeral, remote.publicKey(), remote.nonce());
    }

    private static void requireIdentities(String local, String remote, String session, long sequence) {
        if (local == null || !local.matches("[A-Za-z0-9._:-]{1,128}")
                || remote == null || !remote.matches("[A-Za-z0-9._:-]{1,128}")
                || local.equals(remote) || session == null
                || !UUID.fromString(session).toString().equalsIgnoreCase(session)
                || sequence < 1L)
            throw new IllegalArgumentException("Invalid pairing handshake identity or sequence");
    }
}
