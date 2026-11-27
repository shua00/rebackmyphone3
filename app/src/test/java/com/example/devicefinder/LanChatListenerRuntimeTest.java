package com.example.devicefinder;

import org.junit.Test;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import static org.junit.Assert.*;

public class LanChatListenerRuntimeTest {
    private static DatagramSocket socket() throws Exception {
        return new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
    }

    @Test public void onePathRegistersOneLeaseWithoutBackgroundRuntime() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        DatagramSocket socket = socket();
        try {
            LanChatListenerRuntime.Lease lease = runtime.register("sta|192.168.1.2/24", socket, 4L);
            assertSame(lease, runtime.get("sta|192.168.1.2/24"));
            assertTrue(runtime.isCurrent(lease));
            assertEquals(1, runtime.listenerCount());
            assertEquals(1, registry.listenerCount());
            assertEquals(1, runtime.snapshot().size());
        } finally {
            socket.close();
        }
    }

    @Test public void independentPathsOwnIndependentSocketsAndGenerations() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        DatagramSocket sta = socket(), ap = socket();
        try {
            LanChatListenerRuntime.Lease staLease =
                    runtime.register("sta|192.168.1.2/24", sta, 10L);
            LanChatListenerRuntime.Lease apLease =
                    runtime.register("ap|192.168.43.1/24", ap, 20L);
            assertEquals(2, runtime.listenerCount());
            assertSame(sta, runtime.get(staLease.pathFingerprint).socket);
            assertSame(ap, runtime.get(apLease.pathFingerprint).socket);
            assertTrue(runtime.isCurrent(staLease));
            assertTrue(runtime.isCurrent(apLease));
        } finally {
            sta.close();
            ap.close();
        }
    }

    @Test public void staleLeaseCannotRemoveReplacementOnSamePath() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        DatagramSocket oldSocket = socket(), newSocket = socket();
        try {
            LanChatListenerRuntime.Lease oldLease =
                    runtime.register("sta|192.168.1.2/24", oldSocket, 30L);
            LanChatListenerRuntime.Lease newLease =
                    runtime.register("sta|192.168.1.2/24", newSocket, 31L);
            assertFalse(runtime.remove(oldLease));
            assertTrue(runtime.isCurrent(newLease));
            assertFalse(runtime.isCurrent(oldLease));
            assertSame(newSocket, runtime.get(newLease.pathFingerprint).socket);
            assertEquals(1, registry.listenerCount());
        } finally {
            oldSocket.close();
            newSocket.close();
        }
    }

    @Test public void removingOnePathLeavesOtherPathAndItsLeaseCurrent() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        DatagramSocket sta = socket(), ap = socket();
        try {
            LanChatListenerRuntime.Lease staLease =
                    runtime.register("sta|192.168.1.2/24", sta, 40L);
            LanChatListenerRuntime.Lease apLease =
                    runtime.register("ap|192.168.43.1/24", ap, 41L);
            assertTrue(runtime.remove(apLease));
            assertFalse(runtime.isCurrent(apLease));
            assertTrue(runtime.isCurrent(staLease));
            assertEquals(1, runtime.listenerCount());
            assertEquals(1, registry.listenerCount());
        } finally {
            sta.close();
            ap.close();
        }
    }

    @Test public void externalPathInvalidationCanRetireTheStaleRuntimeLease() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        DatagramSocket socket = socket();
        try {
            LanChatListenerRuntime.Lease lease =
                    runtime.register("ap|192.168.43.1/24", socket, 49L);
            registry.invalidatePath(lease.pathFingerprint);
            assertFalse(runtime.isCurrent(lease));
            assertFalse(runtime.remove(lease));
            assertEquals(0, runtime.listenerCount());
        } finally {
            socket.close();
        }
    }

    @Test public void snapshotIsImmutableAndEmptySnapshotIsShared() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        assertSame(runtime.snapshot(), runtime.snapshot());
        DatagramSocket socket = socket();
        try {
            runtime.register("sta|192.168.1.2/24", socket, 50L);
            List<LanChatListenerRuntime.Lease> snapshot = runtime.snapshot();
            try {
                snapshot.clear();
                fail("snapshot must be immutable");
            } catch (UnsupportedOperationException expected) { }
        } finally {
            socket.close();
        }
    }
}
