package com.example.devicefinder;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Creates an immutable app-private snapshot from a SAF document; never mutates the source URI. */
public final class LanTransferSnapshot {
    public static final class Result {
        public final File file;
        public final String transferId, filename, mimeType, sha256;
        public final long sizeBytes;
        Result(File f,String id,String name,String mime,String hash,long size) {
            file=f;transferId=id;filename=name;mimeType=mime;sha256=hash;sizeBytes=size;
        }
    }
    private LanTransferSnapshot() {}

    public static Result create(Context context, Uri uri) throws Exception {
        if (context==null || uri==null) throw new IllegalArgumentException("Document URI required");
        String name="shared-file";
        long declaredSize=-1;
        try (android.database.Cursor c=context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
            if(c!=null && c.moveToFirst()) {
                int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s=c.getColumnIndex(OpenableColumns.SIZE);
                if(n>=0 && c.getString(n)!=null) name=c.getString(n);
                if(s>=0 && !c.isNull(s)) declaredSize=c.getLong(s);
            }
        }
        name=name.replaceAll("[\\\\/\\p{Cntrl}]","_").trim();
        if(name.isEmpty() || ".".equals(name) || "..".equals(name)) name="shared-file";
        while(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>180 && !name.isEmpty())
            name=name.substring(0,name.length()-1);
        if(name.isEmpty()) name="shared-file";
        String mime=context.getContentResolver().getType(uri);
        if(mime==null || mime.trim().isEmpty() || mime.length()>127) mime="application/octet-stream";
        File dir=new File(context.getFilesDir(),"lan-transfer-outbox");
        if(!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Cannot create private transfer directory");
        String id=UUID.randomUUID().toString();
        File target=new File(dir,id+".snapshot");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long count=0;
        try(InputStream in=context.getContentResolver().openInputStream(uri);
            FileOutputStream out=new FileOutputStream(target,false)) {
            if(in==null) throw new java.io.IOException("Cannot open selected document");
            byte[] buffer=new byte[64*1024];
            int read;
            while((read=in.read(buffer))!=-1) {
                count+=read;
                if(count>LanTransferStore.MAX_FILE_BYTES) throw new java.io.IOException("File exceeds transfer size limit");
                digest.update(buffer,0,read);out.write(buffer,0,read);
            }
            out.getFD().sync();
        } catch(Exception failure) {
            target.delete();
            throw failure;
        }
        if(declaredSize>=0 && declaredSize!=count) { target.delete();throw new java.io.IOException("Document size changed while snapshotting"); }
        StringBuilder hash=new StringBuilder(64);
        for(byte b:digest.digest()) hash.append(String.format(Locale.US,"%02x",b&255));
        return new Result(target,id,name,mime,hash.toString(),count);
    }
}
