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
    static final int MAX_PENDING_MESSAGES = 50;
    static final int MAX_AUTOMATIC_RETRY_ATTEMPTS = 8;
    static final long RETRY_RECOVERY_EVENT_COOLDOWN_MS = 60_000L;

    static final long PEER_DISCOVERY_COOLDOWN_MS = 1_500L;
    static final long PEER_CHALLENGE_TIMEOUT_MS = 2_500L;

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
                    .append(RETRY_DELAY_AFTER_ATTEMPT_MS[i]).append(' ');
        }
        return sql.append("ELSE ").append(RETRY_DELAY_CEILING_MS).append(" END").toString();
    }

    static final String TARGET_RETRY_DELAY_CASE_SQL = buildTargetRetryDelayCaseSql();

}
