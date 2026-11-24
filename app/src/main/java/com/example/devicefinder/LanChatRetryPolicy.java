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

    /**
     * Delay before the next attempt, indexed by the already completed attempt count.
     * Attempt 1/2 are a short burst; later retries back off to a 60s ceiling.
     */
    static long retryDelayAfterAttemptMs(int completedAttempts) {
        switch (completedAttempts) {
            case 1: return 250L;
            case 2: return 250L;
            case 3: return 10_000L;
            case 4: return 20_000L;
            case 5: return 40_000L;
            default: return 60_000L;
        }
    }

    /**
     * SQL equivalent of retryDelayAfterAttemptMs for an already validated column name.
     * The returned fragment is deliberately fixed; callers must not pass user input.
     */
    static final String TARGET_RETRY_DELAY_CASE_SQL =
            "CASE attempt_count WHEN 1 THEN 250 WHEN 2 THEN 250 " +
            "WHEN 3 THEN 10000 WHEN 4 THEN 20000 WHEN 5 THEN 40000 ELSE 60000 END";
}
