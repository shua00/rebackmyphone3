package com.example.devicefinder;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded in-memory replay gate for pairing-control envelopes; never an authentication source. */
public final class LanTransferPairingReplayGuard {
    private static final int MAX_SESSIONS = 256;
    private final long ttlMs;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>(32, .75f, true);
    private static final class Entry { long lastSequence, expiresAt; Entry(long seq,long expiry){lastSequence=seq;expiresAt=expiry;} }

    public LanTransferPairingReplayGuard(long ttlMs) {
        if (ttlMs < 1_000L || ttlMs > 10 * 60_000L) throw new IllegalArgumentException("Invalid replay window");
        this.ttlMs = ttlMs;
    }

    /** Accepts strictly increasing sequence numbers per (session, sender); rejects replay/out-of-order. */
    public synchronized boolean accept(String sessionId, String senderPeerId, long sequence, long nowMs) {
        if (sessionId == null || sessionId.isEmpty() || senderPeerId == null || senderPeerId.isEmpty()
                || sequence < 1L || nowMs < 0L || nowMs > Long.MAX_VALUE - ttlMs) return false;
        java.util.Iterator<Map.Entry<String,Entry>> it=entries.entrySet().iterator();
        while(it.hasNext()) if(nowMs >= it.next().getValue().expiresAt) it.remove();
        String key=sessionId + "\0" + senderPeerId;
        Entry old=entries.get(key);
        if(old!=null && sequence<=old.lastSequence) return false;
        // Fail closed at capacity: evicting a live session would reopen its replay window.
        if(old==null && entries.size()>=MAX_SESSIONS) return false;
        entries.put(key,new Entry(sequence,nowMs+ttlMs));
        return true;
    }
}
