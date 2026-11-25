package com.example.devicefinder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanTransferPairingControlProtocolTest {
    private static final String SESSION="5d6e3b20-41f4-4db1-8d34-ae75b77e8a10";
    @Test public void requestEnvelopeRoundTripsWithoutTouchingDataPlane() throws Exception {
        byte[] payload=new byte[96];
        LanTransferPairingControlProtocol.Message source=new LanTransferPairingControlProtocol.Message(
                LanTransferPairingControlProtocol.Type.REQUEST,SESSION,"peer-a","peer-b",1,payload);
        LanTransferPairingControlProtocol.Message decoded=LanTransferPairingControlProtocol.decode(
                LanTransferPairingControlProtocol.encode(source));
        assertEquals(source.type,decoded.type);assertEquals(source.sessionId,decoded.sessionId);
        assertEquals(source.senderPeerId,decoded.senderPeerId);assertEquals(source.targetPeerId,decoded.targetPeerId);
        assertEquals(1L,decoded.sequence);assertArrayEquals(payload,decoded.payload);
    }
    @Test public void malformedFramesAndTypeSpecificPayloadsAreRejected() throws Exception {
        try { new LanTransferPairingControlProtocol.Message(LanTransferPairingControlProtocol.Type.CONFIRM,
                SESSION,"peer-a","peer-b",1,new byte[3]);fail("short proof rejected"); }
        catch(IllegalArgumentException expected) { }
        try { LanTransferPairingControlProtocol.decode(new byte[5000]);fail("oversize frame rejected"); }
        catch(java.io.IOException expected) { }
        byte[] encoded=LanTransferPairingControlProtocol.encode(new LanTransferPairingControlProtocol.Message(
                LanTransferPairingControlProtocol.Type.CANCEL,SESSION,"peer-a","peer-b",2,new byte[0]));
        encoded[4]=99;
        try { LanTransferPairingControlProtocol.decode(encoded);fail("unknown version rejected"); }
        catch(java.io.IOException expected) { }
    }
    @Test public void controlMessageRejectsIdentityAndSequenceErrors() {
        try { new LanTransferPairingControlProtocol.Message(LanTransferPairingControlProtocol.Type.CANCEL,
                SESSION,"peer-a","peer-a",1,new byte[0]);fail("same peer rejected"); }
        catch(IllegalArgumentException expected) { }
        try { new LanTransferPairingControlProtocol.Message(LanTransferPairingControlProtocol.Type.CANCEL,
                SESSION,"peer-a","peer-b",0,new byte[0]);fail("zero sequence rejected"); }
        catch(IllegalArgumentException expected) { }
    }
}
