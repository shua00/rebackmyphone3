package com.example.devicefinder;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LanChatRetryPolicyTest {
    @Test public void perTargetRetryBackoffIsBoundedAndDeterministic() {
        assertEquals(250L, LanChatRetryPolicy.retryDelayAfterAttemptMs(1));
        assertEquals(250L, LanChatRetryPolicy.retryDelayAfterAttemptMs(2));
        assertEquals(10_000L, LanChatRetryPolicy.retryDelayAfterAttemptMs(3));
        assertEquals(20_000L, LanChatRetryPolicy.retryDelayAfterAttemptMs(4));
        assertEquals(40_000L, LanChatRetryPolicy.retryDelayAfterAttemptMs(5));
        assertEquals(60_000L, LanChatRetryPolicy.retryDelayAfterAttemptMs(6));
    }

    @Test public void onlyConcreteRecoveryEventsBypassOutboxBackoff() {
        assertEquals(true, LanChatRetryPolicy.shouldImmediatelyRetryForRecovery(true, false));
        assertEquals(true, LanChatRetryPolicy.shouldImmediatelyRetryForRecovery(false, true));
        // ACK-timeout route refresh alone has neither recovery flag and must use Outbox backoff.
        assertEquals(false, LanChatRetryPolicy.shouldImmediatelyRetryForRecovery(false, false));
    }

    @Test public void schedulerWakeDoesNotTurnIntoAnExtraDeliveryAttempt() {
        assertEquals(1_500L, LanChatRetryPolicy.outboxRetryWakeDelayMs(1, false));
        assertEquals(4_000L, LanChatRetryPolicy.outboxRetryWakeDelayMs(2, false));
        assertEquals(10_000L, LanChatRetryPolicy.outboxRetryWakeDelayMs(3, false));
        assertEquals(60_000L, LanChatRetryPolicy.outboxRetryWakeDelayMs(9, false));
    }

    @Test public void unattemptedRouteProbeRemainsResponsiveWithoutResettingRetryBudget() {
        assertEquals(1_500L, LanChatRetryPolicy.outboxRetryWakeDelayMs(1, true));
        assertEquals(4_000L, LanChatRetryPolicy.outboxRetryWakeDelayMs(2, true));
        assertEquals(5_000L, LanChatRetryPolicy.outboxRetryWakeDelayMs(Integer.MAX_VALUE, true));
        assertEquals(Long.MAX_VALUE, LanChatRetryPolicy.outboxRetryWakeDelayMs(Integer.MAX_VALUE, false));
    }
    @Test public void pendingOutboxCapacityIsRaisedAndByteBounded() {
        assertEquals(200, LanChatRetryPolicy.MAX_PENDING_MESSAGES);
        assertEquals(8L * 1024L * 1024L, LanChatRetryPolicy.MAX_PENDING_BODY_BYTES);
    }

}
