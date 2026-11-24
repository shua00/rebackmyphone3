package com.example.devicefinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
    // Bound identity size so first-discovery packets can reserve worst-case target-ID space.
    public static final int MAX_PEER_ID_BYTES = 128;

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
        validateUnicodeScalars(packet);
        packet.put("v", VERSION);
        validateCanonicalNumbers(packet);
        String type = packet.optString("type", "");
        if (!isSupportedType(type)) throw new IllegalArgumentException("Unsupported LAN P2P packet type");
        validatePeerId(packet, "sender_peer_id");
        if (packet.has("target_peer_id")) validatePeerId(packet, "target_peer_id");
        validatePacketSchema(packet);
        packet.put("auth", hmac(canonical(packet), secret));
        byte[] bytes = canonical(packet).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("LAN P2P datagram exceeds size limit");
        }
        return bytes;
    }

    public static JSONObject decode(byte[] bytes, String secret) throws Exception {
        if (bytes == null) throw new IllegalArgumentException("Invalid LAN P2P datagram size");
        return decode(bytes, 0, bytes.length, secret);
    }

    /**
     * Decode only the original datagram slice. Callers must not round-trip through String:
     * replacement decoding upstream would hide malformed UTF-8 before this strict boundary.
     */
    public static JSONObject decode(byte[] bytes, int offset, int length, String secret) throws Exception {
        if (bytes == null || offset < 0 || length <= 0
                || length > MAX_DATAGRAM_BYTES || offset > bytes.length - length) {
            throw new IllegalArgumentException("Invalid LAN P2P datagram size");
        }
        final String raw;
        try {
            raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("Invalid LAN P2P UTF-8", malformedUtf8);
        }
        JSONObject packet = new JSONObject(raw);
        validateUnicodeScalars(packet);
        validateCanonicalNumbers(packet);
        Object version = packet.opt("v");
        if (!(version instanceof Integer || version instanceof Long)
                || ((Number) version).longValue() != VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version");
        }
        if (!isSupportedType(packet.optString("type", ""))) {
            throw new IllegalArgumentException("Unsupported LAN P2P packet type");
        }
        validatePeerId(packet, "sender_peer_id");
        if (packet.has("target_peer_id")) validatePeerId(packet, "target_peer_id");
        validatePacketSchema(packet);
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

    /**
     * JSON floating-point and out-of-range integers do not have a guaranteed identical
     * canonical text representation across org.json and Python's json module.
     * The v2 wire schema uses integer timestamps only, so reject all fractional numbers.
     */
    private static void validateCanonicalNumbers(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL || value instanceof String
                || value instanceof Boolean) return;
        if (value instanceof Number) {
            if (!(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long)) {
                throw new IllegalArgumentException("Non-integer JSON numbers are unsupported");
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) validateCanonicalNumbers(object.get(keys.next()));
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) validateCanonicalNumbers(array.get(i));
        }
    }

    private static void validatePeerId(JSONObject packet, String field) throws Exception {
        if (!packet.has(field) || !(packet.get(field) instanceof String)) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        String peerId = packet.getString(field);
        if (!isValidPeerId(peerId)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    /** Shared validation for wire packets and durable recipient snapshots. */
    private static boolean isValidUnicodeScalarString(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return false;
            }
        }
        return true;
    }

    /** Recursively enforce the same strict UTF-8 string boundary as Python before HMAC. */
    private static void validateUnicodeScalars(Object value) throws Exception {
        if (value instanceof String) {
            if (!isValidUnicodeScalarString((String) value)) {
                throw new IllegalArgumentException("JSON string contains an unpaired surrogate");
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!isValidUnicodeScalarString(key)) {
                    throw new IllegalArgumentException("JSON key contains an unpaired surrogate");
                }
                validateUnicodeScalars(object.get(key));
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) validateUnicodeScalars(array.get(i));
        }
    }

    public static boolean isValidPeerId(String peerId) {
        if (peerId == null || peerId.isEmpty()
                || !isValidUnicodeScalarString(peerId)) return false;
        for (int i = 0; i < peerId.length(); i++) {
            if (Character.isISOControl(peerId.charAt(i))) return false;
        }
        return peerId.getBytes(StandardCharsets.UTF_8).length <= MAX_PEER_ID_BYTES;
    }

    /**
     * A valid maximum-length ID whose quotes expand to two JSON bytes each.
     * Preflight with this value so a later directed packet cannot exceed the envelope
     * merely because its real recipient ID is longer than the broadcast wildcard.
     */
    public static String maxPeerIdBudgetProbe() {
        StringBuilder probe = new StringBuilder(MAX_PEER_ID_BYTES);
        for (int i = 0; i < MAX_PEER_ID_BYTES; i++) probe.append('"');
        return probe.toString();
    }


    private static void requirePacketString(JSONObject packet, String field,
                                            boolean allowEmpty) throws Exception {
        if (!packet.has(field) || !(packet.get(field) instanceof String)
                || (!allowEmpty && ((String) packet.get(field)).isEmpty())) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
    }

    private static long requirePacketInteger(JSONObject packet, String field,
                                             long minimum, long maximum) throws Exception {
        if (!packet.has(field)) throw new IllegalArgumentException("Missing " + field);
        Object value = packet.get(field);
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        long number = ((Number) value).longValue();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return number;
    }

    /** Type-specific v2 fields are validated identically by the Python codec. */
    private static void validatePacketSchema(JSONObject packet) throws Exception {
        for (String field : new String[]{"sender_peer_type", "service_id", "network_scope", "display_name"}) {
            if (packet.has(field) && !(packet.get(field) instanceof String)) {
                throw new IllegalArgumentException("Invalid " + field);
            }
        }
        String type = packet.getString("type");
        if ("peer.hello".equals(type)) {
            requirePacketString(packet, "nonce", false);
            if (packet.has("challenge_only") && !(packet.get("challenge_only") instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid challenge_only");
            }
            if (packet.has("listen_port")) requirePacketInteger(packet, "listen_port", 1, 65535);
            if (packet.has("capabilities")) {
                Object value = packet.get("capabilities");
                if (!(value instanceof JSONArray)) throw new IllegalArgumentException("Invalid capabilities");
                JSONArray capabilities = (JSONArray) value;
                for (int i = 0; i < capabilities.length(); i++) {
                    if (!(capabilities.get(i) instanceof String)) {
                        throw new IllegalArgumentException("Invalid capabilities");
                    }
                }
            }
        } else if ("peer.ack".equals(type)) {
            requirePacketString(packet, "reply_nonce", false);
            if (packet.has("listen_port")) requirePacketInteger(packet, "listen_port", 1, 65535);
        } else if ("chat.message".equals(type)) {
            requirePacketString(packet, "message_id", false);
            requirePacketString(packet, "target_peer_id", false);
            requirePacketString(packet, "body", true);
            if (packet.has("created_at_ms")) requirePacketInteger(packet, "created_at_ms", 0, Long.MAX_VALUE);
        } else if ("chat.ack".equals(type)) {
            requirePacketString(packet, "message_id", false);
            requirePacketString(packet, "target_peer_id", false);
            if (!packet.has("status") || !(packet.get("status") instanceof String)
                    || !"stored".equals(packet.getString("status"))) {
                throw new IllegalArgumentException("Invalid chat ACK status");
            }
        } else {
            throw new IllegalArgumentException("Unsupported LAN P2P packet type");
        }
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
