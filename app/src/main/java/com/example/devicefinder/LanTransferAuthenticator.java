package com.example.devicefinder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Python-compatible mutual PSK challenge for LAN transfer TCP sessions. */
public final class LanTransferAuthenticator {
    private static final byte[] MAGIC = new byte[]{'L','T','F','1'};
    private static final int NONCE_BYTES = 32, PROOF_BYTES = 32, MAX_PEER_ID_BYTES = 128;
    private static final byte[] SERVER_DOMAIN = "lan-transfer-v1/server\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLIENT_DOMAIN = "lan-transfer-v1/client\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SESSION_DOMAIN = "lan-transfer-v1/session\0".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private LanTransferAuthenticator() {}

    /** API-21-compatible trust policy; avoids java.util.function on older Android releases. */
    public interface TrustedPeerAuthorizer { boolean isTrusted(String peerId); }
    public interface PeerSecretProvider { String secretFor(String peerId); }

    public static final class AuthenticatedPeer {
        public final String peerId;
        public final byte[] sessionKey;
        AuthenticatedPeer(String peerId, byte[] sessionKey) {
            this.peerId = peerId;
            this.sessionKey = sessionKey.clone();
        }
    }

    public static AuthenticatedPeer authenticateClient(Socket socket, String localPeerId,
            String expectedRemotePeerId, String sharedSecret) throws IOException, GeneralSecurityException {
        byte[] clientId = peerBytes(localPeerId), expectedServer = peerBytes(expectedRemotePeerId);
        byte[] key = deriveKey(sharedSecret), clientNonce = nonce();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.write(MAGIC); out.writeShort(clientId.length); out.write(clientId); out.write(clientNonce); out.flush();
        byte[] serverId = readPeerId(in);
        if (!MessageDigest.isEqual(serverId, expectedServer)) throw new SecurityException("Unexpected TCP server identity");
        byte[] serverNonce = new byte[NONCE_BYTES]; in.readFully(serverNonce);
        byte[] transcript = transcript(clientId, serverId, clientNonce, serverNonce);
        byte[] proof = new byte[PROOF_BYTES]; in.readFully(proof);
        if (!MessageDigest.isEqual(proof, hmac(key, concat(SERVER_DOMAIN, transcript))))
            throw new SecurityException("Invalid TCP server proof");
        out.write(hmac(key, concat(CLIENT_DOMAIN, transcript))); out.flush();
        if (in.readUnsignedByte() != 1) throw new SecurityException("TCP server rejected client");
        return new AuthenticatedPeer(decodePeer(serverId), hmac(key, concat(SESSION_DOMAIN, transcript)));
    }

    public static AuthenticatedPeer authenticateServer(Socket socket, String localPeerId,
            String sharedSecret, TrustedPeerAuthorizer authorizePeer) throws IOException, GeneralSecurityException {
        return authenticateServer(socket, localPeerId, ignored -> sharedSecret, authorizePeer);
    }
    public static AuthenticatedPeer authenticateServer(Socket socket, String localPeerId,
            PeerSecretProvider secrets, TrustedPeerAuthorizer authorizePeer) throws IOException, GeneralSecurityException {
        if (authorizePeer == null || secrets == null) throw new IllegalArgumentException("Credential lookup and trust policy are required");
        byte[] serverId = peerBytes(localPeerId);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        byte[] magic = new byte[MAGIC.length]; in.readFully(magic);
        if (!MessageDigest.isEqual(magic, MAGIC)) throw new SecurityException("Invalid transfer preamble");
        byte[] clientId = readPeerId(in);
        String clientName = decodePeer(clientId);
        byte[] clientNonce = new byte[NONCE_BYTES]; in.readFully(clientNonce);
        if (!authorizePeer.isTrusted(clientName)) throw new SecurityException("Untrusted TCP peer");
        String peerSecret = secrets.secretFor(clientName);
        if (peerSecret == null || peerSecret.isEmpty()) throw new SecurityException("No credential for peer identity");
        byte[] key = deriveKey(peerSecret);
        byte[] serverNonce = nonce(), transcript = transcript(clientId, serverId, clientNonce, serverNonce);
        out.writeShort(serverId.length); out.write(serverId); out.write(serverNonce);
        out.write(hmac(key, concat(SERVER_DOMAIN, transcript))); out.flush();
        byte[] proof = new byte[PROOF_BYTES]; in.readFully(proof);
        if (!MessageDigest.isEqual(proof, hmac(key, concat(CLIENT_DOMAIN, transcript))))
            throw new SecurityException("Invalid TCP client proof");
        out.writeByte(1); out.flush();
        return new AuthenticatedPeer(clientName, hmac(key, concat(SESSION_DOMAIN, transcript)));
    }

    private static byte[] peerBytes(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Peer ID is required");
        byte[] raw = id.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_PEER_ID_BYTES) throw new IllegalArgumentException("Invalid peer ID length");
        for (int i=0;i<id.length();i++) {
            char ch=id.charAt(i);
            if (Character.isISOControl(ch)) throw new IllegalArgumentException("Invalid peer ID");
            if (Character.isHighSurrogate(ch)) {
                if (i+1>=id.length() || !Character.isLowSurrogate(id.charAt(i+1)))
                    throw new IllegalArgumentException("Invalid peer ID");
                i++;
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Invalid peer ID");
        }
        return raw;
    }

    private static byte[] readPeerId(DataInputStream in) throws IOException {
        int size=in.readUnsignedShort();
        if(size<1 || size>MAX_PEER_ID_BYTES) throw new IOException("Invalid peer ID length");
        byte[] raw=new byte[size]; in.readFully(raw);
        String value=decodePeer(raw);
        if(!MessageDigest.isEqual(raw,peerBytes(value))) throw new IOException("Invalid peer ID");
        return raw;
    }

    private static String decodePeer(byte[] raw) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch(CharacterCodingException e) { throw new IOException("Invalid peer ID encoding",e); }
    }

    private static byte[] deriveKey(String secret) throws GeneralSecurityException {
        if(secret==null || secret.isEmpty()) throw new IllegalArgumentException("Shared secret is required");
        return MessageDigest.getInstance("SHA-256").digest(concat(
                "lan-p2p-v2\0".getBytes(StandardCharsets.UTF_8),secret.getBytes(StandardCharsets.UTF_8)));
    }
    private static byte[] nonce() { byte[] n=new byte[NONCE_BYTES]; RANDOM.nextBytes(n); return n; }
    private static byte[] transcript(byte[] c,byte[] s,byte[] cn,byte[] sn) {
        ByteBuffer b=ByteBuffer.allocate(2+c.length+2+s.length+cn.length+sn.length);
        b.putShort((short)c.length).put(c).putShort((short)s.length).put(s).put(cn).put(sn);
        return b.array();
    }
    private static byte[] hmac(byte[] key,byte[] data) throws GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(data);
    }
    private static byte[] concat(byte[] a,byte[] b) {
        byte[] r=Arrays.copyOf(a,a.length+b.length); System.arraycopy(b,0,r,a.length,b.length); return r;
    }
}
