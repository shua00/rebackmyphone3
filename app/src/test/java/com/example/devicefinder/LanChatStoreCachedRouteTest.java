package com.example.devicefinder;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanChatStoreCachedRouteTest {
    @Test public void cachedRouteRequiresExactUnexpiredListenerEpochAndEndpoint() {
        Context context = RuntimeEnvironment.getApplication();
        LanChatStore store = new LanChatStore(context);
        try {
            store.getWritableDatabase();
            assertTrue(store.upsertPeer("peer-1", "android", "android-main", "remote-scope",
                    "192.168.1.55", 8888, 1_000L, 3_000L, "wifi-path-a", 7L, "service-instance-1"));

            assertFalse(store.hasFreshVerifiedPeerRoute("peer-1", "192.168.1.55",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertFalse(store.markPeerRouteRegistryAdmitted("peer-1", "192.168.1.55",
                    8888, 2_000L, "wifi-path-a", 8L, "service-instance-1"));
            assertFalse(store.markPeerRouteRegistryAdmitted("peer-1", "192.168.1.56",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertFalse(store.markPeerRouteRegistryAdmitted("peer-1", "192.168.1.55",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-2"));
            assertTrue(store.markPeerRouteRegistryAdmitted("peer-1", "192.168.1.55",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertTrue(store.hasFreshVerifiedPeerRoute("peer-1", "192.168.1.55",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertFalse(store.hasFreshVerifiedPeerRoute("peer-1", "192.168.1.55",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-2"));
            assertFalse(store.hasFreshVerifiedPeerRoute("peer-1", "192.168.1.55",
                    8888, 3_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertFalse(store.hasFreshVerifiedPeerRoute("peer-1", "192.168.1.55",
                    8888, 2_000L, "wifi-path-a", 8L, "service-instance-1"));
            assertFalse(store.hasFreshVerifiedPeerRoute("peer-1", "192.168.1.56",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertFalse(store.hasFreshVerifiedPeerRoute("peer-1", "192.168.1.55",
                    8889, 2_000L, "wifi-path-a", 7L, "service-instance-1"));

            assertTrue(store.upsertPeer("peer-2", "android", "android-main", "scope-a",
                    "192.168.1.56", 8888, 1_000L, 3_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertTrue(store.markPeerRouteRegistryAdmitted("peer-2", "192.168.1.56",
                    8888, 2_000L, "wifi-path-a", 7L, "service-instance-1"));
            assertTrue(store.observePeer("peer-2", "android", "android-main", "scope-b",
                    "192.168.1.56", 8888, 2_000L, 4_000L));
            assertFalse(store.hasFreshVerifiedPeerRoute("peer-2", "192.168.1.56",
                    8888, 2_500L, "wifi-path-a", 7L, "service-instance-1"));
        } finally {
            store.closeStore();
        }
    }
}
