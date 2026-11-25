package com.example.devicefinder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;

/**
 * Bounded Android TCP receiver for the LNP2 data plane.
 *
 * Socket admission/listener ownership stays with the service layer. This class
 * authenticates one accepted socket, binds every frame to the authenticated
 * peer and immutable Offer participants, and only ACKs chunks after the
 * filesystem has synced bytes and committed the durable receipt.
 */
public final class LanTransferTcpSession {
    public interface Clock { long nowMs(); }

    public static final int DEFAULT_MAX_FRAMES = 64;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;
    public static final int MAX_MISSING_PAGE = 128;

    private final LanTransferStore store;
    private final LanTransferFilesystem filesystem;
    private final String localPeerId;
    private final String sharedSecret;
    private final LanTransferAuthenticator.TrustedPeerAuthorizer authorizer;
    private final Clock clock;
    private final int maxFrames;
    private final int maxPayloadBytes;
    private final int socketTimeoutMs;

    public LanTransferTcpSession(Context context, LanTransferStore store,
            LanTransferFilesystem filesystem, String localPeerId, String sharedSecret,
            LanTransferAuthenticator.TrustedPeerAuthorizer authorizer, Clock clock) {
        this(context, store, filesystem, localPeerId, sharedSecret, authorizer, clock,
                DEFAULT_MAX_FRAMES, DEFAULT_MAX_PAYLOAD_BYTES, 15000);
    }

    public LanTransferTcpSession(Context context, LanTransferStore store,
            LanTransferFilesystem filesystem, String localPeerId, String sharedSecret,
            LanTransferAuthenticator.TrustedPeerAuthorizer authorizer, Clock clock,
            int maxFrames, int maxPayloadBytes, int socketTimeoutMs) {
        if (context == null || store == null || filesystem == null || localPeerId == null
                || localPeerId.isEmpty() || sharedSecret == null || sharedSecret.isEmpty()
                || authorizer == null || clock == null)
            throw new IllegalArgumentException("Transfer session dependencies are required");
        if (maxFrames < 1 || maxFrames > 256 || maxPayloadBytes < 1
                || maxPayloadBytes > DEFAULT_MAX_PAYLOAD_BYTES || socketTimeoutMs < 1000)
            throw new IllegalArgumentException("Invalid transfer session limits");
        this.store = store;
        this.filesystem = filesystem;
        this.localPeerId = localPeerId;
        this.sharedSecret = sharedSecret;
        this.authorizer = authorizer;
        this.clock = clock;
        this.maxFrames = maxFrames;
        this.maxPayloadBytes = maxPayloadBytes;
        this.socketTimeoutMs = socketTimeoutMs;
    }

