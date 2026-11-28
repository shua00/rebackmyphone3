package com.example.devicefinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Python-compatible authenticated LNP2 file-transfer TCP frame codec. */
public final class LanTransferFrameCodec {
    private static final byte[] MAGIC = new byte[]{'L','N','P','2'};
    private static final int VERSION = 1;
    private static final int MAX_HEADER_BYTES = 4096;
    private static final int MAX_METADATA_BYTES = 2048;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String[] HEADER_KEYS = new String[]{
            "auth","index","kind","metadata","payload_sha256","payload_size",
            "sender_peer_id","target_peer_id","transfer_id","v"};
    private LanTransferFrameCodec() {}

    public static final class Frame {
        public final String kind, transferId, senderPeerId, targetPeerId;
        public final Integer index;
        public final byte[] payload;
        public final JSONObject metadata;
        public Frame(String kind, String transferId, String senderPeerId, String targetPeerId,
                Integer index, byte[] payload, JSONObject metadata) {
            this.kind=kind; this.transferId=transferId; this.senderPeerId=senderPeerId;
            this.targetPeerId=targetPeerId; this.index=index;
            this.payload=payload==null?new byte[0]:payload.clone();
            this.metadata=metadata;
        }
    }

    public static void send(Socket socket, Frame frame, byte[] sessionKey, int maxPayloadBytes)
            throws Exception {
        socket.getOutputStream().write(encodeFrame(frame, sessionKey, maxPayloadBytes));
        socket.getOutputStream().flush();
    }

