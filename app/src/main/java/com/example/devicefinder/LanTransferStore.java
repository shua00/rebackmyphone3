package com.example.devicefinder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Independent durable ledger for LAN file transfers; never shares the chat Outbox. */
public final class LanTransferStore extends SQLiteOpenHelper {
    private static final String DB_NAME="lan_p2p_transfer.db";
    private static final int DB_VERSION=1;
    public static final int DEFAULT_CHUNK_SIZE=256*1024, MIN_CHUNK_SIZE=4096, MAX_CHUNK_SIZE=1024*1024;
    public static final long MAX_FILE_BYTES=2L*1024L*1024L*1024L;
    public static final int MAX_ACTIVE_TRANSFERS=4;
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHA=Pattern.compile("[0-9a-f]{64}");

    public static final class Offer {
        public final String transferId,senderPeerId,targetPeerId,filename,sha256,mimeType;
        public final long sizeBytes,createdAtMs;
        public Offer(String id,String sender,String target,String filename,long size,String sha,long created,String mime) {
            transferId=id;senderPeerId=sender;targetPeerId=target;this.filename=filename;
            sizeBytes=size;sha256=sha;createdAtMs=created;mimeType=mime;
        }
    }
    public static final class Transfer {
        public final Offer offer; public final String state; public final int chunkSizeBytes,totalChunks;
        public final long updatedAtMs;
        Transfer(Offer o,String s,int chunk,int total,long updated) {
            offer=o;state=s;chunkSizeBytes=chunk;totalChunks=total;updatedAtMs=updated;
        }
    }
    public static final class MissingPage {
        public final List<Integer> indexes;
        public final boolean more;
        MissingPage(List<Integer> indexes,boolean more) { this.indexes=indexes;this.more=more; }
    }
    public static final class ChunkReceipt {
        public final String transferId,sha256,stagingPath; public final int index,sizeBytes; public final long recordedAtMs;
        ChunkReceipt(String id,int i,int size,String hash,String path,long time) {
            transferId=id;index=i;sizeBytes=size;sha256=hash;stagingPath=path;recordedAtMs=time;
        }
    }
    public LanTransferStore(Context c) { super(c.getApplicationContext(),DB_NAME,null,DB_VERSION);setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE lan_transfer_tasks (transfer_id TEXT PRIMARY KEY NOT NULL,sender_peer_id TEXT NOT NULL,target_peer_id TEXT NOT NULL,filename TEXT NOT NULL,size_bytes INTEGER NOT NULL,whole_sha256 TEXT NOT NULL,created_at_ms INTEGER NOT NULL,mime_type TEXT NOT NULL,state TEXT NOT NULL,chunk_size_bytes INTEGER NOT NULL,total_chunks INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,published_path TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_lan_transfer_state ON lan_transfer_tasks(state,updated_at_ms)");
        db.execSQL("CREATE TABLE lan_transfer_chunks (transfer_id TEXT NOT NULL,chunk_index INTEGER NOT NULL,payload_size INTEGER NOT NULL,chunk_sha256 TEXT NOT NULL,staging_path TEXT NOT NULL,recorded_at_ms INTEGER NOT NULL,PRIMARY KEY(transfer_id,chunk_index),FOREIGN KEY(transfer_id) REFERENCES lan_transfer_tasks(transfer_id) ON DELETE CASCADE)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        throw new IllegalStateException("Unsupported transfer ledger migration "+oldVersion+" -> "+newVersion);
    }
    private static boolean id(String s) { return s!=null&&ID.matcher(s).matches(); }
    private static boolean control(String s) { for(int i=0;i<s.length();i++)if(s.charAt(i)<32||s.charAt(i)==127)return true;return false; }
    private static int chunks(long size,int chunk) { return (int)((size+chunk-1L)/chunk); }
    private static void validate(Offer o,int chunk) {
        if(o==null||!id(o.transferId)||!id(o.senderPeerId)||!id(o.targetPeerId)||o.senderPeerId.equals(o.targetPeerId))throw new IllegalArgumentException("Invalid transfer identity");
        if(o.filename==null||o.filename.isEmpty()||".".equals(o.filename)||"..".equals(o.filename)||o.filename.contains("/")||o.filename.contains("\\")||control(o.filename)||o.filename.getBytes(StandardCharsets.UTF_8).length>255)throw new IllegalArgumentException("Invalid display filename");
        if(o.sizeBytes<0||o.sizeBytes>MAX_FILE_BYTES||o.createdAtMs<0)throw new IllegalArgumentException("Invalid size or timestamp");
        if(o.sha256==null||!SHA.matcher(o.sha256).matches())throw new IllegalArgumentException("Invalid whole-file SHA-256");
        if(o.mimeType==null||o.mimeType.isEmpty()||o.mimeType.length()>127||control(o.mimeType))throw new IllegalArgumentException("Invalid MIME type");
        if(chunk<MIN_CHUNK_SIZE||chunk>MAX_CHUNK_SIZE)throw new IllegalArgumentException("Chunk size outside 4 KiB..1 MiB");
    }
    private static boolean same(Offer a,Offer b) {
        return a.transferId.equals(b.transferId)&&a.senderPeerId.equals(b.senderPeerId)&&a.targetPeerId.equals(b.targetPeerId)&&a.filename.equals(b.filename)&&a.sizeBytes==b.sizeBytes&&a.sha256.equals(b.sha256)&&a.createdAtMs==b.createdAtMs&&a.mimeType.equals(b.mimeType);
    }
    private static Offer readOffer(Cursor c) {
        return new Offer(c.getString(c.getColumnIndexOrThrow("transfer_id")),c.getString(c.getColumnIndexOrThrow("sender_peer_id")),c.getString(c.getColumnIndexOrThrow("target_peer_id")),c.getString(c.getColumnIndexOrThrow("filename")),c.getLong(c.getColumnIndexOrThrow("size_bytes")),c.getString(c.getColumnIndexOrThrow("whole_sha256")),c.getLong(c.getColumnIndexOrThrow("created_at_ms")),c.getString(c.getColumnIndexOrThrow("mime_type")));
    }
    private static boolean terminal(String s) { return "completed".equals(s)||"rejected".equals(s)||"cancelled".equals(s)||"failed".equals(s)||"expired".equals(s); }
    private static boolean allowed(String a,String b) {
        if(a.equals(b))return true;
        if("offered".equals(a))return "accepted".equals(b)||"rejected".equals(b)||"cancelled".equals(b)||"expired".equals(b);
        if("accepted".equals(a))return "transferring".equals(b)||"cancelled".equals(b)||"failed".equals(b);
        if("transferring".equals(a))return "paused".equals(b)||"verifying".equals(b)||"cancelled".equals(b)||"failed".equals(b);
        if("paused".equals(a))return "transferring".equals(b)||"cancelled".equals(b)||"failed".equals(b)||"expired".equals(b);
        if("verifying".equals(a))return "transferring".equals(b)||"completed".equals(b)||"cancelled".equals(b)||"failed".equals(b);
        return false;
    }

    /** New Offer=true; identical replay=false; reused ID with different metadata throws. */
    public boolean createOffer(Offer o,long now,int chunk) {
        validate(o,chunk);if(now<0)throw new IllegalArgumentException("Invalid timestamp");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Cursor c=db.rawQuery("SELECT * FROM lan_transfer_tasks WHERE transfer_id=?",new String[]{o.transferId});
            try { if(c.moveToFirst()) { if(!same(readOffer(c),o))throw new IllegalStateException("Conflicting Offer for transfer_id");db.setTransactionSuccessful();return false; } }
            finally { c.close(); }
            Cursor count=db.rawQuery("SELECT COUNT(*) FROM lan_transfer_tasks WHERE state NOT IN ('completed','rejected','cancelled','failed','expired')",null);
            int active=0;try{if(count.moveToFirst())active=count.getInt(0);}finally{count.close();}
            if(active>=MAX_ACTIVE_TRANSFERS)throw new IllegalStateException("Active transfer limit reached");
            ContentValues v=new ContentValues();v.put("transfer_id",o.transferId);v.put("sender_peer_id",o.senderPeerId);v.put("target_peer_id",o.targetPeerId);v.put("filename",o.filename);v.put("size_bytes",o.sizeBytes);v.put("whole_sha256",o.sha256);v.put("created_at_ms",o.createdAtMs);v.put("mime_type",o.mimeType);v.put("state","offered");v.put("chunk_size_bytes",chunk);v.put("total_chunks",chunks(o.sizeBytes,chunk));v.put("updated_at_ms",now);
            db.insertOrThrow("lan_transfer_tasks",null,v);db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
    }
    public Transfer getTransfer(String id) {
        Cursor c=getReadableDatabase().rawQuery("SELECT * FROM lan_transfer_tasks WHERE transfer_id=?",new String[]{id});
        try { if(!c.moveToFirst())return null;return new Transfer(readOffer(c),c.getString(c.getColumnIndexOrThrow("state")),c.getInt(c.getColumnIndexOrThrow("chunk_size_bytes")),c.getInt(c.getColumnIndexOrThrow("total_chunks")),c.getLong(c.getColumnIndexOrThrow("updated_at_ms"))); }
        finally { c.close(); }
    }
    /** VERIFYING is forbidden until every expected durable receipt exists. */
    public boolean transition(String id,String target,long now) {
        if(target==null||now<0)throw new IllegalArgumentException("Invalid transition");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Cursor c=db.rawQuery("SELECT state,total_chunks FROM lan_transfer_tasks WHERE transfer_id=?",new String[]{id});
            String current;int total;try{if(!c.moveToFirst())throw new IllegalArgumentException("Unknown transfer");current=c.getString(0);total=c.getInt(1);}finally{c.close();}
            if(!allowed(current,target))throw new IllegalStateException("Invalid transfer transition "+current+" -> "+target);
            if("verifying".equals(target)) { Cursor n=db.rawQuery("SELECT COUNT(*) FROM lan_transfer_chunks WHERE transfer_id=?",new String[]{id});int count=0;try{if(n.moveToFirst())count=n.getInt(0);}finally{n.close();}if(count!=total)throw new IllegalStateException("Cannot verify before all chunks are durable"); }
            ContentValues v=new ContentValues();v.put("state",target);v.put("updated_at_ms",now);db.update("lan_transfer_tasks",v,"transfer_id=?",new String[]{id});db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
    }
    /** Call only after staging bytes and fsync have succeeded. */
    public boolean recordDurableChunk(String id,int index,int payloadSize,String hash,String path,long now) {
        if(index<0||payloadSize<0||hash==null||!SHA.matcher(hash).matches()||path==null||path.isEmpty()||now<0)throw new IllegalArgumentException("Invalid durable chunk receipt");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Cursor c=db.rawQuery("SELECT state,size_bytes,chunk_size_bytes,total_chunks FROM lan_transfer_tasks WHERE transfer_id=?",new String[]{id});
            String state;long size;int chunk,total;try{if(!c.moveToFirst())throw new IllegalArgumentException("Unknown transfer");state=c.getString(0);size=c.getLong(1);chunk=c.getInt(2);total=c.getInt(3);}finally{c.close();}
            if(!"transferring".equals(state))throw new IllegalStateException("Chunks accepted only while transferring");
            if(index>=total)throw new IllegalArgumentException("Chunk index outside transfer");
            int expected=(int)Math.min((long)chunk,size-(long)index*chunk);if(expected<=0||payloadSize!=expected)throw new IllegalArgumentException("Chunk length mismatch");
            Cursor old=db.rawQuery("SELECT payload_size,chunk_sha256,staging_path FROM lan_transfer_chunks WHERE transfer_id=? AND chunk_index=?",new String[]{id,Integer.toString(index)});
            try{if(old.moveToFirst()){boolean identical=old.getInt(0)==payloadSize&&hash.equals(old.getString(1))&&path.equals(old.getString(2));if(!identical)throw new IllegalStateException("Conflicting chunk receipt");db.setTransactionSuccessful();return false;}}finally{old.close();}
            ContentValues v=new ContentValues();v.put("transfer_id",id);v.put("chunk_index",index);v.put("payload_size",payloadSize);v.put("chunk_sha256",hash);v.put("staging_path",path);v.put("recorded_at_ms",now);db.insertOrThrow("lan_transfer_chunks",null,v);
            ContentValues update=new ContentValues();update.put("updated_at_ms",now);db.update("lan_transfer_tasks",update,"transfer_id=?",new String[]{id});db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
    }
    public ChunkReceipt getChunkReceipt(String id,int index) {
        Cursor c=getReadableDatabase().rawQuery("SELECT transfer_id,chunk_index,payload_size,chunk_sha256,staging_path,recorded_at_ms FROM lan_transfer_chunks WHERE transfer_id=? AND chunk_index=?",
                new String[]{id,Integer.toString(index)});
        try {
            if(!c.moveToFirst())return null;
            return new ChunkReceipt(c.getString(0),c.getInt(1),c.getInt(2),c.getString(3),c.getString(4),c.getLong(5));
        } finally { c.close(); }
    }

    /** Reserve declared active task sizes conservatively; avoids rescanning every staged file per chunk. */
    public long getActiveReservedBytes() {
        Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM lan_transfer_tasks WHERE state NOT IN ('completed','rejected','cancelled','failed','expired')",null);
        try { return c.moveToFirst()?c.getLong(0):0L; } finally { c.close(); }
    }

    /** Bounded missing-index page; never materializes a potentially 524k-index list. */
    public MissingPage getMissingChunkPage(String id,int startIndex,int limit) {
        if(startIndex<0||limit<1||limit>128)throw new IllegalArgumentException("Invalid missing page bounds");
        Transfer transfer=getTransfer(id);if(transfer==null)throw new IllegalArgumentException("Unknown transfer");
        if(startIndex>=transfer.totalChunks)return new MissingPage(new ArrayList<>(),false);
        List<Integer> missing=new ArrayList<>(limit);
        Cursor c=getReadableDatabase().rawQuery("SELECT chunk_index FROM lan_transfer_chunks WHERE transfer_id=? AND chunk_index>=? ORDER BY chunk_index",
                new String[]{id,Integer.toString(startIndex)});
        try {
            boolean has=c.moveToFirst();int candidate=startIndex;
            while(candidate<transfer.totalChunks) {
                if(has&&c.getInt(0)==candidate) { candidate++;has=c.moveToNext();continue; }
                missing.add(candidate++);
                if(missing.size()==limit) {
                    while(candidate<transfer.totalChunks) {
                        if(has&&c.getInt(0)==candidate) { candidate++;has=c.moveToNext(); }
                        else return new MissingPage(missing,true);
                    }
                    return new MissingPage(missing,false);
                }
            }
            return new MissingPage(missing,false);
        } finally { c.close(); }
    }

    public List<Integer> missingChunkIndexes(String id) {
        Transfer t=getTransfer(id);if(t==null)throw new IllegalArgumentException("Unknown transfer");
        boolean[] seen=new boolean[t.totalChunks];Cursor c=getReadableDatabase().rawQuery("SELECT chunk_index FROM lan_transfer_chunks WHERE transfer_id=?",new String[]{id});
        try{while(c.moveToNext()){int i=c.getInt(0);if(i>=0&&i<seen.length)seen[i]=true;}}finally{c.close();}
        List<Integer> missing=new ArrayList<>();for(int i=0;i<seen.length;i++)if(!seen[i])missing.add(i);return missing;
    }
    /** Reconciliation invalidates a receipt whose staged file is missing or corrupt. */
    public boolean invalidateChunkReceipt(String id,int index,long now) {
        if(index<0||now<0)throw new IllegalArgumentException("Invalid receipt");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Cursor c=db.rawQuery("SELECT state FROM lan_transfer_tasks WHERE transfer_id=?",new String[]{id});String state;
            try{if(!c.moveToFirst())throw new IllegalArgumentException("Unknown transfer");state=c.getString(0);}finally{c.close();}
            if(terminal(state))throw new IllegalStateException("Terminal transfer cannot be changed");
            int deleted=db.delete("lan_transfer_chunks","transfer_id=? AND chunk_index=?",new String[]{id,Integer.toString(index)});
            ContentValues v=new ContentValues();v.put("updated_at_ms",now);if(deleted>0&&"verifying".equals(state))v.put("state","transferring");
            db.update("lan_transfer_tasks",v,"transfer_id=?",new String[]{id});db.setTransactionSuccessful();return deleted>0;
        } finally { db.endTransaction(); }
    }
    /** Mark completed only after the caller verified the whole hash and atomically published. */
    public boolean markCompletedAfterPublish(String id,String hash,String publishedPath,long now) {
        if(hash==null||publishedPath==null||publishedPath.isEmpty()||now<0)throw new IllegalArgumentException("Invalid publication");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Cursor c=db.rawQuery("SELECT state,whole_sha256,total_chunks FROM lan_transfer_tasks WHERE transfer_id=?",new String[]{id});
            String state,expected;int total;try{if(!c.moveToFirst())throw new IllegalArgumentException("Unknown transfer");state=c.getString(0);expected=c.getString(1);total=c.getInt(2);}finally{c.close();}
            if(!"verifying".equals(state))throw new IllegalStateException("Transfer is not verifying");
            if(!expected.equals(hash))throw new SecurityException("Whole-file SHA-256 mismatch");
            Cursor n=db.rawQuery("SELECT COUNT(*) FROM lan_transfer_chunks WHERE transfer_id=?",new String[]{id});int count=0;try{if(n.moveToFirst())count=n.getInt(0);}finally{n.close();}
            if(count!=total)throw new IllegalStateException("Missing durable chunks");
            ContentValues v=new ContentValues();v.put("state","completed");v.put("published_path",publishedPath);v.put("updated_at_ms",now);db.update("lan_transfer_tasks",v,"transfer_id=?",new String[]{id});db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
    }
    public List<ChunkReceipt> loadChunkReceiptsPage(String id,int startIndex,int limit) {
        if(startIndex<0||limit<1||limit>256)throw new IllegalArgumentException("Invalid receipt page bounds");
        List<ChunkReceipt> out=new ArrayList<>();
        Cursor c=getReadableDatabase().rawQuery("SELECT transfer_id,chunk_index,payload_size,chunk_sha256,staging_path,recorded_at_ms FROM lan_transfer_chunks WHERE transfer_id=? AND chunk_index>=? ORDER BY chunk_index LIMIT ?",
                new String[]{id,Integer.toString(startIndex),Integer.toString(limit)});
        try { while(c.moveToNext())out.add(new ChunkReceipt(c.getString(0),c.getInt(1),c.getInt(2),c.getString(3),c.getString(4),c.getLong(5))); }
        finally { c.close(); }
        return out;
    }

    public List<ChunkReceipt> loadChunkReceipts(String id) {
        List<ChunkReceipt> out=new ArrayList<>();Cursor c=getReadableDatabase().rawQuery("SELECT transfer_id,chunk_index,payload_size,chunk_sha256,staging_path,recorded_at_ms FROM lan_transfer_chunks WHERE transfer_id=? ORDER BY chunk_index",new String[]{id});
        try{while(c.moveToNext())out.add(new ChunkReceipt(c.getString(0),c.getInt(1),c.getInt(2),c.getString(3),c.getString(4),c.getLong(5)));}finally{c.close();}return out;
    }
    public void closeStore(){close();}
}