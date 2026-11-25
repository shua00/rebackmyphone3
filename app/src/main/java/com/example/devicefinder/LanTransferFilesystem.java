package com.example.devicefinder;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileDescriptor;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Private-file staging for LAN transfers. A receipt is committed only after bytes and
 * containing directory have been fsynced. The SQLite ledger is never treated as proof
 * that a staging file still exists or remains intact after process/device restart.
 */
public final class LanTransferFilesystem {
    public static final long DEFAULT_STAGING_QUOTA_BYTES=4L*1024L*1024L*1024L;
    private static final long MIN_FREE_SPACE_RESERVE_BYTES=16L*1024L*1024L;
    private final LanTransferStore store;
    private final File root;
    private final File publishedRoot;
    private final long quotaBytes;

    public LanTransferFilesystem(Context context,LanTransferStore store) {
        this(context,store,DEFAULT_STAGING_QUOTA_BYTES);
    }
    public LanTransferFilesystem(Context context,LanTransferStore store,long quotaBytes) {
        if(context==null||store==null||quotaBytes<1)throw new IllegalArgumentException("Context, store and positive quota are required");
        this.store=store;
        this.root=new File(context.getFilesDir(),"lan-transfer-staging");
        this.publishedRoot=new File(context.getFilesDir(),"lan-transfer-received");
        this.quotaBytes=quotaBytes;
    }

