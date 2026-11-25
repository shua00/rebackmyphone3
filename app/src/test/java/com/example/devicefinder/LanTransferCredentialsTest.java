package com.example.devicefinder;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanTransferCredentialsTest {
    @Test
    public void missingCredentialsKeepTransferListenerDisabled() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("lan_transfer_secure_credentials", Context.MODE_PRIVATE)
                .edit().clear().commit();
        assertNull(LanTransferCredentials.load(context));
    }

    @Test
    public void provisioningRequiresStrongSecretAndExplicitPeerAllowList() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Set<String> peers = new HashSet<>();
        peers.add("peer-001");
        try {
            LanTransferCredentials.provision(context, "too-short", peers);
            fail("weak group secret must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("32-byte"));
        }
        String strongSecret = "0123456789abcdef0123456789abcdef";
        try {
            LanTransferCredentials.provision(context, strongSecret, Collections.emptySet());
            fail("empty peer allow-list must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("allow-list"));
        }
        peers.clear();
        peers.add("../untrusted");
        try {
            LanTransferCredentials.provision(context, strongSecret, peers);
            fail("malformed peer identity must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("identity"));
        }
    }
}
