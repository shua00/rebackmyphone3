package com.example.devicefinder;
import android.content.Context;
import org.junit.After;import org.junit.Before;import org.junit.Test;import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;import org.robolectric.RuntimeEnvironment;import org.robolectric.annotation.Config;
import java.util.Arrays;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class LanTransferStoreTest {
 private static final String DB="lan_p2p_transfer.db",HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
 private Context context;private LanTransferStore store;
 @Before public void setUp(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(DB);store=new LanTransferStore(context);}
 @After public void tearDown(){if(store!=null)store.closeStore();context.deleteDatabase(DB);}
 private LanTransferStore.Offer offer(String id,long size){return new LanTransferStore.Offer(id,"sender","receiver","report.bin",size,HASH,1000,"application/octet-stream");}
 @Test public void offerIsImmutableIdempotentAndSurvivesReopen(){
  LanTransferStore.Offer o=offer("task-1",8192);assertTrue(store.createOffer(o,1100,4096));assertFalse(store.createOffer(o,1200,4096));
  assertEquals(1,store.loadIncomingOffers("receiver",10).size());assertEquals(0,store.loadIncomingOffers("other",10).size());
  try{store.createOffer(offer("task-1",8193),1200,4096);fail("conflicting Offer");}catch(IllegalStateException expected){}
  try{store.createOffer(o,1200,8192);fail("chunk size must be immutable");}catch(IllegalStateException expected){}
  store.closeStore();store=new LanTransferStore(context);LanTransferStore.Transfer saved=store.getTransfer("task-1");
  assertNotNull(saved);assertEquals("sender",saved.offer.senderPeerId);assertEquals("offered",saved.state);assertEquals(2,saved.totalChunks);
 }
 @Test public void receiptsAreIdempotentAndMissingIndexesAreStable(){
  store.createOffer(offer("task-2",8192),1100,4096);store.transition("task-2","accepted",1200);store.transition("task-2","transferring",1300);
  assertEquals(Arrays.asList(0,1),store.missingChunkIndexes("task-2"));
  assertTrue(store.recordDurableChunk("task-2",1,4096,HASH,"/staging/task-2/1.part",1400));
  assertFalse(store.recordDurableChunk("task-2",1,4096,HASH,"/staging/task-2/1.part",1500));
  assertEquals(Arrays.asList(0),store.missingChunkIndexes("task-2"));
  try{store.recordDurableChunk("task-2",1,4096,"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","/staging/task-2/1.part",1600);fail("conflicting receipt");}catch(IllegalStateException expected){}
 }
 @Test public void verifyRequiresAllChunksAndPublishRequiresWholeHash(){
  store.createOffer(offer("task-3",8192),1100,4096);store.transition("task-3","accepted",1200);store.transition("task-3","transferring",1300);
  store.recordDurableChunk("task-3",0,4096,HASH,"/staging/task-3/0.part",1400);
  try{store.transition("task-3","verifying",1500);fail("missing chunk");}catch(IllegalStateException expected){}
  store.recordDurableChunk("task-3",1,4096,HASH,"/staging/task-3/1.part",1600);store.transition("task-3","verifying",1700);
  try{store.markCompletedAfterPublish("task-3","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","/downloads/report.bin",1800);fail("wrong hash");}catch(SecurityException expected){}
  assertTrue(store.markCompletedAfterPublish("task-3",HASH,"/downloads/report.bin",1900));assertEquals("completed",store.getTransfer("task-3").state);
 }
 @Test public void corruptReceiptCanBeInvalidatedAndVerificationReopened(){
  store.createOffer(offer("task-4",4096),1100,4096);store.transition("task-4","accepted",1200);store.transition("task-4","transferring",1300);
  store.recordDurableChunk("task-4",0,4096,HASH,"/staging/task-4/0.part",1400);store.transition("task-4","verifying",1500);
  assertTrue(store.invalidateChunkReceipt("task-4",0,1600));assertEquals("transferring",store.getTransfer("task-4").state);
  assertEquals(Arrays.asList(0),store.missingChunkIndexes("task-4"));
 }
 @Test public void missingIndexesArePagedAndActiveReservationsAreBounded() {
  store.createOffer(offer("task-pages",300L*4096L),1100,4096);
  assertEquals(300L*4096L,store.getActiveReservedBytes());
  LanTransferStore.MissingPage first=store.getMissingChunkPage("task-pages",0,128);
  assertEquals(128,first.indexes.size());assertEquals(Integer.valueOf(0),first.indexes.get(0));
  assertEquals(Integer.valueOf(127),first.indexes.get(127));assertTrue(first.more);
  LanTransferStore.MissingPage second=store.getMissingChunkPage("task-pages",128,128);
  assertEquals(Integer.valueOf(128),second.indexes.get(0));assertTrue(second.more);
  LanTransferStore.MissingPage third=store.getMissingChunkPage("task-pages",256,128);
  assertEquals(44,third.indexes.size());assertFalse(third.more);
 }
 @Test public void outgoingOfferCanReachFailedOnRemoteTerminalOrSecurityFailure(){
  store.createOffer(offer("task-failed",1),1100,4096);
  assertTrue(store.transition("task-failed","failed",1200));
  assertEquals("failed",store.getTransfer("task-failed").state);
 }
 @Test public void traversalAndInvalidChunkSizeAreRejected(){
  try{store.createOffer(new LanTransferStore.Offer("task-5","sender","receiver","../evil",1,HASH,1,"x"),1,4096);fail();}catch(IllegalArgumentException expected){}
  try{store.createOffer(offer("task-6",1),1,1024);fail();}catch(IllegalArgumentException expected){}
 }
}