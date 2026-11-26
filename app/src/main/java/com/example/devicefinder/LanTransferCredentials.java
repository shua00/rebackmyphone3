package com.example.devicefinder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    public static final long PENDING_COMMIT_TTL_MS = 24L * 60L * 60L * 1000L;

    public static final class PendingCommit {
        public final String commitId;
        public final String peerId;
        public final String role;
        public final long expiresAtMs;
        private final byte[] secret, localConfirmationProof, remoteConfirmationProof;
        private PendingCommit(String id, String peer, String commitRole, byte[] key, long expires,
                byte[] localProof, byte[] remoteProof) {
            commitId=id; peerId=peer; role=commitRole; secret=key.clone(); expiresAtMs=expires;
            localConfirmationProof=localProof==null?null:localProof.clone();
            remoteConfirmationProof=remoteProof==null?null:remoteProof.clone();
        }
        public byte[] copySecret() { return secret.clone(); }
        public byte[] copyLocalConfirmationProof() {
            return localConfirmationProof==null?null:localConfirmationProof.clone();
        }
        public byte[] copyRemoteConfirmationProof() {
            return remoteConfirmationProof==null?null:remoteConfirmationProof.clone();
        }
    }

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
        JSONObject payload=readPayload(c);
        JSONObject peers=new JSONObject();
        for(Map.Entry<String,String> e:secrets.entrySet()){validatePeer(e.getKey());validateSecret(e.getValue());peers.put(e.getKey(),e.getValue());}
        payload.put("version",3).put("peer_secrets",peers);
        payload.remove("secret"); payload.remove("trusted_peer_ids");
        write(c,payload);
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
        JSONObject payload=readPayload(c);
        Map<String,String> existing=readPeerSecrets(payload);
        Map<String,String> merged=mergePeerSecretMap(existing,peerId,peerSecret);
        JSONObject peers=new JSONObject();
        for(Map.Entry<String,String> entry:merged.entrySet()) peers.put(entry.getKey(),entry.getValue());
        payload.put("version",3).put("peer_secrets",peers);
        write(c,payload);
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

    private static JSONObject emptyPayload() throws Exception {
        return new JSONObject().put("version",3).put("peer_secrets",new JSONObject())
                .put("pending_commits",new JSONObject()).put("commit_receipts",new JSONObject());
    }

    private static JSONObject readPayload(Context c) throws Exception {
        if(c==null)throw new IllegalArgumentException("Context is required");
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.M)throw new IllegalStateException("Secure transfer credentials require Android 6.0 or later");
        String sealed=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(CIPHER_KEY,null);
        if(sealed==null||sealed.isEmpty())return emptyPayload();
        JSONObject env=new JSONObject(sealed);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(GCM_TAG_BITS,
                Base64.decode(env.getString("iv"),Base64.NO_WRAP)));
        JSONObject payload=new JSONObject(new String(cipher.doFinal(
                Base64.decode(env.getString("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
        JSONObject peers;
        if(payload.has("peer_secrets")) peers=payload.getJSONObject("peer_secrets");
        else {
            peers=new JSONObject();
            if(payload.has("secret")) {
                String legacy=payload.getString("secret");validateSecret(legacy);
                JSONArray ids=payload.getJSONArray("trusted_peer_ids");
                for(int i=0;i<ids.length();i++) {
                    String id=ids.getString(i);validatePeer(id);peers.put(id,legacy);
                }
            }
        }
        payload.put("version",3).put("peer_secrets",peers);
        if(!payload.has("pending_commits"))payload.put("pending_commits",new JSONObject());
        if(!payload.has("commit_receipts"))payload.put("commit_receipts",new JSONObject());
        return payload;
    }

    private static Map<String,String> readPeerSecrets(JSONObject payload)throws Exception {
        Map<String,String> out=new HashMap<>();
        JSONObject peers=payload.optJSONObject("peer_secrets");
        if(peers==null)return out;
        java.util.Iterator<String> it=peers.keys();
        while(it.hasNext()) {
            String id=it.next(),secret=peers.getString(id);
            validatePeer(id);validateSecret(secret);out.put(id,secret);
        }
        return out;
    }

    public static synchronized void stagePendingCommit(Context c,String commitId,String peerId,
            byte[] secret,long expiresAtMs)throws Exception {
        stagePendingCommit(c,commitId,peerId,secret,expiresAtMs,null,null,"INITIATOR");
    }

    /** Stores the provisional secret and, for responders, both SAS confirmation proofs for crash recovery. */
    public static synchronized void stagePendingCommit(Context c,String commitId,String peerId,
            byte[] secret,long expiresAtMs,byte[] localProof,byte[] remoteProof)throws Exception {
        stagePendingCommit(c,commitId,peerId,secret,expiresAtMs,localProof,remoteProof,
                localProof == null ? "INITIATOR" : "RESPONDER");
    }

    public static synchronized void stagePendingCommit(Context c,String commitId,String peerId,
            byte[] secret,long expiresAtMs,byte[] localProof,byte[] remoteProof,String role)throws Exception {
        validateCommitInput(commitId,peerId,secret);
        if(expiresAtMs<=System.currentTimeMillis())throw new IllegalArgumentException("Pending commit expiry must be in the future");
        if((localProof==null)!=(remoteProof==null)
                ||(localProof!=null&&(localProof.length!=32||remoteProof.length!=32))
                ||!("INITIATOR".equals(role)||"RESPONDER".equals(role))
                ||("RESPONDER".equals(role)!=(localProof!=null)))
            throw new IllegalArgumentException("Pending commit role and recovery proofs are inconsistent");
        JSONObject payload=readPayload(c);
        JSONObject pending=payload.getJSONObject("pending_commits");
        JSONObject existing=pending.optJSONObject(commitId);
        String encoded=Base64.encodeToString(secret,Base64.NO_WRAP);
        if(existing!=null) {
            if(!peerId.equals(existing.getString("peer_id"))
                    ||!encoded.equals(existing.getString("secret"))
                    ||!role.equals(existing.optString("role","INITIATOR")))
                throw new SecurityException("Pending commit identifier is already bound to different material");
            if(localProof!=null) {
                existing.put("local_proof",Base64.encodeToString(localProof,Base64.NO_WRAP));
                existing.put("remote_proof",Base64.encodeToString(remoteProof,Base64.NO_WRAP));
            }
            existing.put("expires_at_ms",Math.max(expiresAtMs,existing.optLong("expires_at_ms",0L)));
        } else {
            java.util.Iterator<String> keys=pending.keys();
            while(keys.hasNext()) {
                JSONObject other=pending.getJSONObject(keys.next());
                if(peerId.equals(other.optString("peer_id")))
                    throw new IllegalStateException("Another pending pairing commit already exists for this peer");
            }
            if(pending.length()>=32)throw new IllegalStateException("Pending pairing commit limit reached");
            JSONObject item=new JSONObject().put("peer_id",peerId).put("secret",encoded)
                    .put("expires_at_ms",expiresAtMs).put("role",role);
            if(localProof!=null)item.put("local_proof",Base64.encodeToString(localProof,Base64.NO_WRAP))
                    .put("remote_proof",Base64.encodeToString(remoteProof,Base64.NO_WRAP));
            pending.put(commitId,item);
        }
        write(c,payload);
    }

    public static synchronized PendingCommit getPendingCommit(Context c,String commitId)throws Exception {
        validateCommitId(commitId);
        JSONObject payload=readPayload(c);
        JSONObject item=payload.getJSONObject("pending_commits").optJSONObject(commitId);
        return item==null?null:decodePending(commitId,item);
    }

    public static synchronized List<PendingCommit> loadPendingCommits(Context c)throws Exception {
        JSONObject pending=readPayload(c).getJSONObject("pending_commits");
        List<PendingCommit> out=new ArrayList<>();
        java.util.Iterator<String> keys=pending.keys();
        while(keys.hasNext()) {
            String id=keys.next();out.add(decodePending(id,pending.getJSONObject(id)));
        }
        return Collections.unmodifiableList(out);
    }

    private static PendingCommit decodePending(String id,JSONObject item)throws Exception {
        validateCommitId(id);String peer=item.getString("peer_id");validatePeer(peer);
        byte[] secret=Base64.decode(item.getString("secret"),Base64.NO_WRAP);
        if(secret.length!=32)throw new IllegalArgumentException("Stored pending peer secret is invalid");
        byte[] local=item.has("local_proof")?Base64.decode(item.getString("local_proof"),Base64.NO_WRAP):null;
        byte[] remote=item.has("remote_proof")?Base64.decode(item.getString("remote_proof"),Base64.NO_WRAP):null;
        if((local==null)!=(remote==null)||(local!=null&&(local.length!=32||remote.length!=32)))
            throw new IllegalArgumentException("Stored pending confirmation recovery proofs are invalid");
        String role=item.optString("role",local==null?"INITIATOR":"RESPONDER");
        if(!("INITIATOR".equals(role)||"RESPONDER".equals(role))
                ||("RESPONDER".equals(role)!=(local!=null)))
            throw new IllegalArgumentException("Stored pending commit role is invalid");
        return new PendingCommit(id,peer,role,secret,item.getLong("expires_at_ms"),local,remote);
    }

    /** Responder-side atomic credential + commit-receipt write; duplicate requests are idempotent. */
    public static synchronized void commitPeerSecret(Context c,String commitId,String peerId,
            byte[] secret)throws Exception {
        commitPeerSecretInternal(c,commitId,peerId,secret,false);
    }

    /** Initiator-side finalization requires its encrypted pending journal entry. */
    public static synchronized void finishPendingCommit(Context c,String commitId,String peerId,
            byte[] secret)throws Exception {
        commitPeerSecretInternal(c,commitId,peerId,secret,true);
    }

    private static void commitPeerSecretInternal(Context c,String commitId,String peerId,
            byte[] secret,boolean requirePending)throws Exception {
        validateCommitInput(commitId,peerId,secret);
        JSONObject payload=readPayload(c);
        JSONObject pending=payload.getJSONObject("pending_commits");
        JSONObject receipts=payload.getJSONObject("commit_receipts");
        String digest=sha256(secret);
        JSONObject receipt=receipts.optJSONObject(commitId);
        if(receipt!=null) {
            if(!peerId.equals(receipt.getString("peer_id"))
                    ||!digest.equals(receipt.getString("secret_sha256")))
                throw new SecurityException("Commit identifier is already finalized with different peer material");
            pending.remove(commitId);
            write(c,payload);
            return;
        }
        JSONObject staged=pending.optJSONObject(commitId);
        if(requirePending&&(staged==null||!peerId.equals(staged.getString("peer_id"))
                ||!Base64.encodeToString(secret,Base64.NO_WRAP).equals(staged.getString("secret"))))
            throw new SecurityException("Local pending commit does not match the acknowledged pairing");
        JSONObject peers=payload.getJSONObject("peer_secrets");
        if(!peers.has(peerId)&&peers.length()>=256)throw new IllegalStateException("Trusted peer limit reached");
        peers.put(peerId,Base64.encodeToString(secret,Base64.NO_WRAP));
        receipts.put(commitId,new JSONObject().put("peer_id",peerId)
                .put("secret_sha256",digest).put("committed_at_ms",System.currentTimeMillis()));
        trimReceipts(receipts);
        pending.remove(commitId);
        payload.put("version",3);
        write(c,payload);
    }

    public static synchronized boolean hasCommitReceipt(Context c,String commitId,String peerId,
            byte[] secret)throws Exception {
        validateCommitInput(commitId,peerId,secret);
        JSONObject receipt=readPayload(c).getJSONObject("commit_receipts").optJSONObject(commitId);
        return receipt!=null&&peerId.equals(receipt.optString("peer_id"))
                &&sha256(secret).equals(receipt.optString("secret_sha256"));
    }

    public static synchronized byte[] getCommittedPeerSecretBytes(Context c,String peerId)throws Exception {
        validatePeer(peerId);Value value=load(c);
        if(value==null)return null;
        String stored=value.secretFor(peerId);
        if(stored==null)return null;
        if(value.sharedSecret!=null&&value.sharedSecret.equals(stored))
            return stored.getBytes(StandardCharsets.UTF_8);
        byte[] decoded=Base64.decode(stored,Base64.NO_WRAP);
        if(decoded.length!=32)throw new IllegalArgumentException("Stored peer credential is invalid");
        return decoded;
    }

    public static synchronized void clearExpiredPendingCommits(Context c,long nowMs)throws Exception {
        JSONObject payload=readPayload(c);
        JSONObject pending=payload.getJSONObject("pending_commits");
        boolean changed=false;
        java.util.Iterator<String> keys=pending.keys();
        List<String> remove=new ArrayList<>();
        while(keys.hasNext()) {
            String id=keys.next();
            if(pending.getJSONObject(id).optLong("expires_at_ms",0L)<=nowMs)remove.add(id);
        }
        for(String id:remove){pending.remove(id);changed=true;}
        if(changed)write(c,payload);
    }

    private static void trimReceipts(JSONObject receipts)throws Exception {
        while(receipts.length()>512) {
            String oldest=null;long oldestAt=Long.MAX_VALUE;
            java.util.Iterator<String> keys=receipts.keys();
            while(keys.hasNext()) {
                String id=keys.next();long at=receipts.getJSONObject(id).optLong("committed_at_ms",0L);
                if(at<oldestAt){oldestAt=at;oldest=id;}
            }
            if(oldest==null)break;receipts.remove(oldest);
        }
    }

    private static void validateCommitInput(String id,String peer,byte[] secret) {
        validateCommitId(id);validatePeer(peer);
        if(secret==null||secret.length!=32)throw new IllegalArgumentException("A 32-byte derived peer secret is required");
    }
    private static void validateCommitId(String id) {
        try { if(id==null||!UUID.fromString(id).toString().equalsIgnoreCase(id))
            throw new IllegalArgumentException("Invalid pairing commit identifier"); }
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid pairing commit identifier");}
    }
    private static String sha256(byte[] value)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value);
        return Base64.encodeToString(digest,Base64.NO_WRAP);
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
        JSONObject payload=readPayload(c);
        Map<String,String> out=readPeerSecrets(payload);
        if(out.isEmpty())return null; // A pending pairing journal is not yet a trusted peer.
        if(out.size()>256)throw new IllegalArgumentException("Too many trusted peer credentials");
        String legacy=payload.has("secret")?payload.getString("secret"):null;
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
