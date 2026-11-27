package com.example.devicefinder;

/**
 * Pure LAN chat delivery policy.
 *
 * Keep timing/capacity decisions here so the durable store owns transactions and
 * the service owns orchestration, without duplicating retry policy in either layer.
 * Values are compile-time defaults intended for controlled tuning and regression tests.
 */
final class LanChatRetryPolicy {
    private LanChatRetryPolicy() {}

    static final long FIRST_DISCOVERY_WINDOW_MS = 10_000L;
    static final int MAX_PENDING_MESSAGES = 200;
    /** Unconfirmed messages expire after seven days; history is retained. */
    static final long OUTBOX_MESSAGE_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    // Bound aggregate UTF-8 message bodies as well as row count; metadata/index overhead is additional.
    static final long MAX_PENDING_BODY_BYTES = 8L * 1024L * 1024L;
    static final int MAX_AUTOMATIC_RETRY_ATTEMPTS = 8;
    static final long RETRY_RECOVERY_EVENT_COOLDOWN_MS = 60_000L;

    static final long PEER_DISCOVERY_COOLDOWN_MS = 1_500L;
    static final long PEER_CHALLENGE_TIMEOUT_MS = 2_500L;

    // Outbox wake cadence and bounded work per wake. Keep these separate from per-target
    // dispatch backoff: a scheduler wake is not itself a delivery attempt.
    static final long OUTBOX_RETRY_FIRST_DELAY_MS = 1_500L;
    static final long OUTBOX_RETRY_SECOND_DELAY_MS = 4_000L;
    static final long OUTBOX_RETRY_BACKOFF_BASE_MS = 10_000L;
    static final long OUTBOX_RETRY_MAX_DELAY_MS = 60_000L;
    static final long RECOVERY_FAILURE_RETRY_DELAY_MS = 500L;
    static final long UNATTEMPTED_ROUTE_PROBE_MAX_DELAY_MS = 5_000L;
    static final int RETRY_BATCH_SIZE = 3;
    static final int MAX_RETRY_BATCHES_PER_WAKE = 3;

    static long outboxRetryWakeDelayMs(int minimumAttempts, boolean hasUnattemptedRouteTargets) {
        long sendRetryDelayMs = minimumAttempts == Integer.MAX_VALUE ? Long.MAX_VALUE
                : (minimumAttempts <= 1 ? OUTBOX_RETRY_FIRST_DELAY_MS
                : (minimumAttempts == 2 ? OUTBOX_RETRY_SECOND_DELAY_MS
                : Math.min(OUTBOX_RETRY_MAX_DELAY_MS,
                        OUTBOX_RETRY_BACKOFF_BASE_MS << Math.min(3, minimumAttempts - 3))));
        return hasUnattemptedRouteTargets
                ? Math.min(UNATTEMPTED_ROUTE_PROBE_MAX_DELAY_MS, sendRetryDelayMs)
                : sendRetryDelayMs;
    }

    /** Retry delays after completed attempts 1..5; later attempts use the ceiling below. */
    private static final long[] RETRY_DELAY_AFTER_ATTEMPT_MS =
            {250L, 250L, 10_000L, 20_000L, 40_000L};
    private static final long RETRY_DELAY_CEILING_MS = 60_000L;

    static long retryDelayAfterAttemptMs(int completedAttempts) {
        if (completedAttempts >= 1 && completedAttempts <= RETRY_DELAY_AFTER_ATTEMPT_MS.length) {
            return RETRY_DELAY_AFTER_ATTEMPT_MS[completedAttempts - 1];
        }
        return RETRY_DELAY_CEILING_MS;
    }

    /**
     * Generate the fixed SQL expression from the same values used by the Java policy.
     * This avoids a second, drifting copy of retry timing inside the persistence layer.
     */
    private static String buildTargetRetryDelayCaseSql() {
        StringBuilder sql = new StringBuilder("CASE attempt_count ");
        for (int i = 0; i < RETRY_DELAY_AFTER_ATTEMPT_MS.length; i++) {
            sql.append("WHEN ").append(i + 1).append(" THEN ")
                     .append(retryDelayAfterAttemptMs(i + 1)).append(' ');
        }
        return sql.append("ELSE ").append(RETRY_DELAY_CEILING_MS).append(" END").toString();
    }

    static final String TARGET_RETRY_DELAY_CASE_SQL = buildTargetRetryDelayCaseSql();

}
