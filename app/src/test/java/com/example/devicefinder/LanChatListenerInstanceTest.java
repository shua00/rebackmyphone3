package com.example.devicefinder;

import org.junit.Test;
import java.net.DatagramSocket;
import static org.junit.Assert.*;

public class LanChatListenerInstanceTest {
    private static DatagramSocket socket() throws Exception { return new DatagramSocket(null); }

    @Test public void instanceOwnsOnlyItsSocketAndWorkerIdentity() throws Exception {
        LanChatListenerInstance instance = new LanChatListenerInstance("sta|192.168.1.2/24", 7L);
        DatagramSocket first = socket(), second = socket();
        Thread worker = new Thread(() -> { });
        try {
            assertTrue(instance.attachSocket(first));
            assertFalse(instance.attachSocket(second));
            assertTrue(instance.attachThread(worker));
            assertFalse(instance.attachThread(new Thread(() -> { })));
            assertTrue(instance.owns(first, worker));
            assertFalse(instance.owns(second, worker));
            assertFalse(instance.owns(first, new Thread(() -> { })));
            assertTrue(instance.ownsSocket(first));
            assertTrue(instance.ownsThread(worker));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test public void stopPreventsLateSocketOrThreadAttachment() throws Exception {
        LanChatListenerInstance instance = new LanChatListenerInstance("ap|192.168.43.1/24", 8L);
        DatagramSocket candidate = socket();
        try {
            instance.requestStop();
            assertTrue(instance.isStopRequested());
            assertFalse(instance.attachSocket(candidate));
            assertFalse(instance.attachThread(new Thread(() -> { })));
            assertFalse(instance.owns(candidate, null));
        } finally {
            candidate.close();
        }
    }

    @Test public void oldGenerationCannotClaimReplacementResources() throws Exception {
        LanChatListenerInstance oldInstance = new LanChatListenerInstance("sta|192.168.1.2/24", 10L);
        LanChatListenerInstance replacement = new LanChatListenerInstance("sta|192.168.1.2/24", 11L);
        DatagramSocket oldSocket = socket(), newSocket = socket();
        try {
            assertTrue(oldInstance.attachSocket(oldSocket));
            assertTrue(replacement.attachSocket(newSocket));
            oldInstance.requestStop();
            assertFalse(oldInstance.owns(oldSocket, null));
            assertTrue(replacement.owns(newSocket, null));
            assertEquals(10L, oldInstance.generation);
            assertEquals(11L, replacement.generation);
        } finally {
            oldSocket.close();
            newSocket.close();
        }
    }
}
