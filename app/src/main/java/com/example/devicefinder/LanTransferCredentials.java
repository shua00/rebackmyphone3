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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AndroidKeyStore-sealed transfer credentials. New records are peer-scoped; v1 group PSKs remain readable. */
public final class LanTransferCredentials {
    private static final String KEY_ALIAS="devicefinder.lan.transfer.credentials.v1", PREFS="lan_transfer_secure_credentials", CIPHER_KEY="sealed_v1";
    private static final int GCM_TAG_BITS=128;
    public static final class Value {
        public final String sharedSecret;
        public final Set<String> trustedPeerIds;
        public final Map<String,String> peerSecrets;
        private Value(String legacy, Map<String,String> secrets) {
            sharedSecret=legacy; peerSecrets=Collections.unmodifiableMap(new HashMap<>(secrets));
            trustedPeerIds=Collections.unmodifiableSet(new HashSet<>(secrets.keySet()));
        }
        public String secretFor(String id) { return id==null?null:peerSecrets.get(id); }
    }
    private LanTransferCredentials() {}

    /** Legacy API retained only for reading/migration of explicitly configured v1 peers. */
    public static void provision(Context c,String secret,Set<String> ids)throws Exception {
        validateSecret(secret);
        if(ids==null||ids.isEmpty()||ids.size()>256)throw new IllegalArgumentException("Explicit trusted peer allow-list is required");
        for(String id:ids)validatePeer(id);
        write(c,new JSONObject().put("version",1).put("secret",secret).put("trusted_peer_ids",new JSONArray(ids)));
    }

    /** Replaces the full v2 map; use mergePeerSecret for normal pairing commits. */
    public static void provisionPeerSecrets(Context c,Map<String,String> secrets)throws Exception {
        if(secrets==null||secrets.isEmpty()||secrets.size()>256)throw new IllegalArgumentException("Per-peer credentials are required");
        JSONObject peers=new JSONObject();
        for(Map.Entry<String,String> e:secrets.entrySet()){validatePeer(e.getKey());validateSecret(e.getValue());peers.put(e.getKey(),e.getValue());}
        write(c,new JSONObject().put("version",2).put("peer_secrets",peers));
    }

    /**
     * Atomic read-merge-write for a completed pairing. Existing v1 peers remain mapped to their
     * legacy PSK; the newly paired peer receives only its fresh 32-byte secret.
     */
    /** Compatibility helper for the legacy manual-PSK screen; new pairing must use mergePeerSecret. */
    @Deprecated
    static Set<String> mergeTrustedPeerIds(Set<String> existingPeerIds,String existingSecret,
            String requestedSecret,String newPeerId) {
        if(existingPeerIds!=null&&!existingPeerIds.isEmpty()
                &&(existingSecret==null||!existingSecret.equals(requestedSecret)))
            throw new IllegalArgumentException("Existing trusted peers cannot be rebound to a different group secret");
        if(existingSecret!=null&&!existingSecret.equals(requestedSecret))
            throw new IllegalArgumentException("Existing pairing uses a different group secret");
        Set<String> merged=new HashSet<>();
        if(existingPeerIds!=null)merged.addAll(existingPeerIds);
        validatePeer(newPeerId);validateSecret(requestedSecret);merged.add(newPeerId);
        return Collections.unmodifiableSet(merged);
    }

    public static synchronized void mergePeerSecret(Context c,String peerId,byte[] peerSecret)throws Exception {
        if(c==null)throw new IllegalArgumentException("Context is required");
        validatePeer(peerId);
        if(peerSecret==null||peerSecret.length!=32)throw new IllegalArgumentException("A 32-byte derived peer secret is required");
        Value current=load(c);
        Map<String,String> merged=mergePeerSecretMap(current==null?Collections.emptyMap():current.peerSecrets,peerId,peerSecret);
        provisionPeerSecrets(c,merged);
    }

