package com.example.devicefinder;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanTransferFrameCodecTest {
    private static final byte[] KEY="0123456789abcdef-session-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test public void frameRoundTripPreservesChunkAndAuthenticatesPeerIds() throws Exception {
        ServerSocket listener=new ServerSocket(0);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        byte[] payload="hello-lan-transfer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Future<LanTransferFrameCodec.Frame> received=executor.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(2000);
                    return LanTransferFrameCodec.receive(socket,KEY,1024,"sender","receiver");
                }
            });
            try(Socket client=new Socket("127.0.0.1",listener.getLocalPort())){
                JSONObject metadata=null;
                LanTransferFrameCodec.send(client,new LanTransferFrameCodec.Frame(
                        "chunk","transfer-1","sender","receiver",0,payload,metadata),KEY,1024);
            }
            LanTransferFrameCodec.Frame frame=received.get(3,TimeUnit.SECONDS);
            assertEquals("chunk",frame.kind);assertEquals("transfer-1",frame.transferId);
            assertEquals("sender",frame.senderPeerId);assertEquals("receiver",frame.targetPeerId);
            assertArrayEquals(payload,frame.payload);
        } finally {listener.close();executor.shutdownNow();}
    }

    @Test public void controlMetadataAndMissingPageRoundTrip() throws Exception {
        ServerSocket listener=new ServerSocket(0);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<LanTransferFrameCodec.Frame> received=executor.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(2000);
                    return LanTransferFrameCodec.receive(socket,KEY,1024,"sender","receiver");
                }
            });
            try(Socket client=new Socket("127.0.0.1",listener.getLocalPort())){
                JSONObject metadata=new JSONObject();metadata.put("indexes",new org.json.JSONArray().put(1).put(4));
                metadata.put("more",true);
                LanTransferFrameCodec.send(client,new LanTransferFrameCodec.Frame(
                        "missing","transfer-1","sender","receiver",null,new byte[0],metadata),KEY,1024);
            }
            LanTransferFrameCodec.Frame frame=received.get(3,TimeUnit.SECONDS);
            assertEquals("missing",frame.kind);assertTrue(frame.metadata.getBoolean("more"));
            assertEquals(2,frame.metadata.getJSONArray("indexes").length());
        } finally {listener.close();executor.shutdownNow();}
    }

    @Test public void rejectsUnexpectedPeer() throws Exception {
        ServerSocket listener=new ServerSocket(0);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> received=executor.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(2000);
                    LanTransferFrameCodec.receive(socket,KEY,1024,"someone-else","receiver");
                    return false;
                } catch(SecurityException expected){return true;}
            });
            try(Socket client=new Socket("127.0.0.1",listener.getLocalPort())){
                LanTransferFrameCodec.send(client,new LanTransferFrameCodec.Frame(
                        "cancel","transfer-1","sender","receiver",null,new byte[0],null),KEY,1024);
            }
            assertTrue(received.get(3,TimeUnit.SECONDS));
        } finally {listener.close();executor.shutdownNow();}
    }
    @Test public void rejectsWrongSessionKey() throws Exception {
        ServerSocket listener=new ServerSocket(0);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        byte[] wrongKey="wrong-session-key-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Future<Boolean> received=executor.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(2000);
                    LanTransferFrameCodec.receive(socket,wrongKey,1024,"sender","receiver");
                    return false;
                } catch(SecurityException expected){return true;}
            });
            try(Socket client=new Socket("127.0.0.1",listener.getLocalPort())){
                LanTransferFrameCodec.send(client,new LanTransferFrameCodec.Frame(
                        "cancel","transfer-1","sender","receiver",null,new byte[0],null),KEY,1024);
            }
            assertTrue(received.get(3,TimeUnit.SECONDS));
        } finally {listener.close();executor.shutdownNow();}
    }

}
