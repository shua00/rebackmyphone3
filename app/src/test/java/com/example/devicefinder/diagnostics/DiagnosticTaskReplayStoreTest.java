package com.example.devicefinder.diagnostics;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticTaskReplayStoreTest {
    private DiagnosticTaskEnvelope task(String taskId, String nonce, long created, long expiry) {
        char[] chars = new char[64];
        Arrays.fill(chars, 'a');
        return new DiagnosticTaskEnvelope(taskId, new String(chars),
                Arrays.asList("phone-a"), Arrays.asList("android.runtime"),
                created, expiry, nonce);
    }

    @Test public void persistsTaskAndNonceReplayProtectionAndPrunesExpiredEntries() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        DiagnosticTaskReplayStore first = new DiagnosticTaskReplayStore(context);
        DiagnosticTaskEnvelope original = task("task-01", "AAAAAAAAAAAAAAAAAAAAAA",
                1800000000000L, 1800000600000L);
        assertTrue(first.acceptOnce(original, 1800000000100L));
        assertFalse(first.acceptOnce(original, 1800000000101L));

        DiagnosticTaskReplayStore afterRestart = new DiagnosticTaskReplayStore(context);
        DiagnosticTaskEnvelope reusedNonce = task("task-02", "AAAAAAAAAAAAAAAAAAAAAA",
                1800000000000L, 1800000600000L);
        assertFalse(afterRestart.acceptOnce(reusedNonce, 1800000000200L));
        DiagnosticTaskEnvelope reusedTaskId = task("task-01", "BBBBBBBBBBBBBBBBBBBBBB",
                1800000000000L, 1800000600000L);
        assertFalse(afterRestart.acceptOnce(reusedTaskId, 1800000000200L));

        // The same task ID and nonce may be reused only after the previous task's expiry.
        DiagnosticTaskEnvelope reusedAfterExpiry = task("task-01", "AAAAAAAAAAAAAAAAAAAAAA",
                1800003600000L, 1800004200000L);
        assertTrue(afterRestart.acceptOnce(reusedAfterExpiry, 1800003600100L));
    }
}