    /** Encode one complete frame without depending on a socket or network runtime. */
    public static byte[] encodeFrame(Frame frame, byte[] sessionKey, int maxPayloadBytes)
            throws Exception {
        validate(frame, sessionKey, maxPayloadBytes);
        JSONObject unsigned = unsignedHeader(frame);
        String auth = hex(hmac(sessionKey, canonical(unsigned).getBytes(StandardCharsets.UTF_8)));
        JSONObject header = new JSONObject();
        Iterator<String> keys = unsigned.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            header.put(key, unsigned.get(key));
        }
        header.put("auth", auth);
        byte[] encoded = canonical(header).getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1 || encoded.length > MAX_HEADER_BYTES)
            throw new IllegalArgumentException("Transfer header exceeds limit");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 + encoded.length + frame.payload.length);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC); out.writeInt(encoded.length); out.write(encoded); out.write(frame.payload); out.flush();
        return bytes.toByteArray();
    }

    public static Frame receive(Socket socket, byte[] sessionKey, int maxPayloadBytes,
            String expectedSenderPeerId, String expectedTargetPeerId) throws Exception {
        return decodeFromStream(new DataInputStream(socket.getInputStream()), sessionKey,
                maxPayloadBytes, expectedSenderPeerId, expectedTargetPeerId);
    }

    /** Decode exactly one complete frame from a byte array; trailing bytes are rejected. */
    public static Frame decodeFrame(byte[] wire, byte[] sessionKey, int maxPayloadBytes,
            String expectedSenderPeerId, String expectedTargetPeerId) throws Exception {
        if (wire == null) throw new IOException("Missing transfer frame bytes");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire));
        Frame frame = decodeFromStream(in, sessionKey, maxPayloadBytes,
                expectedSenderPeerId, expectedTargetPeerId);
        if (in.available() != 0) throw new IOException("Trailing bytes after transfer frame");
        return frame;
    }

    private static Frame decodeFromStream(DataInputStream in, byte[] sessionKey, int maxPayloadBytes,
            String expectedSenderPeerId, String expectedTargetPeerId) throws Exception {
        requireSession(sessionKey, maxPayloadBytes);
        byte[] magic = new byte[4]; in.readFully(magic);
        if (!MessageDigest.isEqual(magic, MAGIC)) throw new IOException("Invalid transfer frame magic");
        int headerSize = in.readInt();
        if (headerSize < 1 || headerSize > MAX_HEADER_BYTES) throw new IOException("Invalid transfer header length");
        byte[] rawHeader = new byte[headerSize]; in.readFully(rawHeader);
        final String raw;
        try {
            raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawHeader)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid transfer header UTF-8", e); }
        JSONObject header = new JSONObject(raw);
        if (!canonical(header).equals(raw)) throw new IOException("Non-canonical transfer header");
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = header.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        List<String> expectedKeys = new ArrayList<>();
        Collections.addAll(expectedKeys, HEADER_KEYS);
        Collections.sort(expectedKeys);
        if (!keys.equals(expectedKeys)) throw new IOException("Transfer header schema mismatch");
        if (number(header, "v") != VERSION) throw new IOException("Unsupported transfer frame version");
        String kind = string(header, "kind"), transferId = string(header, "transfer_id");
        String sender = string(header, "sender_peer_id"), target = string(header, "target_peer_id");
        checkId(transferId, "transfer ID"); checkId(sender, "sender peer ID"); checkId(target, "target peer ID");
        if (sender.equals(target)) throw new IOException("Transfer peers must differ");
        int payloadSize = number(header, "payload_size");
        if (payloadSize < 0 || payloadSize > maxPayloadBytes) throw new IOException("Invalid transfer payload length");
        Object indexValue = header.get("index");
        Integer index = indexValue == JSONObject.NULL ? null : integer(indexValue, "index");
        Object metadataValue = header.get("metadata");
        JSONObject metadata = metadataValue == JSONObject.NULL ? null
                : (metadataValue instanceof JSONObject ? (JSONObject) metadataValue : null);
        if (metadataValue != JSONObject.NULL && metadata == null) throw new IOException("Invalid transfer metadata");
        byte[] payload = new byte[payloadSize]; if (payloadSize > 0) in.readFully(payload);
        String suppliedHash = string(header, "payload_sha256");
        String actualHash = payloadSize == 0 ? ""
                : hex(MessageDigest.getInstance("SHA-256").digest(payload));
        if (!MessageDigest.isEqual(suppliedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII))) throw new IOException("Payload SHA-256 mismatch");
        Frame frame = new Frame(kind, transferId, sender, target, index, payload, metadata);
        validate(frame, sessionKey, maxPayloadBytes);
        if (expectedSenderPeerId != null && !expectedSenderPeerId.equals(sender))
            throw new SecurityException("Unexpected transfer sender");
        if (expectedTargetPeerId != null && !expectedTargetPeerId.equals(target))
            throw new SecurityException("Unexpected transfer target");
        JSONObject unsigned = withoutAuth(header);
        String expectedAuth = hex(hmac(sessionKey, canonical(unsigned).getBytes(StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(string(header, "auth").getBytes(StandardCharsets.US_ASCII),
                expectedAuth.getBytes(StandardCharsets.US_ASCII))) throw new SecurityException("Invalid frame HMAC");
        return frame;
    }

    private static JSONObject unsignedHeader(Frame f) throws Exception {
        JSONObject h=new JSONObject();
        h.put("v",VERSION); h.put("kind",f.kind); h.put("transfer_id",f.transferId);
        h.put("sender_peer_id",f.senderPeerId); h.put("target_peer_id",f.targetPeerId);
        h.put("index",f.index==null?JSONObject.NULL:f.index);
        h.put("payload_size",f.payload.length);
        h.put("payload_sha256",f.payload.length==0?"":hex(MessageDigest.getInstance("SHA-256").digest(f.payload)));
        h.put("metadata",f.metadata==null?JSONObject.NULL:f.metadata);
        return h;
    }

    private static void validate(Frame f,byte[] key,int maxPayload) throws Exception {
        requireSession(key,maxPayload);
        if(f==null) throw new IllegalArgumentException("Missing transfer frame");
        if(!("offer".equals(f.kind)||"offer_ack".equals(f.kind)
                ||"chunk".equals(f.kind)||"chunk_ack".equals(f.kind)||"missing".equals(f.kind)
                ||"complete".equals(f.kind)||"cancel".equals(f.kind)))
            throw new IllegalArgumentException("Unsupported transfer frame kind");
        checkId(f.transferId,"transfer ID"); checkId(f.senderPeerId,"sender peer ID");
        checkId(f.targetPeerId,"target peer ID");
        if(f.senderPeerId.equals(f.targetPeerId)) throw new IllegalArgumentException("Transfer peers must differ");
        if(f.payload.length>maxPayload) throw new IllegalArgumentException("Transfer payload exceeds limit");
        if("chunk".equals(f.kind)) {
            if(f.index==null||f.index<0||f.payload.length==0||f.metadata!=null)
                throw new IllegalArgumentException("Invalid chunk frame");
        } else if(f.index!=null||f.payload.length!=0) {
            throw new IllegalArgumentException("Control frame cannot contain chunk data");
        }
        if(f.metadata!=null && canonical(f.metadata).getBytes(StandardCharsets.UTF_8).length>MAX_METADATA_BYTES)
            throw new IllegalArgumentException("Transfer metadata exceeds limit");
    }

    private static void requireSession(byte[] key,int maxPayload) {
        if(key==null||key.length<16) throw new IllegalArgumentException("Session key must contain at least 16 bytes");
        if(maxPayload<1||maxPayload>1024*1024) throw new IllegalArgumentException("Invalid transfer payload limit");
    }
    private static void checkId(String value,String field) throws IOException {
        if(value==null||!ID.matcher(value).matches()) throw new IOException("Invalid "+field);
    }
    private static String string(JSONObject o,String key) throws Exception {
        Object v=o.get(key); if(!(v instanceof String)) throw new IOException("Invalid "+key); return (String)v;
    }
    private static int number(JSONObject o,String key) throws Exception { return integer(o.get(key),key); }
    private static int integer(Object v,String field) throws IOException {
        if (!(v instanceof Number)) throw new IOException("Invalid "+field
                + " type=" + (v == null ? "null" : v.getClass().getName())
                + " value=" + String.valueOf(v));
        try {
            long exact = new java.math.BigDecimal(v.toString()).longValueExact();
            if (exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE)
                throw new IOException("Out of range "+field);
            return (int) exact;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IOException("Invalid integral "+field
                    + " type=" + v.getClass().getName() + " value=" + v, invalid);
        }
    }
    private static JSONObject withoutAuth(JSONObject source) throws Exception {
        JSONObject copy=new JSONObject(); Iterator<String> it=source.keys();
        while(it.hasNext()){String k=it.next();if(!"auth".equals(k))copy.put(k,source.get(k));}
        return copy;
    }
    private static byte[] hmac(byte[] key,byte[] data) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);
    }
    private static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder(bytes.length*2);
        for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));
        return s.toString();
    }
    private static String canonical(JSONObject object) throws Exception {
        List<String> keys=new ArrayList<>();Iterator<String> it=object.keys();
        while(it.hasNext())keys.add(it.next());Collections.sort(keys);
        StringBuilder out=new StringBuilder("{");
        for(int i=0;i<keys.size();i++){
            if(i>0)out.append(',');
            String key=keys.get(i);out.append(quoteCanonical(key)).append(':').append(canonicalValue(object.get(key)));
        }
        return out.append('}').toString();
    }
    /** Python json.dumps(ensure_ascii=false) compatible quoting; notably does not escape '/'. */
    private static String quoteCanonical(String value) {
        StringBuilder out=new StringBuilder(value.length()+2).append('"');
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            switch(ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if(ch<0x20) {
                        out.append("\\u");
                        String hex=Integer.toHexString(ch);
                        for(int pad=hex.length();pad<4;pad++) out.append('0');
                        out.append(hex);
                    } else out.append(ch);
            }
        }
        return out.append('"').toString();
    }

    private static String canonicalValue(Object value) throws Exception {
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject)return canonical((JSONObject)value);
        if(value instanceof JSONArray){
            JSONArray a=(JSONArray)value;StringBuilder out=new StringBuilder("[");
            for(int i=0;i<a.length();i++){if(i>0)out.append(',');out.append(canonicalValue(a.get(i)));}
            return out.append(']').toString();
        }
        if(value instanceof String)return quoteCanonical((String)value);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number) {
            try {
                return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Unsupported JSON number", invalid);
            }
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
