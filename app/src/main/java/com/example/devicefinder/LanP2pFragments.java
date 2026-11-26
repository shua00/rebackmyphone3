package com.example.devicefinder;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Bounded UTF-8 fragmentation primitives for a single logical LAN chat message. */
public final class LanP2pFragments {
    public static final int MAX_MESSAGE_BYTES = 256 * 1024;
    public static final int RAW_FRAGMENT_BYTES = 360;
    public static final int MAX_FRAGMENTS = 1024;

    private LanP2pFragments() {}

    public static final class Fragment {
        public final int index;
        public final int count;
        public final int totalBytes;
        public final String sha256;
        public final String base64Data;

        Fragment(int index, int count, int totalBytes, String sha256, String base64Data) {
            this.index = index;
            this.count = count;
            this.totalBytes = totalBytes;
            this.sha256 = sha256;
            this.base64Data = base64Data;
        }
    }

    public static List<Fragment> split(String body) throws Exception {
        if (body == null) throw new IllegalArgumentException("Missing chat body");
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        if (raw.length == 0 || raw.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Long chat message size is outside supported range");
        }
        int count = (raw.length + RAW_FRAGMENT_BYTES - 1) / RAW_FRAGMENT_BYTES;
        if (count > MAX_FRAGMENTS) throw new IllegalArgumentException("Too many chat fragments");
        String digest = sha256(raw);
        ArrayList<Fragment> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * RAW_FRAGMENT_BYTES;
            int length = Math.min(RAW_FRAGMENT_BYTES, raw.length - start);
            byte[] chunk = new byte[length];
            System.arraycopy(raw, start, chunk, 0, length);
            result.add(new Fragment(index, count, raw.length, digest,
                    Base64.encodeToString(chunk, Base64.NO_WRAP)));
        }
        return result;
    }

    public static String assemble(List<Fragment> fragments) throws Exception {
        if (fragments == null || fragments.isEmpty()) throw new IllegalArgumentException("No fragments");
        Fragment first = fragments.get(0);
        if (first.count < 1 || first.count > MAX_FRAGMENTS
                || first.totalBytes < 1 || first.totalBytes > MAX_MESSAGE_BYTES
                || fragments.size() != first.count) throw new IllegalArgumentException("Invalid fragment set");
        Fragment[] ordered = new Fragment[first.count];
        for (Fragment fragment : fragments) {
            if (fragment == null || fragment.count != first.count
                    || fragment.totalBytes != first.totalBytes || !first.sha256.equals(fragment.sha256)
                    || fragment.index < 0 || fragment.index >= first.count
                    || ordered[fragment.index] != null) {
                throw new IllegalArgumentException("Inconsistent or duplicate fragment");
            }
            ordered[fragment.index] = fragment;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(first.totalBytes);
        for (Fragment fragment : ordered) {
            if (fragment == null) throw new IllegalArgumentException("Missing fragment");
            byte[] chunk;
            try { chunk = Base64.decode(fragment.base64Data, Base64.DEFAULT); }
            catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("Invalid fragment Base64", malformed); }
            if (chunk.length == 0 || chunk.length > RAW_FRAGMENT_BYTES) throw new IllegalArgumentException("Invalid fragment length");
            output.write(chunk);
        }
        byte[] raw = output.toByteArray();
        if (raw.length != first.totalBytes || !sha256(raw).equals(first.sha256)) {
            throw new SecurityException("Chat fragment digest mismatch");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Reassembled chat message is not valid UTF-8", malformed);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
