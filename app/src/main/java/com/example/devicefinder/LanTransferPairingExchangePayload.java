package com.example.devicefinder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Strict bounded payload codec for the P-256 public-key and nonce pairing exchange. */
public final class LanTransferPairingExchangePayload {
    private static final int VERSION = 1;
    private static final int NONCE_BYTES = LanTransferPairingCrypto.NONCE_BYTES;
    private static final int MIN_PUBLIC_KEY_BYTES = 64;
    private static final int MAX_PUBLIC_KEY_BYTES = 512;
    private static final int MAX_PAYLOAD_BYTES = 1024;

    public static final class Material {
        public final byte[] publicKey;
        public final byte[] nonce;

        public Material(byte[] publicKey, byte[] nonce) {
            validate(publicKey, nonce);
            this.publicKey = publicKey.clone();
            this.nonce = nonce.clone();
        }

        @Override public byte[] toString() {
            return "PairingExchangeMaterial{publicKeyBytes=" + publicKey.length + ", nonceBytes="
                    + nonce.length + "}";
        }
    }

    private LanTransferPairingExchangePayload() {}

    public static byte[] encode(Material material) throws IOException {
        if (material == null) throw new IllegalArgumentException("Pairing exchange material is required");
        validate(material.publicKey, material.nonce);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(VERSION);
        out.writeShort(material.publicKey.length);
        out.write(material.publicKey);
        out.write(material.nonce);
        out.flush();
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_PAYLOAD_BYTES) throw new IOException("Pairing exchange payload exceeds limit");
        return encoded;
    }

    public static Material decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 1 + 2 + MIN_PUBLIC_KEY_BYTES + NONCE_BYTES
                || encoded.length > MAX_PAYLOAD_BYTES)
            throw new IOException("Invalid pairing exchange payload size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readUnsignedByte() != VERSION) throw new IOException("Unsupported pairing exchange payload version");
        int keyLength = in.readUnsignedShort();
        if (keyLength < MIN_PUBLIC_KEY_BYTES || keyLength > MAX_PUBLIC_KEY_BYTES
                || in.available() != keyLength + NONCE_BYTES)
            throw new IOException("Invalid pairing exchange public key length");
        byte[] key = new byte[keyLength];
        byte[] nonce = new byte[NONCE_BYTES];
        in.readFully(key);
        in.readFully(nonce);
        try {
            return new Material(key, nonce);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid pairing exchange material", invalid);
        }
    }

    private static void validate(byte[] publicKey, byte[] nonce) {
        if (publicKey == null || publicKey.length < MIN_PUBLIC_KEY_BYTES
                || publicKey.length > MAX_PUBLIC_KEY_BYTES || nonce == null
                || nonce.length != NONCE_BYTES)
            throw new IllegalArgumentException("Invalid P-256 pairing exchange material");
    }
}