    private static boolean existsNoFollow(File file) throws IOException {
        try { Os.lstat(file.getAbsolutePath()); return true; }
        catch(ErrnoException e) { if(e.errno==OsConstants.ENOENT)return false;throw new IOException("Unable to inspect staging path",e); }
    }
    private static StructStat statNoFollow(File file) throws IOException {
        try { return Os.lstat(file.getAbsolutePath()); }
        catch(ErrnoException e) { throw new IOException("Unable to inspect file",e); }
    }
    private static void rejectSymlink(File file) throws IOException {
        if(existsNoFollow(file)&&OsConstants.S_ISLNK(statNoFollow(file).st_mode))throw new IOException("Symlink is not allowed in transfer storage");
    }
    private static void syncDirectory(File directory) throws IOException {
        FileDescriptor fd=null;
        try { fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY,0);Os.fsync(fd); }
        catch(ErrnoException e) { throw new IOException("Unable to fsync transfer directory",e); }
        finally { if(fd!=null)try{Os.close(fd);}catch(ErrnoException ignored){} }
    }
    private static void ensureDirectory(File directory) throws IOException {
        rejectSymlink(directory);
        if(!existsNoFollow(directory)) {
            if(!directory.mkdir()&&!directory.isDirectory())throw new IOException("Unable to create transfer directory");
            File parent=directory.getParentFile();if(parent!=null&&existsNoFollow(parent))syncDirectory(parent);
        }
        rejectSymlink(directory);
        if(!directory.isDirectory())throw new IOException("Transfer path is not a directory");
    }
    private File taskDirectory(String transferId) throws IOException {
        if(transferId==null||!transferId.matches("[A-Za-z0-9._:-]{1,128}"))throw new IOException("Invalid transfer ID path");
        ensureDirectory(root);
        File dir=new File(root,transferId);
        String expected=new File(root.getCanonicalFile(),transferId).getCanonicalPath();
        if(!expected.equals(dir.getCanonicalPath()))throw new IOException("Transfer path escaped staging root");
        ensureDirectory(dir);
        return dir;
    }
    private File expectedChunkFile(String transferId,int index) throws IOException {
        if(index<0)throw new IOException("Invalid chunk index");
        return new File(taskDirectory(transferId),index+".part");
    }
    private static String hex(byte[] bytes) {
        final char[] digits="0123456789abcdef".toCharArray();StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes){int v=b&255;out.append(digits[v>>>4]).append(digits[v&15]);}return out.toString();
    }
    private static String hashBytes(byte[] bytes) throws IOException {
        try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IOException("SHA-256 unavailable",e);}
    }
    private static String hashFile(File file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);
            }
            return hex(digest.digest());
        } catch(IOException e){throw e;}catch(Exception e){throw new IOException("Unable to hash transfer file",e);}
    }
    private static void requireRegularFile(File file) throws IOException {
        rejectSymlink(file);
        if(!existsNoFollow(file)||!file.isFile())throw new IOException("Expected regular transfer file");
    }
    private long directoryBytes(File dir) throws IOException {
        if(!existsNoFollow(dir))return 0;
        rejectSymlink(dir);
        if(!dir.isDirectory())throw new IOException("Unexpected non-directory in transfer root");
        long total=0;File[] children=dir.listFiles();if(children==null)throw new IOException("Unable to list transfer directory");
        for(File child:children) {
            rejectSymlink(child);StructStat st=statNoFollow(child);
            if(child.isDirectory())total=Math.addExact(total,directoryBytes(child));
            else if(child.isFile())total=Math.addExact(total,child.length());
            else throw new IOException("Unsupported filesystem object in transfer staging");
        }
        return total;
    }
    private static void atomicRename(File from,File to) throws IOException {
        rejectSymlink(from);rejectSymlink(to);
        try { Os.rename(from.getAbsolutePath(),to.getAbsolutePath()); }
        catch(ErrnoException e){throw new IOException("Atomic transfer file publish failed",e);}
        syncDirectory(to.getParentFile());
    }

    /**
     * Durable chunk write. Hash and exact chunk length are checked before writing.
     * Returns false for an identical already-receipted chunk.
     */
    public synchronized boolean writeChunk(String transferId,int index,byte[] payload,long nowMs) throws IOException {
        LanTransferStore.Transfer transfer=store.getTransfer(transferId);
        if(transfer==null)throw new IOException("Unknown transfer task");
        if(!"transferring".equals(transfer.state))throw new IOException("Transfer is not accepting chunks");
        if(payload==null||index<0||index>=transfer.totalChunks)throw new IOException("Invalid chunk");
        int expected=(int)Math.min((long)transfer.chunkSizeBytes,transfer.offer.sizeBytes-(long)index*transfer.chunkSizeBytes);
        if(expected<=0||payload.length!=expected)throw new IOException("Chunk length mismatch");
        String digest=hashBytes(payload);
        File dir=taskDirectory(transferId), target=new File(dir,index+".part");
        rejectSymlink(target);
        for(LanTransferStore.ChunkReceipt receipt:store.loadChunkReceipts(transferId)) {
            if(receipt.index==index) {
                requireRegularFile(target);
                if(target.length()!=payload.length||!receipt.sha256.equals(digest)||!digest.equals(hashFile(target)))
                    throw new IOException("Conflicting replay for durable chunk");
                return false;
            }
        }
        long current=directoryBytes(root);
        if(current+payload.length>quotaBytes)throw new IOException("LAN transfer staging quota exceeded");
        if(root.getUsableSpace()<payload.length+MIN_FREE_SPACE_RESERVE_BYTES)throw new IOException("Insufficient free space for transfer chunk");
        File temp=new File(dir,index+".tmp-"+UUID.randomUUID());
        try {
            try(FileOutputStream out=new FileOutputStream(temp,false)) { out.write(payload);out.flush();out.getFD().sync(); }
            requireRegularFile(temp);
            if(temp.length()!=payload.length||!digest.equals(hashFile(temp)))throw new IOException("Staged chunk verification failed");
            atomicRename(temp,target);
            // ACK eligibility begins only after the durable file and directory entry exist.
            store.recordDurableChunk(transferId,index,payload.length,digest,target.getCanonicalPath(),nowMs);
            return true;
        } finally { if(existsNoFollow(temp))temp.delete(); }
    }

    /**
     * Re-check every receipt against the actual private file after restart. Corrupt/missing
     * receipts are removed so the sender will resend those chunks. Unreceipted crash leftovers
     * are discarded; no database row alone is accepted as integrity proof.
     */
    public synchronized List<Integer> reconcile(String transferId,long nowMs) throws IOException {
        LanTransferStore.Transfer transfer=store.getTransfer(transferId);
        if(transfer==null)throw new IOException("Unknown transfer task");
        if("completed".equals(transfer.state)||"rejected".equals(transfer.state)
                ||"cancelled".equals(transfer.state)||"failed".equals(transfer.state)
                ||"expired".equals(transfer.state)) return new ArrayList<>();
        File dir=taskDirectory(transferId);
        List<Integer> valid=new ArrayList<>();
        for(LanTransferStore.ChunkReceipt receipt:store.loadChunkReceipts(transferId)) {
            File expected=expectedChunkFile(transferId,receipt.index);
            boolean ok=false;
            try {
                requireRegularFile(expected);
                ok=expected.getCanonicalPath().equals(receipt.stagingPath)
                        &&expected.length()==receipt.sizeBytes
                        &&receipt.sha256.equals(hashFile(expected));
            } catch(IOException invalid) { ok=false; }
            if(ok)valid.add(receipt.index);
            else {
                store.invalidateChunkReceipt(transferId,receipt.index,nowMs);
                if(existsNoFollow(expected))expected.delete();
            }
        }
        File[] files=dir.listFiles();
        if(files==null)throw new IOException("Unable to list transfer staging files");
        for(File file:files) {
            rejectSymlink(file);
            String name=file.getName();
            if(name.contains(".tmp-")) { file.delete();continue; }
            if(name.endsWith(".part")) {
                String stem=name.substring(0,name.length()-5);
                try {
                    int index=Integer.parseInt(stem);
                    if(!valid.contains(index))file.delete();
                } catch(NumberFormatException invalid) { throw new IOException("Unexpected staging filename",invalid); }
            } else throw new IOException("Unexpected staging entry");
        }
        syncDirectory(dir);
        return store.missingChunkIndexes(transferId);
    }

    /** Assemble verified chunks, check the whole-file digest, fsync and atomically publish. */
    public synchronized File assembleAndPublish(String transferId,long nowMs) throws IOException {
        LanTransferStore.Transfer transfer=store.getTransfer(transferId);
        if(transfer==null)throw new IOException("Unknown transfer task");
        File target=new File(publishedRoot,transferId+"-"+transfer.offer.filename);
        if("completed".equals(transfer.state)) {
            requireRegularFile(target);
            if(target.length()!=transfer.offer.sizeBytes||!transfer.offer.sha256.equals(hashFile(target)))
                throw new SecurityException("Published file no longer matches completed transfer");
            return target;
        }
        if(!store.missingChunkIndexes(transferId).isEmpty())throw new IOException("Transfer still has missing chunks");
        if("transferring".equals(transfer.state))store.transition(transferId,"verifying",nowMs);
        else if(!"verifying".equals(transfer.state))throw new IOException("Transfer is not ready to verify");
        ensureDirectory(publishedRoot);
        if(existsNoFollow(target)) {
            requireRegularFile(target);
            if(target.length()==transfer.offer.sizeBytes&&transfer.offer.sha256.equals(hashFile(target))) {
                store.markCompletedAfterPublish(transferId,transfer.offer.sha256,target.getCanonicalPath(),nowMs);
                cleanupStaging(transferId);
                return target;
            }
            throw new IOException("Published transfer destination already exists with different content");
        }
        File temp=new File(publishedRoot,transferId+".assembling-"+UUID.randomUUID());
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IOException("SHA-256 unavailable",e);}
        try {
            try(FileOutputStream raw=new FileOutputStream(temp,false);
                    BufferedOutputStream out=new BufferedOutputStream(raw)) {
                for(int i=0;i<transfer.totalChunks;i++) {
                    File chunk=expectedChunkFile(transferId,i);requireRegularFile(chunk);
                    int expected=(int)Math.min((long)transfer.chunkSizeBytes,transfer.offer.sizeBytes-(long)i*transfer.chunkSizeBytes);
                    if(chunk.length()!=expected)throw new IOException("Staged chunk length changed");
                    try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(chunk))) {
                        byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);digest.update(buffer,0,n);}
                    }
                }
                out.flush();
                raw.getFD().sync();
            }
            syncDirectory(publishedRoot);
            String actual=hex(digest.digest());
            if(!transfer.offer.sha256.equals(actual)) {
                store.transition(transferId,"failed",nowMs);
                cleanupStaging(transferId);
                throw new SecurityException("Whole-file SHA-256 mismatch");
            }
            if(existsNoFollow(target))throw new IOException("Published transfer destination already exists");
            atomicRename(temp,target);
            store.markCompletedAfterPublish(transferId,actual,target.getCanonicalPath(),nowMs);
            cleanupStaging(transferId);
            return target;
        } catch(IOException|RuntimeException failure) {
            if(existsNoFollow(temp))temp.delete();
            throw failure;
        }
    }

    public synchronized void cancelAndCleanup(String transferId,long nowMs) throws IOException {
        LanTransferStore.Transfer t=store.getTransfer(transferId);
        if(t==null)throw new IOException("Unknown transfer task");
        if(!"cancelled".equals(t.state)&&!"completed".equals(t.state)&&!"rejected".equals(t.state)
                &&!"failed".equals(t.state)&&!"expired".equals(t.state))store.transition(transferId,"cancelled",nowMs);
        cleanupStaging(transferId);
    }
    public synchronized void cleanupStaging(String transferId) throws IOException {
        File dir=taskDirectory(transferId);
        deleteTreeNoFollow(dir);
        File parent=dir.getParentFile();if(parent!=null)syncDirectory(parent);
    }
    private static void deleteTreeNoFollow(File file) throws IOException {
        rejectSymlink(file);
        if(!existsNoFollow(file))return;
        StructStat st=statNoFollow(file);
        if(file.isDirectory()) {
            File[] children=file.listFiles();if(children==null)throw new IOException("Unable to clean transfer directory");
            for(File child:children)deleteTreeNoFollow(child);
        }
        if(!file.delete())throw new IOException("Unable to remove transfer staging entry");
    }
}
