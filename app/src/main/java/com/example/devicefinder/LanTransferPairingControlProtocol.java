package com.example.devicefinder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Versioned control-plane wire format for pairing only.
 * This is intentionally not the chat UDP protocol and not the file-data framing.
 * A transport connection carries one bounded envelope; the application owns discovery,
 * session negotiation, user consent, and durable credential commit.
 */
public final class LanTransferPairingControlProtocol {
    public static final int VERSION = 1;
    public static final int DEFAULT_PORT = 38473;
    public static final int MAX_FRAME_BYTES = 4096;
    public static final int MAX_PAYLOAD_BYTES = 2048;
    private static final int MAGIC = 0x4c545031; // LTP1

    public enum Type {
        REQUEST(1), RESPONSE(2), CONFIRM(3), REJECT(4), CANCEL(5), ACK(6), COMMIT(7), COMMIT_ACK(8);
        final int wire;
        Type(int wire) { this.wire = wire; }
        static Type fromWire(int value) throws IOException {
            for (Type type : values()) if (type.wire == value) return type;
            throw new IOException("Unknown pairing control message type");
        }
    }

    public static final class Message {
        public final Type type;
        public final String sessionId, senderPeerId, targetPeerId;
        public final long sequence;
        public final byte[] payload;
        public Message(Type type, String sessionId, String senderPeerId, String targetPeerId,
                long sequence, byte[] payload) {
            validate(type, sessionId, senderPeerId, targetPeerId, sequence, payload);
            this.type = type;
            this.sessionId = sessionId;
            this.senderPeerId = senderPeerId;
            this.targetPeerId = targetPeerId;
            this.sequence = sequence;
            this.payload = payload.clone();
        }
    }

    private LanTransferPairingControlProtocol() {}

    public static byte[] encode(Message message) throws IOException {
        if (message == null) throw new IllegalArgumentException("Pairing message is required");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(message.type.wire);
        writeString(out, message.sessionId, 36);
        writeString(out, message.senderPeerId, 128);
        writeString(out, message.targetPeerId, 128);
        out.writeLong(message.sequence);
        out.writeShort(message.payload.length);
        out.write(message.payload);
        out.flush();
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_FRAME_BYTES) throw new IOException("Pairing frame exceeds limit");
        return encoded;
    }

    public static Message decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 32 || encoded.length > MAX_FRAME_BYTES)
            throw new IOException("Invalid pairing frame size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != MAGIC) throw new IOException("Invalid pairing control magic");
        int version = in.readUnsignedByte();
        if (version != VERSION) throw new IOException("Unsupported pairing protocol version");
        Type type = Type.fromWire(in.readUnsignedByte());
        String session = readString(in, 36);
        String sender = readString(in, 128);
        String target = readString(in, 128);
        long sequence = in.readLong();
        int payloadLength = in.readUnsignedShort();
        if (payloadLength > MAX_PAYLOAD_BYTES || payloadLength != in.available())
            throw new IOException("Invalid pairing payload length");
        byte[] payload = new byte[payloadLength];
        in.readFully(payload);
        try {
            return new Message(type, session, sender, target, sequence, payload);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid pairing control envelope", invalid);
        }
    }

    private static void writeString(DataOutputStream out, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > max) throw new IOException("Invalid pairing string length");
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        int size = in.readUnsignedByte();
        if (size < 1 || size > max || size > in.available()) throw new IOException("Invalid pairing string length");
        byte[] raw = new byte[size];
        in.readFully(raw);
        String value = new String(raw, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(raw, value.getBytes(StandardCharsets.UTF_8)))
            throw new IOException("Invalid UTF-8 pairing field");
        return value;
    }

    private static void validate(Type type, String session, String sender, String target,
            long sequence, byte[] payload) {
        if (type == null || session == null || !isUuid(session)
                || !validPeer(sender) || !validPeer(target) || sender.equals(target)
                || sequence < 1L || payload == null || payload.length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Invalid pairing control message");
        if ((type == Type.REQUEST || type == Type.RESPONSE) && payload.length < 32)
            throw new IllegalArgumentException("Pairing key exchange payload is incomplete");
        if ((type == Type.CONFIRM || type == Type.REJECT) && payload.length != 32)
            throw new IllegalArgumentException("Pairing confirmation proof must be 32 bytes");
        if (type == Type.ACK && payload.length != 1 && payload.length != 33)
            throw new IllegalArgumentException("Pairing acknowledgement must contain a status and optional proof");
        if (type == Type.ACK && ((payload[0] == 0 && payload.length != 1)
                || ((payload[0] == 1 || payload[0] == 2) && payload.length != 33)
                || (payload[0] == 3 && payload.length != 1)
                || payload[0] < 0 || payload[0] > 3))
            throw new IllegalArgumentException("Pairing acknowledgement status and proof are inconsistent");
        if ((type == Type.COMMIT || type == Type.COMMIT_ACK) && payload.length != 32)
            throw new IllegalArgumentException("Pairing commit proof must be 32 bytes");
        if (type == Type.CANCEL && payload.length != 0)
            throw new IllegalArgumentException("Pairing cancel message must be empty");
    }

    private static boolean validPeer(String id) {
        return id != null && id.matches("[A-Za-z0-9._:-]{1,128}");
    }
    private static boolean isUuid(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (RuntimeException invalid) { return false; }
    }
}
