package com.example.devicefinder;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class LanTransferCredentialsTest {
    @Test public void missingCredentialsKeepTransferListenerDisabled() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        c.getSharedPreferences("lan_transfer_secure_credentials",Context.MODE_PRIVATE).edit().clear().commit();
        assertNull(LanTransferCredentials.load(c));
    }
    @Test public void provisioningRequiresStrongSecretAndExplicitPeerAllowList() throws Exception {
        Context c=RuntimeEnvironment.getApplication();Set<String> peers=new HashSet<>();peers.add("peer-001");
        try{LanTransferCredentials.provision(c,"too-short",peers);fail("weak PSK rejected");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("32-byte"));}
        String strong="0123456789abcdef0123456789abcdef";
        try{LanTransferCredentials.provision(c,strong,Collections.emptySet());fail("empty allowlist rejected");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("allow-list"));}
        peers.clear();peers.add("../untrusted");
        try{LanTransferCredentials.provision(c,strong,peers);fail("malformed ID rejected");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("identity"));}
    }
    @Test public void mergePreservesExistingPeersAndGivesNewPeerIndependentSecret() {
        Map<String,String> old=new HashMap<>();old.put("legacy-a","old-group-secret-0123456789abcdef");
        old.put("legacy-b","old-group-secret-0123456789abcdef");
        byte[] fresh=new byte[32];for(int i=0;i<fresh.length;i++)fresh[i]=(byte)(i+1);
        Map<String,String> merged=LanTransferCredentials.mergePeerSecretMap(old,"new-peer",fresh);
        assertEquals(3,merged.size());
        assertEquals(old.get("legacy-a"),merged.get("legacy-a"));
        assertEquals(old.get("legacy-b"),merged.get("legacy-b"));
        assertNotEquals(old.get("legacy-a"),merged.get("new-peer"));
        try{merged.put("intruder","x");fail("merged map immutable");}
        catch(UnsupportedOperationException expected){}
    }
    @Test public void rePairReplacesOnlyMatchingPeerSecret() {
        Map<String,String> old=new HashMap<>();old.put("peer-a","old-secret-012345678901234567890123");
        old.put("peer-b","other-secret-01234567890123456789012");
        byte[] fresh=new byte[32];java.util.Arrays.fill(fresh,(byte)7);
        Map<String,String> merged=LanTransferCredentials.mergePeerSecretMap(old,"peer-a",fresh);
        assertEquals(old.get("peer-b"),merged.get("peer-b"));
        assertNotEquals(old.get("peer-a"),merged.get("peer-a"));
    }
}
