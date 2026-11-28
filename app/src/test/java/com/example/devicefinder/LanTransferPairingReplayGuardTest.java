package com.example.devicefinder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanTransferPairingReplayGuardTest {
    @Test public void strictlyIncreasingSequenceIsRequiredPerSenderAndSession() {
        LanTransferPairingReplayGuard guard=new LanTransferPairingReplayGuard(30_000L);
        assertTrue(guard.accept("s1","peer-a",1,1000));
        assertFalse(guard.accept("s1","peer-a",1,1001));
        assertFalse(guard.accept("s1","peer-a",0,1001));
        assertFalse(guard.accept("s1","peer-a",2,999));
        assertTrue(guard.accept("s1","peer-a",2,1002));
        assertTrue(guard.accept("s1","peer-b",1,1002));
        assertTrue(guard.accept("s2","peer-a",1,1002));
    }
    @Test public void expiredReplayStateCanBeReinitialized() {
        LanTransferPairingReplayGuard guard=new LanTransferPairingReplayGuard(1000L);
        assertTrue(guard.accept("s","peer-a",7,100));
        assertFalse(guard.accept("s","peer-a",7,500));
        assertTrue(guard.accept("s","peer-a",1,1100));
    }
}
