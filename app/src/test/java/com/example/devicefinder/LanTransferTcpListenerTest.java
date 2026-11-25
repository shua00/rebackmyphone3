package com.example.devicefinder;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanTransferTcpListenerTest {
    private static InetAddress nonLoopbackAddress() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!address.isLoopbackAddress() && !address.isAnyLocalAddress())
                    return address;
            }
        }
        return null;
    }

    @Test
    public void rejectsMissingOrWeakTransferCredentials() {
        Context context = RuntimeEnvironment.getApplication();
        LanTransferStore store = new LanTransferStore(context);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, new LanTransferFilesystem.DurableFileOps() {
                    @Override public void syncDirectory(java.io.File directory) {}
                    @Override public void atomicRename(java.io.File source, java.io.File target)
                            throws java.io.IOException {
                        if (!source.renameTo(target)) throw new java.io.IOException("rename failed");
                    }
                });
        try {
            new LanTransferTcpListener(context, store, filesystem, "receiver", "short",
                    ignored -> true, null);
            fail("weak secret must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("provisioned secret"));
        } finally {
            store.closeStore();
        }
    }

    @Test
    public void listenerBindsConcreteAddressAndClosesAcceptLoop() throws Exception {
        InetAddress address = nonLoopbackAddress();
        assumeTrue("No non-loopback interface available in this test environment", address != null);
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("lan_p2p_transfer.db");
        LanTransferStore store = new LanTransferStore(context);
        LanTransferFilesystem filesystem = new LanTransferFilesystem(context, store,
                LanTransferFilesystem.DEFAULT_STAGING_QUOTA_BYTES, new LanTransferFilesystem.DurableFileOps() {
                    @Override public void syncDirectory(java.io.File directory) {}
                    @Override public void atomicRename(java.io.File source, java.io.File target)
                            throws java.io.IOException {
                        if (!source.renameTo(target)) throw new java.io.IOException("rename failed");
                    }
                });
        LanTransferTcpListener listener = new LanTransferTcpListener(context, store, filesystem,
                "receiver", "test-transfer-secret-0123456789", ignored -> true, null);
        try {
            try {
                listener.start(InetAddress.getLoopbackAddress(), 0);
                fail("loopback must not be accepted as a Wi-Fi bind address");
            } catch (IllegalArgumentException expected) {
                // expected
            }
            listener.start(address, 0);
            assertTrue(listener.isRunning());
            assertTrue(listener.getBoundPort() > 0);
            try (Socket client = new Socket(address, listener.getBoundPort())) {
                assertTrue(client.isConnected());
                assertEquals(address, client.getLocalAddress());
            }
        } finally {
            listener.close();
            assertFalse(listener.isRunning());
            store.closeStore();
        }
    }
}
