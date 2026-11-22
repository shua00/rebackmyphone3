package com.example.devicefinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared v2 LAN P2P JSON envelope codec. No third-party dependencies. */
public final class LanP2pProtocol {
    public static final int VERSION = 2;
    public static final int MAX_DATAGRAM_BYTES = 1200;

    private LanP2pProtocol() {}

    public static JSONObject create(String type, String senderPeerId,
                                    String senderPeerType, String serviceId) throws Exception {
        if (!("peer.hello".equals(type) || "peer.ack".equals(type)
                || "chat.message".equals(type) || "chat.ack".equals(type))) {
            throw new IllegalArgumentException("Unsupported LAN P2P packet type");
        }
        JSONObject packet = new JSONObject();
        packet.put("v", VERSION);
        packet.put("type", type);
        packet.put("sender_peer_id", senderPeerId);
        packet.put("sender_peer_type", senderPeerType);
        packet.put("service_id", serviceId);
        return packet;
    }

    public static byte[] encode(JSONObject fields, String secret) throws Exception {
        JSONObject packet = copyWithoutAuth(fields);
        packet.put("v", VERSION);
        String type = packet.optString("type", "");
        if (!isSupportedType(type)) throw new IllegalArgumentException("Unsupported LAN P2P packet type");
        packet.put("auth", hmac(canonical(packet), secret));
        byte[] bytes = canonical(packet).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("LAN P2P datagram exceeds size limit");
        }
        return bytes;
    }

    public static JSONObject decode(byte[] bytes, String secret) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("Invalid LAN P2P datagram size");
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        JSONObject packet = new JSONObject(raw);
        if (packet.optInt("v", -1) != VERSION) throw new IllegalArgumentException("Unsupported protocol version");
        if (!isSupportedType(packet.optString("type", ""))) {
            throw new IllegalArgumentException("Unsupported LAN P2P packet type");
        }
        if (packet.optString("sender_peer_id", "").isEmpty()) {
            throw new IllegalArgumentException("Missing sender peer identity");
        }
        String supplied = packet.optString("auth", "");
        if (supplied.isEmpty()) throw new IllegalArgumentException("Missing packet authentication");
        JSONObject unsigned = copyWithoutAuth(packet);
        String expected = hmac(canonical(unsigned), secret);
        if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Invalid LAN P2P authentication");
        }
        return packet;
    }

    private static boolean isSupportedType(String type) {
        return "peer.hello".equals(type) || "peer.ack".equals(type)
                || "chat.message".equals(type) || "chat.ack".equals(type);
    }

    private static JSONObject copyWithoutAuth(JSONObject source) throws Exception {
        JSONObject copy = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"auth".equals(key)) copy.put(key, source.get(key));
        }
        return copy;
    }

    private static String hmac(String canonical, String secret) throws Exception {
        if (secret == null || secret.isEmpty()) throw new IllegalArgumentException("Missing shared secret");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(("lan-p2p-v2" + ((char) 0) + secret).getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] result = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(result.length * 2);
        for (byte b : result) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String canonical(JSONObject object) throws Exception {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = object.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String key : keys) {
            if (!first) out.append(',');
            first = false;
            out.append(JSONObject.quote(key)).append(':').append(canonicalValue(object.get(key)));
        }
        return out.append('}').toString();
    }

    private static String canonicalValue(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return canonical((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonicalValue(array.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
