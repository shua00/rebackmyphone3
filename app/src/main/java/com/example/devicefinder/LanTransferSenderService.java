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
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated foreground lifecycle for file sending; isolated from chat and tracking executors. */
public final class LanTransferSenderService extends Service {
    public static final String ACTION_SEND="com.example.devicefinder.LAN_TRANSFER_SEND";
    public static final String ACTION_FEATURE_TOGGLED="com.example.devicefinder.LAN_TRANSFER_FEATURE_TOGGLED";
    private static final String PREFS="lan_transfer_sender_jobs";
    private static final String JOB="active_job_v1";
    private static final int NOTIFICATION_ID=8842;
    private ExecutorService executor;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean jobActive = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile LanTransferSenderWorker activeWorker;
    private volatile String lastWorkerState;

    public static boolean hasPendingJob(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).contains(JOB);
    }
    public static void onFeatureToggled(Context context, boolean enabled) {
        if (enabled) { resumeIfPending(context); return; }
        if (!hasPendingJob(context)) return;
        Intent intent = new Intent(context, LanTransferSenderService.class).setAction(ACTION_FEATURE_TOGGLED);
        try { context.startService(intent); }
        catch (Exception failure) {
            FileLogger.log(context, "LAN_TRANSFER", "Sender pause request deferred: "
                    + failure.getClass().getSimpleName());
        }
    }

    public static void resumeIfPending(Context context) {
        if(!LanTransferFeature.isEnabled(context) || !hasPendingJob(context)) return;
        Intent intent=new Intent(context,LanTransferSenderService.class).setAction(ACTION_SEND);
        try {
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
        } catch(Exception failure) {
            FileLogger.log(context,"LAN_TRANSFER","Pending sender restore deferred: "+failure.getClass().getSimpleName());
        }
    }
    public static void cancelLocal(Context context) {
        Intent intent=new Intent(context,LanTransferSenderService.class).setAction("com.example.devicefinder.LAN_TRANSFER_CANCEL_LOCAL");
        try { context.startService(intent); }
        catch(Exception failure) { FileLogger.log(context,"LAN_TRANSFER","Unable to request local cancel: "+failure.getClass().getSimpleName()); }
    }

    /** Legacy fixed-endpoint overload retained for controlled diagnostics. */
    public static void enqueue(Context context, LanTransferStore.Offer offer, String host, int port, String path) throws Exception {
        enqueueInternal(context, offer, host, port, path);
    }

    /** Production queue stores peer identity, not a stale IP/port. */
    public static void enqueue(Context context, LanTransferStore.Offer offer, String path) throws Exception {
        enqueueInternal(context, offer, null, 0, path);
    }

    private static void enqueueInternal(Context context, LanTransferStore.Offer offer,
            String host, int port, String path) throws Exception {
        if (!LanTransferFeature.isEnabled(context))
            throw new SecurityException("LAN file transfer feature is disabled");
        if (offer == null || path == null || path.trim().isEmpty())
            throw new IllegalArgumentException("Offer and private snapshot path are required");
        JSONObject j=new JSONObject();
        j.put("id",offer.transferId);j.put("sender",offer.senderPeerId);j.put("target",offer.targetPeerId);
        j.put("filename",offer.filename);j.put("size",offer.sizeBytes);j.put("sha",offer.sha256);
        j.put("created",offer.createdAtMs);j.put("mime",offer.mimeType);
        if (host != null && !host.trim().isEmpty() && port >= 1 && port <= 65535) {
            j.put("host",host); j.put("port",port);
        }
        j.put("path",path);
        android.content.SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        synchronized(LanTransferSenderService.class) {
            if(prefs.getString(JOB,null)!=null)
                throw new IllegalStateException("An outgoing transfer is already queued; finish or cancel it before starting another");
            if(!prefs.edit().putString(JOB,j.toString()).commit())
                throw new IllegalStateException("Unable to persist outgoing task metadata");
        }
        Intent intent=new Intent(context,LanTransferSenderService.class).setAction(ACTION_SEND);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"LanTransfer-Sender");t.setDaemon(false);return t;});
        createChannel();
        startForeground(NOTIFICATION_ID,notification("正在检查待处理文件传输任务"));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent != null && ACTION_FEATURE_TOGGLED.equals(intent.getAction())) {
            if (!LanTransferFeature.isEnabled(this)) {
                retryHandler.removeCallbacksAndMessages(null);
                LanTransferSenderWorker worker = activeWorker;
                if (worker != null) {
                    worker.pause();
                    updateNotification("文件传输已关闭：当前分块会话结束后暂停，任务保留");
                } else {
                    jobActive.set(false);
                    stopForeground(true);
                    stopSelf(startId);
                }
            }
            return START_NOT_STICKY;
        }
        if(intent!=null && "com.example.devicefinder.LAN_TRANSFER_CANCEL_LOCAL".equals(intent.getAction())) {
            LanTransferSenderWorker worker=activeWorker;
            if(worker!=null) {
                worker.cancel();
                updateNotification("正在停止本机发送；当前 TCP 分块会话结束后生效");
            } else {
                cancelPersistedLocalJob(startId);
            }
            return START_STICKY;
        }
        if(intent!=null && !ACTION_SEND.equals(intent.getAction())) { stopSelf(startId);return START_NOT_STICKY; }
        if (!LanTransferFeature.isEnabled(this)) {
            jobActive.set(false);
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            String raw=getSharedPreferences(PREFS,MODE_PRIVATE).getString(JOB,null);
            if(raw==null) { stopForeground(true);stopSelf(startId);return START_NOT_STICKY; }
            if(!jobActive.compareAndSet(false,true)) return START_STICKY;
            JSONObject j=new JSONObject(raw);
            executor.execute(()->runJob(j,startId));
        } catch(Exception failure) {
            jobActive.set(false);
            FileLogger.log(this,"LAN_TRANSFER","Unable to restore outgoing task: "+failure.getClass().getSimpleName());
            stopSelf(startId);
        }
        return START_STICKY;
    }

    private void runJob(JSONObject j,int startId) {
        if (!LanTransferFeature.isEnabled(this)) {
            FileLogger.log(this, "LAN_TRANSFER", "Outgoing job paused because feature is disabled");
            jobActive.set(false);
            stopSelf(startId);
            return;
        }
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
            LanTransferSenderWorker.NetworkProvider pathProvider = new LanTransferSenderWorker.NetworkProvider() {
                @Override public Network currentWifiNetwork() throws java.io.IOException {
                    for (LanNetworkPath path : currentLanNetworkPaths())
                        if (path.network != null) return path.network;
                    return null;
                }
                @Override public LanNetworkPath currentLanNetworkPath() throws java.io.IOException {
                    List<LanNetworkPath> paths = currentLanNetworkPaths();
                    return paths.isEmpty() ? null : paths.get(0);
                }
                @Override public List<LanNetworkPath> currentLanNetworkPaths() throws java.io.IOException {
                    return LanTransferSenderService.this.currentLanNetworkPaths();
                }
            };
            LanTransferSenderWorker worker=new LanTransferSenderWorker(store,credentials,pathProvider,
                source,offer,local,new LanTransferSenderWorker.EndpointProvider() {
                    @Override public LanTransferSenderWorker.Endpoint currentEndpoint() throws java.io.IOException {
                        List<LanTransferSenderWorker.Endpoint> all = currentEndpoints();
                        if (all.isEmpty()) throw new java.io.IOException("No fresh trusted-peer endpoint");
                        return all.get(0);
                    }
                    @Override public List<LanTransferSenderWorker.Endpoint> currentEndpoints() throws java.io.IOException {
                        List<LanTransferSenderWorker.Endpoint> all = new ArrayList<>();
                        for (LanTransferPeerDirectory.Candidate candidate :
                                LanTransferPeerDirectory.findCurrentAll(getApplicationContext(),
                                        offer.targetPeerId, System.currentTimeMillis())) {
                            if (candidate.isConnectable() && all.size() < 4)
                                all.add(new LanTransferSenderWorker.Endpoint(candidate.host,
                                        candidate.port, candidate.networkFingerprint));
                        }
                        if (all.isEmpty()) throw new java.io.IOException("Trusted peer has no fresh endpoint on active LAN paths");
                        return all;
                    }
                },LanTransferStore.DEFAULT_CHUNK_SIZE,
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
            activeWorker=worker;
            worker.run();
            activeWorker=null;
            LanTransferStore.Transfer current=store.getTransfer(offer.transferId);
            retry=current!=null && ("offered".equals(current.state) || "paused".equals(current.state));
        } catch(Exception failure) {
            FileLogger.log(this,"LAN_TRANSFER","Sender job failed: "+failure.getClass().getSimpleName());
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(JOB).commit();
            updateNotification("文件发送任务遇到不可恢复错误，请检查配对/文件后重新发起");
        } finally {
            activeWorker=null;
            if(store!=null) store.closeStore();
            if(retry && LanTransferFeature.isEnabled(this)) {
                updateNotification("等待对端接受或网络恢复，稍后自动重试");
                retryHandler.postDelayed(() -> {
                    if(executor!=null && !executor.isShutdown()) {
                        try { executor.execute(() -> runJob(j,startId)); }
                        catch(java.util.concurrent.RejectedExecutionException ignored) { jobActive.set(false);stopSelf(startId); }
                    }
                }, 60000L);
            } else {
                jobActive.set(false);
                stopSelf(startId);
            }
        }
    }


    private static String notificationTextForState(String state) {
        if ("waiting_for_acceptance".equals(state)) return "已发送文件请求，待对方接收";
        if ("waiting_for_network".equals(state)) return "尚未联系到接收设备，等待网络";
        if ("transferring".equals(state)) return "正在传输文件";
        if ("paused".equals(state)) return "文件传输暂停，等待恢复";
        if ("accepted".equals(state)) return "对方已接受，准备传输";
        if ("completed".equals(state)) return "文件已成功送达";
        if ("rejected".equals(state)) return "对方拒绝接收文件";
        if ("cancelled".equals(state)) return "文件发送已取消";
        if ("expired".equals(state)) return "文件传输请求已过期";
        if ("failed".equals(state)) return "文件传输失败";
        return "文件传输状态：" + state;
    }

    private void cancelPersistedLocalJob(int startId) {
        String raw=getSharedPreferences(PREFS,MODE_PRIVATE).getString(JOB,null);
        if(raw==null) { jobActive.set(false);stopSelf(startId);return; }
        LanTransferStore store=null;
        try {
            JSONObject j=new JSONObject(raw);
            store=new LanTransferStore(this);
            LanTransferStore.Transfer task=store.getTransfer(j.getString("id"));
            if(task!=null && ("offered".equals(task.state)||"accepted".equals(task.state)
                    ||"transferring".equals(task.state)||"paused".equals(task.state)))
                store.transition(j.getString("id"),"cancelled",System.currentTimeMillis());
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(JOB).commit();
            Intent update=new Intent("ACTION_LAN_TRANSFER_UPDATED").setPackage(getPackageName());
            update.putExtra("transfer_id",j.getString("id")).putExtra("state","cancelled_local");
            sendBroadcast(update);
            updateNotification("已取消本机发送任务；不会远程删除对端已接收的数据");
        } catch(Exception failure) {
            FileLogger.log(this,"LAN_TRANSFER","Local cancel failed: "+failure.getClass().getSimpleName());
            updateNotification("本机取消失败，请刷新任务状态");
        } finally {
            if(store!=null) store.closeStore();
            jobActive.set(false);
            stopSelf(startId);
        }
    }


    /** Enumerate every usable STA Network plus one unrepresented AP interface candidate. */
    private List<LanNetworkPath> currentLanNetworkPaths() throws java.io.IOException {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null) throw new java.io.IOException("ConnectivityManager unavailable");
        List<LanNetworkPath> wifi=new ArrayList<>();
        List<LanNetworkPath> localCandidates;
        java.util.Set<String> excludedInterfaces=new java.util.HashSet<>();
        boolean incomplete=false;
        try {
            for(Network network:cm.getAllNetworks()) {
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps==null) { incomplete=true; continue; }
                LinkProperties links=cm.getLinkProperties(network);
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if(links==null || links.getInterfaceName()==null || links.getInterfaceName().isEmpty())
                        incomplete=true;
                    else excludedInterfaces.add(links.getInterfaceName());
                    continue;
                }
                if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if(links==null || links.getInterfaceName()==null || links.getInterfaceName().isEmpty()
                        || links.getLinkAddresses()==null) { incomplete=true; continue; }
                boolean found=false;
                for(LinkAddress item:links.getLinkAddresses()) {
                    if(!(item.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address address=(Inet4Address)item.getAddress();
                    int prefix=item.getPrefixLength();
                    if(!LanNetworkPath.isUsableLanIpv4(address)||prefix<1||prefix>32) continue;
                    wifi.add(LanNetworkPath.fromAndroidNetwork(network,links.getInterfaceName(),
                            address,prefix,LanTransferIpv4Subnet.directedBroadcast(address,prefix)));
                    found=true;
                }
                if(!found) incomplete=true;
            }
            if(incomplete) throw new java.io.IOException("Incomplete Wi-Fi Network snapshot; refusing partial path set");
            localCandidates=LanNetworkPath.enumerateLocalInterfaceCandidates();
            LanTransferPathSet selected=LanTransferPathSet.select(wifi,localCandidates,excludedInterfaces);
            return selected.paths();
        } catch(java.io.IOException failure) { throw failure; }
        catch(Exception failure) { throw new java.io.IOException("Unable to enumerate active LAN paths",failure); }
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
    @Override public void onDestroy() { retryHandler.removeCallbacksAndMessages(null);jobActive.set(false);if(executor!=null)executor.shutdownNow();super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
