package com.example.devicefinder;

import android.content.Context;
import android.system.Os;
import org.junit.After;import org.junit.Before;import org.junit.Test;import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;import org.robolectric.RuntimeEnvironment;import org.robolectric.annotation.Config;
import java.io.File;import java.io.FileOutputStream;import java.security.MessageDigest;import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class LanTransferFilesystemTest {
 private static final String DB="lan_p2p_transfer.db";
 private Context context;private LanTransferStore store;private LanTransferFilesystem fs;
 @Before public void setUp(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(DB);store=new LanTransferStore(context);fs=new LanTransferFilesystem(context,store);}
 @After public void tearDown(){if(store!=null)store.closeStore();context.deleteDatabase(DB);}
 private static String hash(byte[] b)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format("%02x",x&255));return s.toString();}
 private void start(String id,byte[] content,int chunk)throws Exception{
  store.createOffer(new LanTransferStore.Offer(id,"sender","receiver","payload.bin",content.length,hash(content),1,"application/octet-stream"),2,chunk);
  store.transition(id,"accepted",3);store.transition(id,"transferring",4);
 }
 @Test public void chunkIsFsyncedBeforeReceiptAndSurvivesReopen()throws Exception{
  byte[] data=new byte[4096];Arrays.fill(data,(byte)7);start("fs-1",data,4096);
  assertTrue(fs.writeChunk("fs-1",0,data,5));assertEquals(1,store.loadChunkReceipts("fs-1").size());
  store.closeStore();store=new LanTransferStore(context);fs=new LanTransferFilesystem(context,store);
  assertTrue(fs.reconcile("fs-1",6).isEmpty());assertFalse(fs.writeChunk("fs-1",0,data,7));
 }
 @Test public void corruptStagedFileInvalidatesReceiptOnRestartReconcile()throws Exception{
  byte[] data=new byte[4096];Arrays.fill(data,(byte)9);start("fs-2",data,4096);fs.writeChunk("fs-2",0,data,5);
  File staged=new File(new File(new File(context.getFilesDir(),"lan-transfer-staging"),"fs-2"),"0.part");
  try(FileOutputStream out=new FileOutputStream(staged,false)){out.write(new byte[]{1,2,3});out.getFD().sync();}
  assertEquals(Arrays.asList(0),fs.reconcile("fs-2",6));assertTrue(store.loadChunkReceipts("fs-2").isEmpty());
 }
 @Test public void wholeFileHashGateAndAtomicPublishCompleteTransfer()throws Exception{
  byte[] data=new byte[8192];for(int i=0;i<data.length;i++)data[i]=(byte)(i&255);start("fs-3",data,4096);
  fs.writeChunk("fs-3",0,Arrays.copyOfRange(data,0,4096),5);fs.writeChunk("fs-3",1,Arrays.copyOfRange(data,4096,8192),6);
  File published=fs.assembleAndPublish("fs-3",7);assertTrue(published.isFile());assertEquals(hash(data),hash(read(published)));
  assertEquals("completed",store.getTransfer("fs-3").state);
 }
 @Test public void quotaRejectsChunkBeforeReceipt()throws Exception{
  byte[] data=new byte[4096];Arrays.fill(data,(byte)1);start("fs-4",data,4096);
  LanTransferFilesystem tiny=new LanTransferFilesystem(context,store,1024);
  try{tiny.writeChunk("fs-4",0,data,5);fail("quota must reject");}catch(java.io.IOException expected){}
  assertTrue(store.loadChunkReceipts("fs-4").isEmpty());
 }
 @Test public void symlinkChunkIsRejectedAndReceiptInvalidated()throws Exception{
  byte[] data=new byte[4096];Arrays.fill(data,(byte)2);start("fs-5",data,4096);fs.writeChunk("fs-5",0,data,5);
  File dir=new File(new File(context.getFilesDir(),"lan-transfer-staging"),"fs-5");
  File chunk=new File(dir,"0.part"),outside=new File(context.getFilesDir(),"outside-transfer-file");
  try(FileOutputStream out=new FileOutputStream(outside)){out.write(data);out.getFD().sync();}
  chunk.delete();Os.symlink(outside.getAbsolutePath(),chunk.getAbsolutePath());
  assertEquals(Arrays.asList(0),fs.reconcile("fs-5",6));assertTrue(store.loadChunkReceipts("fs-5").isEmpty());
  assertTrue(outside.isFile());
 }
 private static byte[] read(File f)throws Exception{java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();try(java.io.FileInputStream in=new java.io.FileInputStream(f)){byte[] x=new byte[4096];int n;while((n=in.read(x))!=-1)b.write(x,0,n);}return b.toByteArray();}
}
