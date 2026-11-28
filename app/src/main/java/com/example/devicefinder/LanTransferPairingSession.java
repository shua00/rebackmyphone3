package com.example.devicefinder;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, transport-independent state machine for the two-sided file-transfer pairing ceremony. */
public final class LanTransferPairingSession {
    public enum Role { INITIATOR, RESPONDER }
    public enum State { WAITING_LOCAL_CONFIRMATION, WAITING_REMOTE_CONFIRMATION, READY_TO_COMMIT, REJECTED, CANCELLED, EXPIRED }
    private static final byte[] CONFIRM_DOMAIN="rebackmyphone3/lan-transfer-pairing/v1/confirmation\0".getBytes(StandardCharsets.UTF_8);
    private final String localPeerId,remotePeerId;
    private final Role localRole;
    private final long expiresAtElapsedMs;
    private byte[] peerSecret,transcriptHash;
    private String expectedSas;
    private boolean localConfirmed,remoteConfirmed;
    private State terminalState;

    public LanTransferPairingSession(String localPeerId,String remotePeerId,Role localRole,
            LanTransferPairingCrypto.Result material,long nowElapsedMs,long timeoutMs) {
        if(!validPeer(localPeerId)||!validPeer(remotePeerId)||localPeerId.equals(remotePeerId)||localRole==null
                ||material==null||material.peerSecret==null||material.peerSecret.length!=32
                ||material.transcriptHash==null||material.transcriptHash.length!=32
                ||material.shortAuthenticationCode==null||!material.shortAuthenticationCode.matches("[0-9]{6}")
                ||timeoutMs<1000L||timeoutMs>10*60_000L||nowElapsedMs<0L||nowElapsedMs>Long.MAX_VALUE-timeoutMs)
            throw new IllegalArgumentException("Invalid pairing session configuration");
        this.localPeerId=localPeerId;this.remotePeerId=remotePeerId;this.localRole=localRole;
        this.peerSecret=material.peerSecret.clone();this.transcriptHash=material.transcriptHash.clone();
        this.expectedSas=material.shortAuthenticationCode;this.expiresAtElapsedMs=nowElapsedMs+timeoutMs;
    }
    public synchronized State state(long now) {
        expireIfNeeded(now);if(terminalState!=null)return terminalState;
        if(localConfirmed&&remoteConfirmed)return State.READY_TO_COMMIT;
        return localConfirmed?State.WAITING_REMOTE_CONFIRMATION:State.WAITING_LOCAL_CONFIRMATION;
    }
    public synchronized void confirmLocal(String sas,long now) {
        requireActive(now);if(localConfirmed)throw new IllegalStateException("Local confirmation already recorded");
        byte[] supplied=sas==null?new byte[0]:sas.getBytes(StandardCharsets.US_ASCII);
        if(!MessageDigest.isEqual(expectedSas.getBytes(StandardCharsets.US_ASCII),supplied)){terminate(State.REJECTED);return;}
        localConfirmed=true;
    }
    public synchronized byte[] createLocalConfirmationProof(long now)throws GeneralSecurityException {
        // The proof must remain available after the peer's proof arrives: the responder
        // sends its ACK only after both confirmations have been recorded.
        expireIfNeeded(now);
        if(terminalState!=null)throw new IllegalStateException("Pairing session is terminal");
        if(!localConfirmed)throw new IllegalStateException("Local user has not confirmed SAS");
        return confirmationProof(localRole,true);
    }
    /** Explicit user rejection returns an authenticated negative decision, then destroys local session secrets. */
    public synchronized byte[] rejectLocal(long now)throws GeneralSecurityException {
        requireActive(now);
        byte[] proof=confirmationProof(localRole,false);
        terminate(State.REJECTED);
        return proof;
    }
    public synchronized void applyRemoteConfirmation(boolean accepted,byte[] proof,long now)throws GeneralSecurityException {
        expireIfNeeded(now);
        if(terminalState!=null)throw new IllegalStateException("Pairing session is terminal");
        byte[] expected=confirmationProof(opposite(localRole),accepted);
        if(remoteConfirmed) {
            // A peer may retry CONFIRM with a fresh transport sequence after its ACK was
            // lost. Accept only the exact same transcript-bound decision; never apply it twice.
            boolean identical=proof!=null&&MessageDigest.isEqual(expected,proof);
            Arrays.fill(expected,(byte)0);
            if(identical)return;
            terminate(State.REJECTED);
            throw new SecurityException("Conflicting remote confirmation replay");
        }
        if(localConfirmed&&remoteConfirmed) {
            Arrays.fill(expected,(byte)0);
            throw new IllegalStateException("Pairing is ready to commit");
        }
        if(proof==null||!MessageDigest.isEqual(expected,proof)){Arrays.fill(expected,(byte)0);terminate(State.REJECTED);return;}
        Arrays.fill(expected,(byte)0);
        if(!accepted){terminate(State.REJECTED);return;}
        remoteConfirmed=true;
    }
    public synchronized void cancel(long now){expireIfNeeded(now);if(terminalState==null)terminate(State.CANCELLED);}
    public synchronized void disconnect(long now){cancel(now);}
    public synchronized boolean canCommit(long now){return state(now)==State.READY_TO_COMMIT;}
    public synchronized byte[] peerSecretForCommit(long now) {
        if(!canCommit(now)||peerSecret==null)throw new IllegalStateException("Both pairing confirmations are required before commit");
        return peerSecret.clone();
    }
    public String localPeerId(){return localPeerId;}
    public String remotePeerId(){return remotePeerId;}
    public Role localRole(){return localRole;}
    private byte[] confirmationProof(Role role,boolean accepted)throws GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(peerSecret,"HmacSHA256"));
        mac.update(CONFIRM_DOMAIN);mac.update(transcriptHash);mac.update(role.name().getBytes(StandardCharsets.US_ASCII));
        mac.update((byte)(accepted?1:0));return mac.doFinal();
    }
    private void requireActive(long now) {
        expireIfNeeded(now);if(terminalState!=null)throw new IllegalStateException("Pairing session is terminal");
        if(localConfirmed&&remoteConfirmed)throw new IllegalStateException("Pairing is ready to commit");
    }
    private void expireIfNeeded(long now) {
        if(terminalState==null&&now>=expiresAtElapsedMs)terminate(State.EXPIRED);
    }
    private void terminate(State state) {
        terminalState=state;if(peerSecret!=null)Arrays.fill(peerSecret,(byte)0);
        if(transcriptHash!=null)Arrays.fill(transcriptHash,(byte)0);
        expectedSas=null;peerSecret=null;transcriptHash=null;
    }
    private static Role opposite(Role role){return role==Role.INITIATOR?Role.RESPONDER:Role.INITIATOR;}
    private static boolean validPeer(String peer){return peer!=null&&peer.matches("[A-Za-z0-9._:-]{1,128}");}
}
