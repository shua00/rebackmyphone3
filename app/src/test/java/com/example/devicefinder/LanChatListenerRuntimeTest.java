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

    @Test public void registrationRejectsUnboundOrInvalidListenerInputs() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        DatagramSocket unbound = new DatagramSocket(null);
        try {
            try {
                runtime.register("sta|192.168.1.2/24", unbound, 1L);
                fail("unbound sockets must never become listener leases");
            } catch (IllegalArgumentException expected) { }
            assertEquals(0, runtime.listenerCount());
            assertEquals(0, registry.listenerCount());
            try {
                runtime.register(" ", unbound, 1L);
                fail("empty path identity must be rejected");
            } catch (IllegalArgumentException expected) { }
        } finally {
            unbound.close();
        }
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

    @Test public void receiveLoopInstancesAreOwnedIndependentlyByPath() {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        LanChatListenerRuntime runtime = new LanChatListenerRuntime(registry);
        LanChatListenerInstance sta =
                new LanChatListenerInstance("sta|192.168.1.2/24", 60L);
        LanChatListenerInstance ap =
                new LanChatListenerInstance("ap|192.168.43.1/24", 61L);
        assertTrue(runtime.installInstance(sta));
        assertTrue(runtime.installInstance(ap));
        assertEquals(2, runtime.instanceCount());
        assertSame(sta, runtime.getInstance(sta.pathFingerprint));
        assertSame(ap, runtime.getInstance(ap.pathFingerprint));
        assertTrue(runtime.removeInstance(ap));
        assertFalse(runtime.removeInstance(ap));
        assertSame(sta, runtime.getInstance(sta.pathFingerprint));
        assertEquals(1, runtime.instanceCount());
    }

    @Test public void staleReceiveLoopCannotRemoveReplacementForSamePath() {
        LanChatListenerRuntime runtime =
                new LanChatListenerRuntime(new LanChatPathRegistry());
        String path = "ap|192.168.43.1/24";
        LanChatListenerInstance oldInstance = new LanChatListenerInstance(path, 70L);
        LanChatListenerInstance newInstance = new LanChatListenerInstance(path, 71L);
        assertTrue(runtime.installInstance(oldInstance));
        assertFalse(runtime.installInstance(newInstance));
        assertTrue(runtime.removeInstance(oldInstance));
        assertTrue(runtime.installInstance(newInstance));
        assertFalse(runtime.removeInstance(oldInstance));
        assertSame(newInstance, runtime.getInstance(path));
        assertEquals(1, runtime.instanceCount());
    }

    @Test public void receiveLoopInstanceSnapshotIsImmutable() {
        LanChatListenerRuntime runtime =
                new LanChatListenerRuntime(new LanChatPathRegistry());
        assertTrue(runtime.instanceSnapshot().isEmpty());
        assertTrue(runtime.installInstance(
                new LanChatListenerInstance("sta|192.168.1.2/24", 80L)));
        List<LanChatListenerInstance> snapshot = runtime.instanceSnapshot();
        try {
            snapshot.clear();
            fail("instance snapshot must be immutable");
        } catch (UnsupportedOperationException expected) { }
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
