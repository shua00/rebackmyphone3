package com.example.devicefinder;

import android.content.Context;
import java.util.List;

/** Process-local, read-only lifecycle snapshot for the independent file-transfer LAN plane. */
public final class LanTransferRuntimeDiagnostics {
    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_STOPPED = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_FAILED = 3;
    // Stable diagnostic wire/report code: an Android permission blocked this subsystem.
    public static final int STATE_PERMISSION_DENIED = 4;
    private static volatile int discoveryState = STATE_UNKNOWN;
    private static volatile int tcpListenerState = STATE_UNKNOWN;

    private LanTransferRuntimeDiagnostics() {}
    public static void publishDiscoveryState(int state) {
        discoveryState = validState(state) ? state : STATE_FAILED;
    }
    public static void publishTcpState(int state) {
        tcpListenerState = validState(state) ? state : STATE_FAILED;
    }
    public static Snapshot snapshot(Context context, long nowMs) {
        int peers = 0;
        long age = 0L;
        if (context != null && nowMs > 0L) {
            try {
                List<LanTransferPeerDirectory.Candidate> current =
                        LanTransferPeerDirectory.loadCurrent(context, nowMs);
                peers = current.size();
                boolean first = true;
                for (LanTransferPeerDirectory.Candidate candidate : current) {
                    long candidateAge = Math.max(0L, nowMs - candidate.lastSeenAtMs);
                    if (first || candidateAge < age) age = candidateAge;
                    first = false;
                }
            } catch (RuntimeException ignored) {
                // Lifecycle state remains useful if the optional endpoint cache is unreadable.
            }
        }
        return new Snapshot(discoveryState, tcpListenerState, peers, age);
    }
    static void resetForTests() {
        discoveryState = STATE_UNKNOWN;
        tcpListenerState = STATE_UNKNOWN;
    }
    private static boolean validState(int state) { return state >= STATE_UNKNOWN && state <= STATE_PERMISSION_DENIED; }

    public static final class Snapshot {
        public final int discoveryState, tcpListenerState, freshPeerCount;
        public final long latestPeerAgeMs;
        Snapshot(int discovery, int tcp, int peers, long age) {
            discoveryState = discovery;
            tcpListenerState = tcp;
            freshPeerCount = peers;
            latestPeerAgeMs = age;
        }
    }
}
