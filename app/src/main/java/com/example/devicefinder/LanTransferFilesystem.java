package com.example.devicefinder;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Private, single-file-per-transfer staging. Chunks are written at deterministic offsets,
 * fsynced before SQLite receipts, and rehashed on restart. This avoids creating up to 524k
 * individual files for a 2 GiB transfer negotiated at the 4 KiB minimum chunk size.
 */
public final class LanTransferFilesystem {
    /** Injectable only to let Robolectric assert ordering; production defaults to real Android fsync/rename syscalls. */
    public interface DurableFileOps {
        void syncDirectory(File directory) throws IOException;
        void atomicRename(File source,File target) throws IOException;
    }
    private static final class AndroidDurableFileOps implements DurableFileOps {
        @Override public void syncDirectory(File dir)throws IOException {
            FileDescriptor fd=null;
            try{fd=Os.open(dir.getAbsolutePath(),OsConstants.O_RDONLY,0);Os.fsync(fd);}
            catch(ErrnoException e){throw new IOException("Unable to fsync transfer directory",e);}
            finally{if(fd!=null)try{Os.close(fd);}catch(ErrnoException ignored){}}
        }
        @Override public void atomicRename(File from,File to)throws IOException {
            try{Os.rename(from.getAbsolutePath(),to.getAbsolutePath());}
            catch(ErrnoException e){throw new IOException("Atomic transfer publish failed",e);}
        }
    }
    public static final long DEFAULT_STAGING_QUOTA_BYTES=4L*1024L*1024L*1024L;
    private static final long MIN_FREE_SPACE_RESERVE_BYTES=16L*1024L*1024L;
    private final LanTransferStore store;
    private final File root,publishedRoot;
    private final long quotaBytes;
    private final DurableFileOps fileOps;

    public LanTransferFilesystem(Context context,LanTransferStore store) {
        this(context,store,DEFAULT_STAGING_QUOTA_BYTES);
    }
    public LanTransferFilesystem(Context context,LanTransferStore store,long quotaBytes) {
        this(context,store,quotaBytes,new AndroidDurableFileOps());
    }
    LanTransferFilesystem(Context context,LanTransferStore store,long quotaBytes,DurableFileOps fileOps) {
        if(context==null||store==null||quotaBytes<1||fileOps==null)throw new IllegalArgumentException("Context, store, quota and file operations are required");
        this.store=store;this.root=new File(context.getFilesDir(),"lan-transfer-staging");
        this.publishedRoot=new File(context.getFilesDir(),"lan-transfer-received");this.quotaBytes=quotaBytes;
        this.fileOps=fileOps;
    }