    /** Serves at most maxFrames requests. The caller owns and closes the socket. */
    public int serve(Socket socket) throws IOException, GeneralSecurityException {
        if (socket == null || socket.isClosed()) throw new IllegalArgumentException("Connected socket required");
        socket.setSoTimeout(socketTimeoutMs);
        LanTransferAuthenticator.AuthenticatedPeer peer =
                LanTransferAuthenticator.authenticateServer(socket, localPeerId, sharedSecret, authorizer);
        int handled = 0;
        while (handled < maxFrames && !socket.isClosed()) {
            final LanTransferFrameCodec.Frame frame;
            try {
                frame = LanTransferFrameCodec.receive(socket, peer.sessionKey, maxPayloadBytes,
                        peer.peerId, localPeerId);
            } catch (java.io.EOFException closed) {
                return handled;
            } catch (IOException closed) {
                if (socket.isClosed()) return handled;
                throw closed;
            } catch (Exception invalid) {
                if (invalid instanceof SecurityException) throw (SecurityException) invalid;
                throw new IOException("Invalid LNP2 frame", invalid);
            }
            LanTransferStore.Transfer transfer = store.getTransfer(frame.transferId);
            if (transfer == null) throw new SecurityException("Unknown transfer task");
            LanTransferStore.Offer offer = transfer.offer;
            if (!peer.peerId.equals(offer.senderPeerId)
                    || !localPeerId.equals(offer.targetPeerId)
                    || !peer.peerId.equals(frame.senderPeerId)
                    || !localPeerId.equals(frame.targetPeerId))
                throw new SecurityException("Frame does not match fixed Offer participants");

            String responseKind = frame.kind;
            JSONObject responseMetadata = null;
            long now = clock.nowMs();
            if (now < 0) throw new IOException("Invalid transfer clock");
            if ("chunk".equals(frame.kind)) {
                if (!"transferring".equals(transfer.state))
                    throw new IOException("Transfer is not accepting chunks");
                boolean inserted = filesystem.writeChunk(frame.transferId, frame.index,
                        frame.payload, now);
                responseMetadata = ackMetadata(frame.index, inserted);
                responseKind = "chunk_ack";
            } else if ("missing".equals(frame.kind)) {
                int limit = requestedPageLimit(frame.metadata);
                filesystem.reconcilePage(frame.transferId, now);
                LanTransferStore.MissingPage page = filesystem.missingChunkPage(frame.transferId, 0, limit);
                responseMetadata = missingMetadata(page);
                responseKind = "missing";
            } else if ("complete".equals(frame.kind)) {
                if ("completed".equals(transfer.state)) {
                    responseMetadata = stateMetadata("completed");
                    responseKind = "complete";
                } else {
                    LanTransferStore.MissingPage page = filesystem.reconcilePage(frame.transferId, now);
                    if (!page.indexes.isEmpty()) {
                        responseMetadata = missingMetadata(page);
                        responseKind = "missing";
                    } else {
                        filesystem.assembleAndPublish(frame.transferId, now);
                        responseMetadata = stateMetadata("completed");
                        responseKind = "complete";
                    }
                }
            } else if ("cancel".equals(frame.kind)) {
                if (!isTerminal(transfer.state)) filesystem.cancelAndCleanup(frame.transferId, now);
                LanTransferStore.Transfer after = store.getTransfer(frame.transferId);
                responseMetadata = stateMetadata(after == null ? "unknown" : after.state);
                responseKind = "cancel";
            } else {
                throw new IOException("Unsupported transfer request kind");
            }

            try {
                LanTransferFrameCodec.send(socket, new LanTransferFrameCodec.Frame(
                        responseKind, frame.transferId, localPeerId, peer.peerId,
                        null, new byte[0], responseMetadata), peer.sessionKey, maxPayloadBytes);
            } catch (Exception sendFailure) {
                if (sendFailure instanceof IOException) throw (IOException) sendFailure;
                throw new IOException("Unable to send LNP2 response", sendFailure);
            }
            handled++;
        }
        return handled;
    }

    private static int requestedPageLimit(JSONObject metadata) throws IOException {
        if (metadata == null) return MAX_MISSING_PAGE;
        if (metadata.length() != 1 || !metadata.has("limit"))
            throw new IOException("Invalid missing-page request");
        try {
            Object value = metadata.get("limit");
            if (!(value instanceof Number)) throw new IOException("Invalid missing-page limit");
            java.math.BigDecimal n = new java.math.BigDecimal(value.toString());
            int limit = n.intValueExact();
            if (limit < 1 || limit > MAX_MISSING_PAGE) throw new IOException("Missing-page limit out of range");
            return limit;
        } catch (org.json.JSONException | ArithmeticException invalid) {
            throw new IOException("Invalid missing-page limit", invalid);
        }
    }

    private static JSONObject ackMetadata(int index, boolean inserted) throws IOException {
        JSONObject result = new JSONObject();
        try { result.put("index", index); result.put("new_receipt", inserted); }
        catch (org.json.JSONException invalid) { throw new IOException("Unable to encode chunk ACK", invalid); }
        return result;
    }

    private static JSONObject stateMetadata(String state) throws IOException {
        JSONObject result = new JSONObject();
        try { result.put("state", state); }
        catch (org.json.JSONException invalid) { throw new IOException("Unable to encode transfer state", invalid); }
        return result;
    }

    private static JSONObject missingMetadata(LanTransferStore.MissingPage page)
            throws IOException {
        JSONObject result = new JSONObject();
        JSONArray indexes = new JSONArray();
        for (Integer index : page.indexes) indexes.put(index);
        try { result.put("indexes", indexes); result.put("more", page.more); }
        catch (org.json.JSONException invalid) { throw new IOException("Unable to encode missing page", invalid); }
        return result;
    }

    private static boolean isTerminal(String state) {
        return "completed".equals(state) || "rejected".equals(state)
                || "cancelled".equals(state) || "failed".equals(state) || "expired".equals(state);
    }
}
