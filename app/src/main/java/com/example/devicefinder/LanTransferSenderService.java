package com.example.devicefinder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated foreground lifecycle for file sending; isolated from chat and tracking executors. */
public final class LanTransferSenderService extends Service {
    public static final String ACTION_SEND="com.example.devicefinder.LAN_TRANSFER_SEND";
    private static final String PREFS="lan_transfer_sender_jobs";
    private static final String JOB="active_job_v1";
    private static final int NOTIFICATION_ID=8842;
    private ExecutorService executor;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());

    public static void enqueue(Context context, LanTransferStore.Offer offer, String host, int port, String path) throws Exception {
        JSONObject j=new JSONObject();
        j.put("id",offer.transferId);j.put("sender",offer.senderPeerId);j.put("target",offer.targetPeerId);
        j.put("filename",offer.filename);j.put("size",offer.sizeBytes);j.put("sha",offer.sha256);
        j.put("created",offer.createdAtMs);j.put("mime",offer.mimeType);j.put("host",host);j.put("port",port);j.put("path",path);
        if(!context.getApplicationContext().getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(JOB,j.toString()).commit())
            throw new IllegalStateException("Unable to persist outgoing task metadata");
        Intent intent=new Intent(context,LanTransferSenderService.class).setAction(ACTION_SEND);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"LanTransfer-Sender");t.setDaemon(false);return t;});
        createChannel();
        startForeground(NOTIFICATION_ID,notification("文件传输任务已恢复/等待网络"));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && !ACTION_SEND.equals(intent.getAction())) { stopSelf(startId);return START_NOT_STICKY; }
        try {
            String raw=getSharedPreferences(PREFS,MODE_PRIVATE).getString(JOB,null);
            if(raw==null) { stopForeground(true);stopSelf(startId);return START_NOT_STICKY; }
            JSONObject j=new JSONObject(raw);
            executor.execute(()->runJob(j,startId));
        } catch(Exception failure) {
            FileLogger.log(this,"LAN_TRANSFER","Unable to restore outgoing task: "+failure.getClass().getSimpleName());
            stopSelf(startId);
        }
        return START_STICKY;
    }

    private void runJob(JSONObject j,int startId) {
        LanTransferStore store=null;
        boolean retry=false;
        try {
            LanTransferCredentials.Value credentials=LanTransferCredentials.load(this);
            if(credentials==null) throw new SecurityException("Transfer credentials are not provisioned");
            String local=TrackingService.getUniqueDeviceId(this);
            LanTransferStore.Offer offer=new LanTransferStore.Offer(j.getString("id"),j.getString("sender"),
                j.getString("target"),j.getString("filename"),j.getLong("size"),j.getString("sha"),
                j.getLong("created"),j.getString("mime"));
            if(!local.equals(offer.senderPeerId)) throw new SecurityException("Outgoing task belongs to another local identity");
            File source=new File(j.getString("path")).getCanonicalFile();
            if(!source.getPath().startsWith(new File(getFilesDir(),"lan-transfer-outbox").getCanonicalPath()+File.separator))
                throw new SecurityException("Snapshot path is outside private outbox");
            store=new LanTransferStore(this);
            LanTransferSenderWorker worker=new LanTransferSenderWorker(store,credentials,()->selectWifiNetwork(),
                source,offer,local,j.getString("host"),j.getInt("port"),LanTransferStore.DEFAULT_CHUNK_SIZE,
                LanTransferSenderWorker.DEFAULT_MAX_TRANSFER_SESSIONS,LanTransferSenderWorker.DEFAULT_OFFER_WAIT_MS,
                new LanTransferSenderWorker.Listener() {
                    @Override public void onState(String state) {
                        updateNotification("文件传输："+state);
                        Intent update=new Intent("ACTION_LAN_TRANSFER_UPDATED").setPackage(getPackageName());
                        update.putExtra("transfer_id",offer.transferId).putExtra("state",state);
                        sendBroadcast(update);
                        if("completed".equals(state)||"cancelled".equals(state)||"failed".equals(state)
                                ||"rejected".equals(state)||"expired".equals(state))
                            getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(JOB).commit();
                    }
                    @Override public void onProgress(int sent,int batch) { updateNotification("文件传输中："+sent+"/"+batch+" 块"); }
                });
            worker.run();
            LanTransferStore.Transfer current=store.getTransfer(offer.transferId);
            retry=current!=null && ("offered".equals(current.state) || "paused".equals(current.state));
        } catch(Exception failure) {
            FileLogger.log(this,"LAN_TRANSFER","Sender job failed: "+failure.getClass().getSimpleName());
            updateNotification("文件发送暂不可用，可稍后重试");
        } finally {
            if(store!=null) store.closeStore();
            if(retry && !isDestroyed()) {
                updateNotification("等待对端接受或网络恢复，稍后自动重试");
                retryHandler.postDelayed(() -> {
                    if(executor!=null && !executor.isShutdown()) {
                        try { executor.execute(() -> runJob(j,startId)); }
                        catch(java.util.concurrent.RejectedExecutionException ignored) { stopSelf(startId); }
                    }
                }, 60000L);
            } else {
                stopSelf(startId);
            }
        }
    }

    private Network selectWifiNetwork() throws java.io.IOException {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null) throw new java.io.IOException("ConnectivityManager unavailable");
        List<Network> wifi=new ArrayList<>();boolean incomplete=false;
        for(Network n:cm.getAllNetworks()) {
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            if(c==null){incomplete=true;continue;}
            if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi.add(n);
        }
        if(wifi.isEmpty()) { if(incomplete)throw new java.io.IOException("Wi-Fi snapshot incomplete");return null; }
        Network active=cm.getActiveNetwork();
        if(active!=null && wifi.contains(active)) return active;
        if(wifi.size()==1)return wifi.get(0);
        throw new java.io.IOException("Multiple non-default Wi-Fi networks; refusing ambiguous route");
    }

    private void createChannel() {
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel c=new NotificationChannel("lan_transfer_sender","局域网文件传输",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }
    private Notification notification(String text) {
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"lan_transfer_sender"):new Notification.Builder(this);
        Intent open=new Intent(this,LanTransferActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,8842,open,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        return b.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("局域网文件传输").setContentText(text)
            .setContentIntent(pi).setOngoing(true).build();
    }
    private void updateNotification(String text) {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(nm!=null)nm.notify(NOTIFICATION_ID,notification(text));
    }
    @Override public void onDestroy() { retryHandler.removeCallbacksAndMessages(null);if(executor!=null)executor.shutdownNow();super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
