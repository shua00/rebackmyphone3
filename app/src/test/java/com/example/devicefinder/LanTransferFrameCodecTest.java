package com.example.devicefinder;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanTransferFrameCodecTest {
    private static final byte[] KEY = "0123456789abcdef-session-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String PYTHON_GOLDEN =
            "4c4e5032000000f47b2261757468223a2233343338653861353536636163623531333735646662366135303331386533323839333962646162343939316165323639663365653535393165373337666363222c22696e646578223a6e756c6c2c226b696e64223a2263616e63656c222c226d65746164617461223a6e756c6c2c227061796c6f61645f736861323536223a22222c227061796c6f61645f73697a65223a302c2273656e6465725f706565725f6964223a2273656e646572222c227461726765745f706565725f6964223a227265636569766572222c227472616e736665725f6964223a227472616e736665722d31222c2276223a317d";

    private static byte[] fromHex(String value) {
        byte[] out = new byte[value.length()/2];
        for (int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return out;
    }

    private static LanTransferFrameCodec.Frame decode(byte[] wire, byte[] key,
            int maxPayloadBytes, String sender, String target) throws Exception {
        try {
            return LanTransferFrameCodec.decodeFrame(wire, key, maxPayloadBytes, sender, target);
        } catch (java.io.IOException failure) {
            System.err.println("LNP2 decode diagnostic: " + failure.getMessage());
            throw failure;
        }
    }

    @Test public void chunkFrameRoundTripsThroughByteCodec() throws Exception {
        byte[] payload = "hello-lan-transfer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] wire = LanTransferFrameCodec.encodeFrame(new LanTransferFrameCodec.Frame(
                "chunk","transfer-1","sender","receiver",0,payload,null),KEY,1024);
        LanTransferFrameCodec.Frame frame = LanTransferFrameCodec.decodeFrame(
                wire,KEY,1024,"sender","receiver");
        assertEquals("chunk",frame.kind);
        assertEquals("transfer-1",frame.transferId);
        assertArrayEquals(payload,frame.payload);
    }

    @Test public void controlMetadataAndMissingPageRoundTrip() throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("indexes",new org.json.JSONArray().put(1).put(4));
        metadata.put("more",true);
        byte[] wire = LanTransferFrameCodec.encodeFrame(new LanTransferFrameCodec.Frame(
                "missing","transfer-1","sender","receiver",null,new byte[0],metadata),KEY,1024);
        LanTransferFrameCodec.Frame frame = LanTransferFrameCodec.decodeFrame(
                wire,KEY,1024,"sender","receiver");
        assertEquals("missing",frame.kind);
        assertTrue(frame.metadata.getBoolean("more"));
        assertEquals(2,frame.metadata.getJSONArray("indexes").length());
    }

    @Test public void rejectsUnexpectedPeerAndWrongSessionKey() throws Exception {
        byte[] wire = LanTransferFrameCodec.encodeFrame(new LanTransferFrameCodec.Frame(
                "cancel","transfer-1","sender","receiver",null,new byte[0],null),KEY,1024);
        try {
            LanTransferFrameCodec.decodeFrame(wire,KEY,1024,"someone-else","receiver");
            fail("unexpected peer must be rejected");
        } catch (SecurityException expected) { }
        byte[] wrongKey = "wrong-session-key-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            LanTransferFrameCodec.decodeFrame(wire,wrongKey,1024,"sender","receiver");
            fail("wrong session key must be rejected");
        } catch (SecurityException expected) { }
    }

    @Test public void javaEncoderMatchesPythonGoldenWireFrame() throws Exception {
        byte[] wire = LanTransferFrameCodec.encodeFrame(new LanTransferFrameCodec.Frame(
                "cancel","transfer-1","sender","receiver",null,new byte[0],null),KEY,1024);
        assertArrayEquals(fromHex(PYTHON_GOLDEN),wire);
    }

    @Test public void javaDecoderAcceptsPythonGoldenWireFrame() throws Exception {
        LanTransferFrameCodec.Frame frame = LanTransferFrameCodec.decodeFrame(
                fromHex(PYTHON_GOLDEN),KEY,1024,"sender","receiver");
        assertEquals("cancel",frame.kind);
        assertEquals("transfer-1",frame.transferId);
        assertEquals("sender",frame.senderPeerId);
        assertEquals("receiver",frame.targetPeerId);
    }

    @Test public void rejectsTrailingBytesAfterFrame() throws Exception {
        byte[] frame = LanTransferFrameCodec.encodeFrame(new LanTransferFrameCodec.Frame(
                "cancel","transfer-1","sender","receiver",null,new byte[0],null),KEY,1024);
        byte[] tailed = java.util.Arrays.copyOf(frame,frame.length+1);
        try {
            LanTransferFrameCodec.decodeFrame(tailed,KEY,1024,"sender","receiver");
            fail("trailing bytes must be rejected");
        } catch (java.io.IOException expected) { }
    }
}