    static Map<String,String> mergePeerSecretMap(Map<String,String> existing,String peerId,byte[] peerSecret) {
        validatePeer(peerId);
        if(peerSecret==null||peerSecret.length!=32)throw new IllegalArgumentException("A 32-byte derived peer secret is required");
        Map<String,String> merged=new HashMap<>();
        if(existing!=null) {
            if(existing.size()>256)throw new IllegalArgumentException("Too many existing peer credentials");
            for(Map.Entry<String,String> e:existing.entrySet()){validatePeer(e.getKey());validateSecret(e.getValue());merged.put(e.getKey(),e.getValue());}
        }
        if(!merged.containsKey(peerId)&&merged.size()>=256)throw new IllegalStateException("Trusted peer limit reached");
        merged.put(peerId,Base64.encodeToString(peerSecret,Base64.NO_WRAP));
        return Collections.unmodifiableMap(merged);
    }

    private static void write(Context c,JSONObject payload)throws Exception {
        if(c==null)throw new IllegalArgumentException("Context is required");
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.M)throw new IllegalStateException("Secure transfer credentials require Android 6.0 or later");
        byte[] clear=payload.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,getOrCreateKey());
        byte[] encrypted=cipher.doFinal(clear);
        JSONObject env=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .put("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP));
        if(!c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(CIPHER_KEY,env.toString()).commit())throw new IllegalStateException("Unable to persist encrypted transfer credentials");
        notifyService(c);
    }

    public static Value load(Context c)throws Exception {
        if(c==null||Build.VERSION.SDK_INT<Build.VERSION_CODES.M)return null;
        String sealed=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(CIPHER_KEY,null);
        if(sealed==null||sealed.isEmpty())return null;
        JSONObject env=new JSONObject(sealed);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(GCM_TAG_BITS,Base64.decode(env.getString("iv"),Base64.NO_WRAP)));
        JSONObject payload=new JSONObject(new String(cipher.doFinal(Base64.decode(env.getString("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
        Map<String,String> out=new HashMap<>();String legacy=null;
        if(payload.has("peer_secrets")) {
            JSONObject peers=payload.getJSONObject("peer_secrets");java.util.Iterator<String> it=peers.keys();
            while(it.hasNext()){String id=it.next(),s=peers.getString(id);validatePeer(id);validateSecret(s);out.put(id,s);}
        } else {
            legacy=payload.getString("secret");validateSecret(legacy);JSONArray peers=payload.getJSONArray("trusted_peer_ids");
            for(int i=0;i<peers.length();i++){String id=peers.getString(i);validatePeer(id);out.put(id,legacy);}
        }
        if(out.isEmpty()||out.size()>256)throw new IllegalArgumentException("No valid trusted peer credentials");
        return new Value(legacy,out);
    }

    private static void notifyService(Context c) {
        android.content.Intent i=new android.content.Intent(c,TrackingService.class).setAction("ACTION_LAN_TRANSFER_CREDENTIALS_UPDATED");
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)c.startForegroundService(i);else c.startService(i);
    }
    public static void clear(Context c)throws Exception {
        c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(CIPHER_KEY).commit();
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M){KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(KEY_ALIAS))ks.deleteEntry(KEY_ALIAS);}
        notifyService(c);
    }
    private static SecretKey getOrCreateKey()throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(KEY_ALIAS))return(SecretKey)ks.getKey(KEY_ALIAS,null);
        return Api23.createKey(KEY_ALIAS);
    }
    private static final class Api23 {
        static SecretKey createKey(String alias)throws Exception {
            KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
            g.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return g.generateKey();
        }
    }
    private static void validateSecret(String s) {
        if(s==null||s.getBytes(StandardCharsets.UTF_8).length<32||s.length()>4096)
            throw new IllegalArgumentException("A 32-byte peer credential is required");
    }
    private static void validatePeer(String id) {
        if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Invalid trusted peer identity");
    }
}
