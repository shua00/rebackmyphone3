package com.example.devicefinder;

/**
 * Durable LAN chat delivery state vocabulary and pure settlement rules.
 *
 * Outbox state describes the whole message; target status describes one recipient.
 * Keep these concepts separate: a message is delivered only when every frozen target
 * has a durable ACK, while an empty discovery snapshot settles as waiting_peers.
 */
final class LanChatDeliveryState {
    private LanChatDeliveryState() {}

    // Message-level outbox states.
    static final String DISCOVERY_PENDING = "discovery_pending";
    static final String PENDING = "pending";
    static final String RETRY_PAUSED = "retry_paused";
    static final String DELIVERED = "delivered";
    static final String WAITING_PEERS = "waiting_peers";
    static final String EXPIRED = "expired";

    // Recipient-level states. SENT means a dispatch attempt was durably charged.
    static final String TARGET_PENDING = "pending";
    static final String TARGET_SENT = "sent";
    static final String TARGET_ACKED = "acked";

    /**
     * Resolve a closed discovery snapshot using only durable target progress.
     * No recipient rows is not success: it is an explicit waiting-for-peers result.
     */
    static String settleAfterDiscovery(int totalTargets, int ackedTargets,
                                       boolean hasRetryBudget) {
        if (totalTargets <= 0) return WAITING_PEERS;
        if (ackedTargets >= totalTargets) return DELIVERED;
        return hasRetryBudget ? PENDING : RETRY_PAUSED;
    }

    /** Resolve the message after a durable target ACK is recorded. */
    static String settleAfterAck(int totalTargets, int ackedTargets,
                                 boolean hasRetryBudget) {
        if (totalTargets > 0 && ackedTargets >= totalTargets) return DELIVERED;
        return hasRetryBudget ? PENDING : RETRY_PAUSED;
    }
}
