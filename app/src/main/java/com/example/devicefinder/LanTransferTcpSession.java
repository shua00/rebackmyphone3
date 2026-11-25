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

    private final Context appContext;
    private final LanTransferStore store;
    private final LanTransferFilesystem filesystem;
    private final String localPeerId;
    private final LanTransferAuthenticator.PeerSecretProvider peerSecrets;
    private final LanTransferAuthenticator.TrustedPeerAuthorizer authorizer;
    private final Clock clock;
    private final int maxFrames;
    private final int maxPayloadBytes;
    private final int socketTimeoutMs;

    public LanTransferTcpSession(Context c,LanTransferStore s,LanTransferFilesystem f,String id,String secret,LanTransferAuthenticator.TrustedPeerAuthorizer auth,Clock clock){this(c,s,f,id,ignored->secret,auth,clock,DEFAULT_MAX_FRAMES,DEFAULT_MAX_PAYLOAD_BYTES,15000);}
    public LanTransferTcpSession(Context c,LanTransferStore s,LanTransferFilesystem f,String id,String secret,LanTransferAuthenticator.TrustedPeerAuthorizer auth,Clock clock,int frames,int bytes,int timeout){this(c,s,f,id,ignored->secret,auth,clock,frames,bytes,timeout);}
    public LanTransferTcpSession(Context context,LanTransferStore store,LanTransferFilesystem filesystem,String localPeerId,LanTransferAuthenticator.PeerSecretProvider peerSecrets,LanTransferAuthenticator.TrustedPeerAuthorizer authorizer,Clock clock,int maxFrames,int maxPayloadBytes,int socketTimeoutMs){
        if(context==null||store==null||filesystem==null||localPeerId==null||localPeerId.isEmpty()||peerSecrets==null||authorizer==null||clock==null)throw new IllegalArgumentException("Transfer session dependencies are required");
        if(maxFrames<1||maxFrames>256||maxPayloadBytes<1||maxPayloadBytes>DEFAULT_MAX_PAYLOAD_BYTES||socketTimeoutMs<1000)throw new IllegalArgumentException("Invalid transfer session limits");
        this.appContext=context.getApplicationContext();this.store=store;this.filesystem=filesystem;this.localPeerId=localPeerId;this.peerSecrets=peerSecrets;this.authorizer=authorizer;this.clock=clock;this.maxFrames=maxFrames;this.maxPayloadBytes=maxPayloadBytes;this.socketTimeoutMs=socketTimeoutMs;
    }

    /** Serves at most maxFrames requests. The caller owns and closes the socket. */
    public int serve(Socket socket) throws IOException, GeneralSecurityException {
        if (socket == null || socket.isClosed()) throw new IllegalArgumentException("Connected socket required");
        socket.setSoTimeout(socketTimeoutMs);
        LanTransferAuthenticator.AuthenticatedPeer peer =
                LanTransferAuthenticator.authenticateServer(socket, localPeerId, peerSecrets, authorizer);
        int handled = 0;
        int missingPageLimit = 64;
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
            long now = clock.nowMs();
            if (now < 0) throw new IOException("Invalid transfer clock");
            String responseKind = frame.kind;
            JSONObject responseMetadata = null;
            LanTransferStore.Transfer transfer = store.getTransfer(frame.transferId);
            if ("offer".equals(frame.kind)) {
                if (frame.index != null || frame.payload.length != 0 || frame.metadata == null)
                    throw new IOException("Malformed transfer Offer frame");
                if (!peer.peerId.equals(frame.senderPeerId) || !localPeerId.equals(frame.targetPeerId))
                    throw new SecurityException("Offer sender/target does not match authenticated session");
                LanTransferStore.Offer incoming = parseOffer(frame.transferId, peer.peerId,
                        localPeerId, frame.metadata, now);
                boolean inserted = store.createOffer(incoming, now,
                        requestedOfferChunkSize(frame.metadata));
                transfer = store.getTransfer(frame.transferId);
                if (inserted) appContext.sendBroadcast(new android.content.Intent("ACTION_LAN_TRANSFER_UPDATED")
                        .setPackage(appContext.getPackageName()));
                responseMetadata = offerAckMetadata(transfer, inserted);
                responseKind = "offer_ack";
            } else {
                if (transfer == null) throw new SecurityException("Unknown transfer task");
                LanTransferStore.Offer offer = transfer.offer;
                if (!peer.peerId.equals(offer.senderPeerId)
                        || !localPeerId.equals(offer.targetPeerId)
                        || !peer.peerId.equals(frame.senderPeerId)
                        || !localPeerId.equals(frame.targetPeerId))
                    throw new SecurityException("Frame does not match fixed Offer participants");
            }
            if ("offer".equals(frame.kind)) {
                // Offer was durably recorded above; never auto-accept it.
            } else if ("chunk".equals(frame.kind)) {
                if (!"transferring".equals(transfer.state))
                    throw new IOException("Transfer is not accepting chunks");
                boolean inserted = filesystem.writeChunk(frame.transferId, frame.index,
                        frame.payload, now);
                responseMetadata = ackMetadata(frame.index, inserted);
                responseKind = "chunk_ack";
            } else if ("missing".equals(frame.kind)) {
                int limit = requestedPageLimit(frame.metadata);
                missingPageLimit = limit;
                filesystem.reconcilePage(frame.transferId, now);
                LanTransferStore.MissingPage page = filesystem.missingChunkPage(frame.transferId, 0, limit);
                responseMetadata = missingMetadata(page);
                responseKind = "missing";
            } else if ("complete".equals(frame.kind)) {
                if (!"transferring".equals(transfer.state) && !"completed".equals(transfer.state))
                    throw new IOException("Transfer is not ready for completion");
                if ("completed".equals(transfer.state)) {
                    responseMetadata = stateMetadata("completed");
                    responseKind = "complete";
                } else {
                    filesystem.reconcilePage(frame.transferId, now);
                    LanTransferStore.MissingPage page = filesystem.missingChunkPage(frame.transferId, 0, missingPageLimit);
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


    private static LanTransferStore.Offer parseOffer(String transferId, String sender,
            String target, JSONObject metadata, long now) throws IOException {
        if (metadata.length() != 6
                || !metadata.has("filename") || !metadata.has("size_bytes")
                || !metadata.has("sha256") || !metadata.has("created_at_ms")
                || !metadata.has("mime_type") || !metadata.has("chunk_size_bytes"))
            throw new IOException("Invalid transfer Offer metadata");
        try {
            Object sizeValue = metadata.get("size_bytes");
            Object createdValue = metadata.get("created_at_ms");
            if (!(sizeValue instanceof Number) || !(createdValue instanceof Number))
                throw new IOException("Offer size/timestamp must be JSON integers");
            long size = new java.math.BigDecimal(sizeValue.toString()).longValueExact();
            long created = new java.math.BigDecimal(createdValue.toString()).longValueExact();
            String filename = metadata.getString("filename");
            String sha256 = metadata.getString("sha256");
            String mime = metadata.getString("mime_type");
            int chunk = requestedOfferChunkSize(metadata);
            if (created > now + 300000L || created < now - 7L * 24L * 60L * 60L * 1000L)
                throw new IOException("Transfer Offer is outside the accepted age window");
            return new LanTransferStore.Offer(transferId, sender, target, filename,
                    size, sha256, created, mime);
        } catch (org.json.JSONException | ArithmeticException invalid) {
            throw new IOException("Malformed transfer Offer metadata", invalid);
        }
    }

    private static int requestedOfferChunkSize(JSONObject metadata) throws IOException {
        try {
            Object value = metadata.get("chunk_size_bytes");
            if (!(value instanceof Number)) throw new IOException("Offer chunk size must be a JSON integer");
            int chunk = new java.math.BigDecimal(value.toString()).intValueExact();
            if (chunk < LanTransferStore.MIN_CHUNK_SIZE || chunk > LanTransferStore.MAX_CHUNK_SIZE)
                throw new IOException("Offer chunk size outside supported range");
            return chunk;
        } catch (org.json.JSONException | ArithmeticException invalid) {
            throw new IOException("Invalid Offer chunk size", invalid);
        }
    }

    private static JSONObject offerAckMetadata(LanTransferStore.Transfer transfer,
            boolean inserted) throws IOException {
        JSONObject result = new JSONObject();
        if (transfer == null) throw new IOException("Offer was not durably stored");
        try {
            result.put("state", transfer.state);
            result.put("chunk_size_bytes", transfer.chunkSizeBytes);
            result.put("total_chunks", transfer.totalChunks);
            result.put("new_offer", inserted);
        } catch (org.json.JSONException invalid) {
            throw new IOException("Unable to encode Offer acknowledgement", invalid);
        }
        return result;
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