    private static String expectedCanonicalPath(File file)throws IOException {
        File parent=file.getParentFile();
        return parent==null?file.getCanonicalPath():new File(parent.getCanonicalFile(),file.getName()).getPath();
    }
    private static void rejectSymlink(File file)throws IOException {
        if(!file.getCanonicalPath().equals(expectedCanonicalPath(file)))
            throw new IOException("Symlink is not allowed in transfer storage");
    }
    private static boolean existsNoFollow(File file)throws IOException {
        rejectSymlink(file);return file.exists();
    }
    private static void deleteEntryNoFollow(File file)throws IOException {
        String expected=expectedCanonicalPath(file),actual=file.getCanonicalPath();
        if(!actual.equals(expected)) {
            if(!file.delete())throw new IOException("Unable to unlink transfer symlink");
        } else if(file.exists()&&!file.delete())throw new IOException("Unable to delete transfer file");
    }
    private void ensureDirectory(File dir)throws IOException {
        rejectSymlink(dir);
        if(!dir.exists()) {
            if(!dir.mkdir()&&!dir.isDirectory())throw new IOException("Unable to create transfer directory");
            File parent=dir.getParentFile();if(parent!=null&&parent.exists())syncDirectory(parent);
        }
        rejectSymlink(dir);
        if(!dir.isDirectory())throw new IOException("Transfer path is not a directory");
    }
    private File taskDirectory(String id)throws IOException {
        if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}"))throw new IOException("Invalid transfer ID");
        ensureDirectory(root);File dir=new File(root,id);ensureDirectory(dir);return dir;
    }
    private File stagingFile(String id)throws IOException {
        File dir=taskDirectory(id),file=new File(dir,"payload.part");rejectSymlink(file);return file;
    }
    private void syncDirectory(File dir)throws IOException { fileOps.syncDirectory(dir); }
    private static void syncFile(RandomAccessFile file)throws IOException { file.getFD().sync(); }
    private static String hex(byte[] b) {
        char[] d="0123456789abcdef".toCharArray();StringBuilder s=new StringBuilder(b.length*2);
        for(byte v:b){int n=v&255;s.append(d[n>>>4]).append(d[n&15]);}return s.toString();
    }
    private static String hashBytes(byte[] b)throws IOException {
        try{return hex(MessageDigest.getInstance("SHA-256").digest(b));}
        catch(Exception e){throw new IOException("SHA-256 unavailable",e);}
    }
    private static String hashRegion(RandomAccessFile file,long offset,int length)throws IOException {
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");file.seek(offset);
            byte[] buffer=new byte[64*1024];int remaining=length;
            while(remaining>0){int n=file.read(buffer,0,Math.min(buffer.length,remaining));if(n<0)throw new IOException("Staging file ended inside a receipted chunk");digest.update(buffer,0,n);remaining-=n;}
            return hex(digest.digest());
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Unable to hash staged chunk",e);}
    }
    private static String hashFile(File file)throws IOException {
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(file))){
                byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);
            }
            return hex(digest.digest());
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Unable to hash staged file",e);}
    }
    private static void requireRegularFile(File f)throws IOException {
        rejectSymlink(f);if(!f.exists()||!f.isFile())throw new IOException("Expected regular transfer file");
    }
    private void atomicRename(File from,File to)throws IOException {
        rejectSymlink(from);rejectSymlink(to);
        fileOps.atomicRename(from,to);
        syncDirectory(to.getParentFile());
    }

    /** Write at chunk offset, fsync data, sync new directory entry, then commit the receipt. */
    public synchronized boolean writeChunk(String id,int index,byte[] payload,long nowMs)throws IOException {
        LanTransferStore.Transfer t=store.getTransfer(id);
        if(t==null)throw new IOException("Unknown transfer");
        if(!"transferring".equals(t.state))throw new IOException("Transfer is not accepting chunks");
        if(payload==null||index<0||index>=t.totalChunks)throw new IOException("Invalid chunk");
        int expected=(int)Math.min((long)t.chunkSizeBytes,t.offer.sizeBytes-(long)index*t.chunkSizeBytes);
        if(expected<=0||payload.length!=expected)throw new IOException("Chunk length mismatch");
        String digest=hashBytes(payload);File data=stagingFile(id),dir=data.getParentFile();
        LanTransferStore.ChunkReceipt receipt=store.getChunkReceipt(id,index);
        existsNoFollow(data);
        if(receipt!=null) {
            requireRegularFile(data);
            try(RandomAccessFile raf=new RandomAccessFile(data,"r")) {
                long end=(long)index*t.chunkSizeBytes+payload.length;
                if(raf.length()<end||receipt.sizeBytes!=payload.length||!receipt.sha256.equals(digest)
                        ||!digest.equals(hashRegion(raf,(long)index*t.chunkSizeBytes,payload.length)))
                    throw new IOException("Conflicting replay for durable chunk");
            }
            return false;
        }
        if(store.getActiveReservedBytes()>quotaBytes)throw new IOException("LAN transfer staging quota exceeded");
        if(root.getUsableSpace()<payload.length+MIN_FREE_SPACE_RESERVE_BYTES)throw new IOException("Insufficient free space");
        try(RandomAccessFile raf=new RandomAccessFile(data,"rw")) {
            long end=(long)index*t.chunkSizeBytes+payload.length;
            if(raf.length()>t.offer.sizeBytes)raf.setLength(t.offer.sizeBytes);
            raf.seek((long)index*t.chunkSizeBytes);raf.write(payload);syncFile(raf);
        }
        syncDirectory(dir);
        store.recordDurableChunk(id,index,payload.length,digest,data.getCanonicalPath(),nowMs);
        return true;
    }

    /** Revalidate receipts in bounded pages; no DB receipt is trusted without rereading bytes. */
    public synchronized LanTransferStore.MissingPage reconcilePage(String id,long nowMs)throws IOException {
        LanTransferStore.Transfer t=store.getTransfer(id);
        if(t==null)throw new IOException("Unknown transfer");
        if("completed".equals(t.state)||"rejected".equals(t.state)||"cancelled".equals(t.state)
                ||"failed".equals(t.state)||"expired".equals(t.state))
            return new LanTransferStore.MissingPage(new ArrayList<>(),false);
        File dir=taskDirectory(id),data=new File(dir,"payload.part");
        boolean exists;
        try { exists=existsNoFollow(data); }
        catch(IOException unsafePath) {
            // Unlink an unexpected symlink itself; never follow its target.
            deleteEntryNoFollow(data);
            exists=false;
        }
        if(exists)requireRegularFile(data);
        int start=0;
        while(true) {
            List<LanTransferStore.ChunkReceipt> page=store.loadChunkReceiptsPage(id,start,128);
            if(page.isEmpty())break;
            for(LanTransferStore.ChunkReceipt receipt:page) {
                boolean ok=false;
                if(exists&&data.getCanonicalPath().equals(receipt.stagingPath)) {
                    try(RandomAccessFile raf=new RandomAccessFile(data,"r")) {
                        long offset=(long)receipt.index*t.chunkSizeBytes;
                        ok=receipt.index<t.totalChunks&&receipt.sizeBytes>0
                                &&raf.length()>=offset+receipt.sizeBytes
                                &&receipt.sizeBytes==(int)Math.min((long)t.chunkSizeBytes,t.offer.sizeBytes-offset)
                                &&receipt.sha256.equals(hashRegion(raf,offset,receipt.sizeBytes));
                    }
                }
                if(!ok)store.invalidateChunkReceipt(id,receipt.index,nowMs);
            }
            start=page.get(page.size()-1).index+1;
            if(page.size()<128)break;
        }
        if(exists) {
            try(RandomAccessFile raf=new RandomAccessFile(data,"rw")) {
                if(raf.length()>t.offer.sizeBytes){raf.setLength(t.offer.sizeBytes);syncFile(raf);}
            }
        }
        syncDirectory(dir);
        return store.getMissingChunkPage(id,0,128);
    }
    public synchronized List<Integer> reconcile(String id,long nowMs)throws IOException {
        return reconcilePage(id,nowMs).indexes;
    }
    public LanTransferStore.MissingPage missingChunkPage(String id,int startIndex,int limit) {
        return store.getMissingChunkPage(id,startIndex,limit);
    }

    /** Whole-file verification and same-filesystem atomic publish; idempotent across crash window. */
    public synchronized File assembleAndPublish(String id,long nowMs)throws IOException {
        LanTransferStore.Transfer t=store.getTransfer(id);
        if(t==null)throw new IOException("Unknown transfer");
        File target=new File(publishedRoot,id+"-"+t.offer.filename);
        ensureDirectory(publishedRoot);
        if("completed".equals(t.state)) {
            requireRegularFile(target);
            if(target.length()!=t.offer.sizeBytes||!t.offer.sha256.equals(hashFile(target)))
                throw new SecurityException("Published file no longer matches completed transfer");
            cleanupStaging(id);
            return target;
        }
        // Recover the rename-before-ledger-commit crash window before reconciling staging receipts.
        if(existsNoFollow(target)) {
            requireRegularFile(target);
            if(!"verifying".equals(t.state)||target.length()!=t.offer.sizeBytes
                    ||!t.offer.sha256.equals(hashFile(target)))
                throw new IOException("Published destination conflicts with transfer state or hash");
            store.markCompletedAfterPublish(id,t.offer.sha256,target.getCanonicalPath(),nowMs);
            cleanupStaging(id);
            return target;
        }
        LanTransferStore.MissingPage missing=reconcilePage(id,nowMs);
        if(!missing.indexes.isEmpty())throw new IOException("Transfer still has missing chunks");
        if("transferring".equals(t.state))store.transition(id,"verifying",nowMs);
        else if(!"verifying".equals(t.state))throw new IOException("Transfer is not ready to verify");
        File data=stagingFile(id);
        if(t.offer.sizeBytes==0&&!existsNoFollow(data)) {
            try(RandomAccessFile empty=new RandomAccessFile(data,"rw")){empty.setLength(0);syncFile(empty);}
            syncDirectory(data.getParentFile());
        }
        requireRegularFile(data);
        if(data.length()!=t.offer.sizeBytes)throw new IOException("Staging file length does not match Offer");
        String actual=hashFile(data);
        if(!t.offer.sha256.equals(actual)) {
            store.transition(id,"failed",nowMs);cleanupStaging(id);
            throw new SecurityException("Whole-file SHA-256 mismatch");
        }
        try(RandomAccessFile raf=new RandomAccessFile(data,"rw")){syncFile(raf);}
        atomicRename(data,target);
        syncDirectory(data.getParentFile());
        store.markCompletedAfterPublish(id,actual,target.getCanonicalPath(),nowMs);
        cleanupStaging(id);
        return target;
    }

    public synchronized void cancelAndCleanup(String id,long nowMs)throws IOException {
        LanTransferStore.Transfer t=store.getTransfer(id);if(t==null)throw new IOException("Unknown transfer");
        if(!"cancelled".equals(t.state)&&!"completed".equals(t.state)&&!"rejected".equals(t.state)
                &&!"failed".equals(t.state)&&!"expired".equals(t.state))store.transition(id,"cancelled",nowMs);
        cleanupStaging(id);
    }
    public synchronized void cleanupStaging(String id)throws IOException {
        File dir=taskDirectory(id),data=new File(dir,"payload.part");
        deleteEntryNoFollow(data);
        if(!dir.delete()&&dir.exists())throw new IOException("Unable to remove transfer staging directory");
        syncDirectory(root);
    }
}
