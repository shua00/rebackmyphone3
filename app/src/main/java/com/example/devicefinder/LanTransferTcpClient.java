package com.example.devicefinder;

import android.net.Network;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Resumable Android file sender for one authenticated LNP2 TCP session. */
public final class LanTransferTcpClient {
    public interface Progress { void onProgress(int sent, int batchSize); }
    public static final int DEFAULT_CHUNKS_PER_SESSION = 62;
    private LanTransferTcpClient() {}


    /**
     * Opens the sender socket through the exact Wi-Fi Network chosen by the caller.
     * Unlike accepted server sockets, an outgoing socket must be created unconnected,
     * bound by Network.getSocketFactory(), and only then connected.
     */
    public static List<Integer> connectAndSend(Network wifiNetwork, String host, int port,
            File source, LanTransferStore.Offer offer, String localPeerId, String remotePeerId,
            String sharedSecret, int chunkSizeBytes, int maxChunksPerSession,
            int maxPayloadBytes, int connectTimeoutMs, Progress progress)
            throws IOException, GeneralSecurityException {
        if (wifiNetwork == null || host == null || host.isEmpty() || port < 1 || port > 65535
                || connectTimeoutMs < 1000 || connectTimeoutMs > 60000)
            throw new IllegalArgumentException("A selected Wi-Fi Network and valid destination are required");
        try (Socket socket = wifiNetwork.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            return sendFile(socket, source, offer, localPeerId, remotePeerId, sharedSecret,
                    chunkSizeBytes, maxChunksPerSession, maxPayloadBytes, progress);
        }
    }

    /**
     * Sends one bounded batch. Reconnect and call again when missing indexes are returned;
     * each new session re-queries the receiver, so lost ACKs never expand or corrupt state.
     */
    public static List<Integer> sendFile(Socket socket, File source,
            LanTransferStore.Offer offer, String localPeerId, String remotePeerId,
            String sharedSecret, int chunkSizeBytes, int maxChunksPerSession,
            int maxPayloadBytes, Progress progress) throws IOException, GeneralSecurityException {
        if (socket == null || socket.isClosed() || source == null || offer == null)
            throw new IllegalArgumentException("Connected socket, source and Offer are required");
        if (!localPeerId.equals(offer.senderPeerId) || !remotePeerId.equals(offer.targetPeerId))
            throw new SecurityException("Offer participants do not match sender session");
        if (chunkSizeBytes < LanTransferStore.MIN_CHUNK_SIZE
                || chunkSizeBytes > LanTransferStore.MAX_CHUNK_SIZE
                || maxChunksPerSession < 1 || maxChunksPerSession > DEFAULT_CHUNKS_PER_SESSION
                || maxPayloadBytes < chunkSizeBytes || maxPayloadBytes > 1024 * 1024)
            throw new IllegalArgumentException("Invalid sender transfer limits");
        File absolute = source.getAbsoluteFile();
        File parent = absolute.getParentFile();
        String expectedPath = new File(parent.getCanonicalFile(), absolute.getName()).getPath();
        if (!source.isFile() || !source.getCanonicalPath().equals(expectedPath))
            throw new IOException("Source must be a regular non-symlink file");
        long beforeLength = source.length(), beforeModified = source.lastModified();
        String digest = hashFile(source);
        if (beforeLength != offer.sizeBytes || !digest.equals(offer.sha256))
            throw new IOException("Source file does not match accepted Offer");

        socket.setSoTimeout(15000);
        LanTransferAuthenticator.AuthenticatedPeer peer =
                LanTransferAuthenticator.authenticateClient(socket, localPeerId, remotePeerId, sharedSecret);
        int totalChunks = (int) ((offer.sizeBytes + chunkSizeBytes - 1L) / chunkSizeBytes);
        JSONObject request = new JSONObject();
        try { request.put("limit", maxChunksPerSession); }
        catch (org.json.JSONException impossible) { throw new IOException("Unable to encode missing query", impossible); }
        send(socket, new LanTransferFrameCodec.Frame("missing", offer.transferId,
                localPeerId, remotePeerId, null, new byte[0], request), peer.sessionKey, maxPayloadBytes);
        LanTransferFrameCodec.Frame missing = receive(socket, peer, localPeerId, maxPayloadBytes);
        if (!"missing".equals(missing.kind) || !offer.transferId.equals(missing.transferId))
            throw new IOException("Receiver did not return a missing page");
        List<Integer> indexes = parseMissing(missing.metadata, totalChunks, maxChunksPerSession);
        List<Integer> batch = indexes.subList(0, Math.min(indexes.size(), maxChunksPerSession));
        int sent = 0;
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            for (Integer index : batch) {
                long offset = (long) index * chunkSizeBytes;
                int length = (int) Math.min((long) chunkSizeBytes, offer.sizeBytes - offset);
                byte[] payload = new byte[length];
                input.seek(offset);
                input.readFully(payload);
                send(socket, new LanTransferFrameCodec.Frame("chunk", offer.transferId,
                        localPeerId, remotePeerId, index, payload, null), peer.sessionKey, maxPayloadBytes);
                LanTransferFrameCodec.Frame ack = receive(socket, peer, localPeerId, maxPayloadBytes);
                if (!"chunk_ack".equals(ack.kind) || !offer.transferId.equals(ack.transferId)
                        || ack.metadata == null || ack.metadata.optInt("index", -1) != index)
                    throw new IOException("Chunk acknowledgement mismatch");
                sent++;
                if (progress != null) progress.onProgress(sent, batch.size());
            }
        }
        if (source.length() != beforeLength || source.lastModified() != beforeModified
                || !source.getCanonicalPath().equals(expectedPath) || !hashFile(source).equals(digest))
            throw new IOException("Source file changed during transfer");

