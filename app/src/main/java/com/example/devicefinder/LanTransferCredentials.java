package com.example.devicefinder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AndroidKeyStore-encrypted group PSK and explicit peer allow-list for LAN file transfer. */
public final class LanTransferCredentials {
    private static final String KEY_ALIAS = "devicefinder.lan.transfer.credentials.v1";
    private static final String PREFS = "lan_transfer_secure_credentials";
    private static final String CIPHER_KEY = "sealed_v1";
    private static final int GCM_TAG_BITS = 128;

    public static final class Value {
        public final String sharedSecret;
        public final Set<String> trustedPeerIds;
        private Value(String secret, Set<String> peers) {
            sharedSecret = secret;
            trustedPeerIds = Collections.unmodifiableSet(new HashSet<>(peers));
        }
    }

    private LanTransferCredentials() {}

    public static void provision(Context context, String sharedSecret, Set<String> trustedPeerIds)
            throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            throw new IllegalStateException("Secure transfer credentials require Android 6.0 or later");
        validate(sharedSecret, trustedPeerIds);
        JSONObject payload = new JSONObject();
        JSONArray peers = new JSONArray();
        for (String peer : trustedPeerIds) peers.put(peer);
        payload.put("secret", sharedSecret);
        payload.put("trusted_peer_ids", peers);
        byte[] clear = payload.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(clear);
        byte[] iv = cipher.getIV();
        JSONObject envelope = new JSONObject();
        envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        envelope.put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.edit().putString(CIPHER_KEY, envelope.toString()).commit())
            throw new IllegalStateException("Unable to persist encrypted transfer credentials");
        notifyService(context);
    }

    public static Value load(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String sealed = prefs.getString(CIPHER_KEY, null);
        if (sealed == null || sealed.isEmpty()) return null;
        JSONObject envelope = new JSONObject(sealed);
        byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        JSONObject payload = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        JSONArray array = payload.getJSONArray("trusted_peer_ids");
        Set<String> peers = new HashSet<>();
        for (int i = 0; i < array.length(); i++) peers.add(array.getString(i));
        String secret = payload.getString("secret");
        validate(secret, peers);
        return new Value(secret, peers);
    }

    public static void clear(Context context) throws Exception {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(CIPHER_KEY).commit();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        }
        notifyService(context);
    }

    private static void notifyService(Context context) {
        android.content.Intent intent = new android.content.Intent(context, TrackingService.class)
                .setAction("ACTION_LAN_TRANSFER_CREDENTIALS_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS))
            return ((SecretKey) keyStore.getKey(KEY_ALIAS, null));
        return Api23.createKey(KEY_ALIAS);
    }

    /** Isolated so API 21/22 can load the outer fail-closed credential class safely. */
    private static final class Api23 {
        static SecretKey createKey(String alias) throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                            | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }
    }

    private static void validate(String secret, Set<String> peers) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || secret.length() > 4096 || peers == null || peers.isEmpty() || peers.size() > 256)
            throw new IllegalArgumentException("A 32-byte group secret and explicit trusted peer allow-list are required");
        for (String peer : peers) {
            if (peer == null || !peer.matches("[A-Za-z0-9._:-]{1,128}"))
                throw new IllegalArgumentException("Invalid trusted peer identity");
        }
    }
}