        send(socket, new LanTransferFrameCodec.Frame("complete", offer.transferId,
                localPeerId, remotePeerId, null, new byte[0], null), peer.sessionKey, maxPayloadBytes);
        LanTransferFrameCodec.Frame complete = receive(socket, peer, localPeerId, maxPayloadBytes);
        if (!offer.transferId.equals(complete.transferId))
            throw new IOException("Completion response belongs to another transfer");
        if ("missing".equals(complete.kind))
            return parseMissing(complete.metadata, totalChunks, maxChunksPerSession);
        if (!"complete".equals(complete.kind) || complete.metadata == null
                || !"completed".equals(complete.metadata.optString("state", "")))
            throw new IOException("Receiver did not confirm verified completion");
        return new ArrayList<>();
    }

    private static String hashFile(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static List<Integer> parseMissing(JSONObject metadata, int total, int limit) throws IOException {
        if (metadata == null) throw new IOException("Missing-page metadata is required");
        JSONArray array = metadata.optJSONArray("indexes");
        if (array == null || array.length() > limit || !metadata.has("more")
                || !(metadata.opt("more") instanceof Boolean))
            throw new IOException("Malformed missing page");
        List<Integer> result = new ArrayList<>();
        int previous = -1;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof Number)) throw new IOException("Invalid missing chunk index");
            try {
                int index = new java.math.BigDecimal(value.toString()).intValueExact();
                if (index <= previous || index < 0 || index >= total)
                    throw new IOException("Missing chunk indexes are unordered or out of range");
                result.add(index);
                previous = index;
            } catch (NumberFormatException | ArithmeticException invalid) {
                throw new IOException("Invalid missing chunk index", invalid);
            }
        }
        if (Boolean.TRUE.equals(metadata.opt("more")) && result.isEmpty())
            throw new IOException("Empty missing page cannot advertise more results");
        return result;
    }

    private static LanTransferFrameCodec.Frame receive(Socket socket,
            LanTransferAuthenticator.AuthenticatedPeer peer, String localPeerId,
            int maxPayloadBytes) throws IOException {
        try {
            return LanTransferFrameCodec.receive(socket, peer.sessionKey, maxPayloadBytes,
                    peer.peerId, localPeerId);
        } catch (Exception invalid) {
            if (invalid instanceof IOException) throw (IOException) invalid;
            if (invalid instanceof SecurityException) throw (SecurityException) invalid;
            throw new IOException("Invalid LNP2 response", invalid);
        }
    }

    private static void send(Socket socket, LanTransferFrameCodec.Frame frame,
            byte[] sessionKey, int maxPayloadBytes) throws IOException {
        try { LanTransferFrameCodec.send(socket, frame, sessionKey, maxPayloadBytes); }
        catch (Exception failure) {
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Unable to send LNP2 request", failure);
        }
    }
}
