/*
 * ============================================================
 *  TrackingService.java — 设备同步与可靠通信服务
 * ------------------------------------------------------------
 *  构建批次 : 20261122-P2P-r21
 *  源码版本 : v2.5.21-r21（协议 v2-only / broadcast ACK completion and diagnostics）
 *  最后修改 : 2026-11-22
 *  体积目标 : 以现有约 140 KB APK 为基线；本轮不新增第三方库/运行时依赖
 *  性能原则 : 复用系统 API、既有回调/线程/上报链；无新增高频轮询、扫描或常驻线程
 * ------------------------------------------------------------
 *  本轮 LAN P2P 路由：
 *    - 复用 ConnectivityManager.NetworkCallback 与现有网络快照，不引入 Tailscale/Pion/libp2p/WebRTC。
 *    - 首次或缓存失效时广播随机 nonce challenge，只有收到对应 response 才记录对端 UDP endpoint；租约 120 秒；有效租约直接复用。
 *    - 已验证 endpoint 优先单播；无已验证 peer 时仅向当前子网广播一次；仅实际默认网络路径签名变化时清空 peer 表。
 *    - 网络指纹仅用于诊断，不授权单播；不以私网 IP、CIDR、SSID/BSSID 作为可达证明。
 *    - 当前验证仅适用于同一可互通 LAN；跨 NAT ICE、TURN/Relay 仍为未来扩展。
 *  本轮约束 : 移除 FIND_ME_MSG v1 双发/接收；保留现有 UDP 8888 监听、广播发送与所有非聊天业务。
 *  P2P 状态 : 按需 PEER_HELLO/PEER_ACK 挑战；缓存 120 秒验证过的 8888 endpoint，仅默认网络身份/IP/前缀/接口/网关等关键路径签名变化时清空；已验证成员逐成员单播；CHAT_ACK 必须匹配已验证 peer 的 ID/IP/端口；无 peer 时子网广播一次并保留待确认队列；消息接收先过滤自身 sender，再 ACK/去重；实机验证仍待完成。
 *  本轮改动 : 修复无已验证 peer 时广播 ACK 无法完成待确认消息的问题；广播 ACK 仅对当前 messageId 接受首个共享密钥认证的 8888 来源，不将其升级为已验证单播 peer；保留重试队列与逐 peer 严格 ACK。增加握手结果诊断。
 *  改动记录（倒序，仅列最近 3 条，更早见 CHANGELOG.txt）:
 *    - 2026-11-22 r21 广播 ACK 闭环：无 peer 时等待一个匹配 messageId 的认证 ACK，确认后回收该条待确认消息；未确认继续保留；不把广播 ACK 伪装成已验证 peer。
 *    - 2026-11-22 r20 发送路径诊断：记录异常 socket 锁等待、UDP send 耗时和 ACK 等待轮数/总耗时，不改变发送策略。
 *    - 2026-11-22 r19 聊天收包快路径：CHAT_MSG 提前到实时分发，先 ACK 后去重并异步处理历史/UI。
 *    - 2026-11-22 r18 接收循环减负：去掉普通包逐包 logcat；聊天 ACK 立即回包后再 FIFO 处理历史/UI；陪伴与警报命令转主线程串行执行。
 *    - 2026-11-22 r17 实时 ACK 快路径：握手/聊天 ACK 优先处理；移除完整 UDP 包日志，防止协议认证字段和聊天内容进入日志。
 *    - 2026-11-22 r16 LAN P2P 修复：单子网广播；自身回环包提前过滤；ACK 空成员计数修正；按真实网络路径代数失效 peer；UDP 慢分支诊断。
 *    2026-11-22 r15 后台持久化与时延诊断：同步 commit 移出 Service 主线程，增加持久化、UDP发送、ACK、接收时间日志。\n *    2026-11-22 r14 LAN P2P 快速发送：复用 120 秒已验证 peer endpoint，连续消息跳过重复 500ms 发现窗口；ACK 等待缩短为 250ms/轮，失败后撤销旧路由并在后续消息重新发现。\n *    2026-11-22 r4 轻量网络状态代数：网络快照关键字段变化时递增 network_generation；补齐头部架构/依赖/扩展边界说明，无新增第三方依赖。
 *    2026-11-21 09:30  网络与反馈扩展：默认网络快照（类型/IP/CIDR/接口/验证状态）；command_feedback 本地持久化、断网补交与服务端明确确认后回收；保留旧 executed_ids 和原离线补发机制
 *    2026-11-20 21:10  可靠性修复：[B1] 离线补发>FLUSH_BATCH_MAX 条时自我续跑（不再等网络状态变化）；
 *                      [B3] 批量下行响应解析由主线程 handler.post 移回工作线程（消除 ANR 风险）；
 *                      [C2] 内存级计时器撞车抑制时自我续期 + sendReport 去重抑制时补排下一轮，防周期链断裂；
 *                      [C3] 静止防抖合并同时检查被覆盖旧行的指令回执，不再吞回执；
 *                      [低危1] 服务端已确认的 executed_cmd_ids 及时回收；[低危3] 队列 rename 失败降级追加写不丢点；
 *                      [低危4] 队列超限裁剪留痕日志；[低危5] 移除死代码 truncateQueueFile 并校正架构注释
 *    2026-11-20 20:20  定时上报双保险：新增内存级 inMemoryReportRunnable，与硬件 AlarmManager 互补防限流
 *    2026-11-20 14:30  弱网与续航优化：新增 502/503/504/429 指数退避（找回特权/高优触发直通）及离线队列静止防抖合并
 *    2026-11-20 12:00  离线补发优化：新增 /api/report_batch 批量优先通道，不支持或部分接收自动逐条降级
 *    2026-11-17 21:00  P0级保活修复：闹钟改用 getForegroundService
 *    2026-11-13 13:00  插件挂载：远程陪伴（HTTP 通道 → 复用 CompanionModule 统一处理）
 *    2026-11-12 22:45  v2.0 通道可视化 + v2.0.1 架构优化 + 修复版本常量
 *    2026-11-11 19:45  注释规范统一（无逻辑改动）
 *    2026-11-07 23:11  （历史，摘要见 CHANGELOG.txt）
 * ============================================================
 */

/*
 * ============================================================
 *
 * 【全链路底层架构加固说明】
 * 1. 原子级并发去重：利用 ConcurrentHashMap.putIfAbsent 的硬件级原子性，
 *    彻底封杀 HTTP/UDP 双通道并发送达导致的“指令重复执行”与 OOM 崩溃。
 *    UDP 控制类指令与 HTTP 指令统一纳入去重矩阵，彻底消灭网络抖动导致的风暴执行。
 * 2. Handler 防爆队列：废弃无状态 postDelayed，改用带 what 标识的 Message，
 *    并在进队前调用 removeMessages。彻底解决网络切换风暴导致的自愈闭包堆积、
 *    fd 句柄爆炸与 CPU 100% 飙高问题。
 * 3. 前台挂载绝对原子化：onCreate 与 STICKY 重启时，所有耗时操作剥离至 Looper，
 *    确保 startForeground 在 5 秒内瞬间完成，根除 RemoteServiceException 秒杀。
 * 4. 防卡死线程池：引入有界队列与 DiscardOldest 拒绝策略，并缩短定位超时。
 * 5. 零崩溃绘制测算 (Anti-NPE)：气泡宽度测算剥离了尚未 attach 的 View 属性，
 *    改用纯内存 Paint 画笔边界计算，绝杀定制 ROM (MIUI/ColorOS) 上的底层闪退。
 * 6. 绝对文件流安全：离线队列的入队裁剪 (enforceQueueLimits) 与补发后的整表重写全部在
 *    FILE_LOCK 矩阵内完成，并统一经 writeQueueAtomically（同目录临时文件 + fsync + rename）
 *    原子替换；rename 失败时降级为追加写，绝不用空文件覆盖旧队列、也绝不丢本次新记录。
 *    （原 truncateQueueFile 已无任何调用点，2026-11-20 [低危5] 移除，裁剪职责由 enforceQueueLimits 承担）
 * 7. 闹钟防爆装甲 (Alarm Quota Armor)：在重排保活与主上报闹钟前强制 cancel
 *    旧的 PendingIntent 句柄，完美绕开 Android 12+ 的精确闹钟 Quota 节流斩杀。
 * 8. [补丁 2/3/4/5] 全局保活策略可调 (顶部 4 行) + onCreate 顶层秒起防线 +
 *    onTaskRemoved 无痕精准复活 + 三路闹钟强制 cancel 前置。
 * ============================================================
 *
 * 【📱 追踪引擎核心 ADB 命令行运维实战手册】
 * ------------------------------------------------------------
 * ▶ 1. 实时拦截并监控引擎输出 (按组件分流查看)
 *    - 仅看上报业务 : adb logcat -s "FileLogger" | grep "REPORT"
 *    - 仅看对讲机端 : adb logcat -s "FileLogger" | grep "UDP"
 *    - 仅看指令下发 : adb logcat -s "FileLogger" | grep "HTTP_CMD"
 *
 * ▶ 2. 强行模拟离线队列入队与补发测试
 *    - 开启飞行模式 : adb shell cmd connectivity airplane-mode enable
 *    - 触发一次上报 : adb shell am startservice -a ACTION_MANUAL_REPORT com.example.devicefinder/.TrackingService
 *    - 验证离线落盘 : adb shell cat /sdcard/Android/data/com.example.devicefinder/files/pending_reports.jsonl
 *    - 恢复网络并观察 : adb shell cmd connectivity airplane-mode disable (自愈引擎会在 5s 内自动清空补发)
 *
 * ▶ 3. 强行拉取底层运行日志 (故障诊断必杀技)
 *    - 脚本流 : adb shell "run-as com.example.devicefinder cat files/device_log.txt" > local_device_log.txt
 *    - 说明 : 若因网络限制无法通过高级设置拉取，可通过此 ADB 命令强制提取最底层的 FileLogger 快照。
 * ============================================================
 */

package com.example.devicefinder;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.RouteInfo;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

public class TrackingService extends Service implements LocationListener {

    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;
    // Observe Wi-Fi LAN separately: the default route may remain on cellular.
    private ConnectivityManager.NetworkCallback wifiLanNetworkCallback;
    private volatile String wifiLanFingerprint;
    // Exact Network that owns the currently published UDP listener socket.
    private volatile Network lanChatBoundWifiNetwork;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private PendingIntent alarmPendingIntent;

    private SensorManager sensorManager;
    private Sensor motionSensor;

    // ★ 补丁2：全局保活策略配置（后期调优直接改这里，单位：毫秒）
    private long keepAliveAlarmIntervalMs = 15 * 60 * 1000L;     // 保活闹钟（默认15分钟）
    private long reportAlarmIntervalMs = 30 * 60 * 1000L;        // 上报闹钟（默认30分钟）
    private long heartbeatAlarmIntervalMs = 6 * 60 * 60 * 1000L; // 心跳闹钟（默认6小时）
    private long taskRemovedRescueDelayMs = 2 * 1000L;           // 划掉后台后复活延迟（默认2秒）

    // ★ 上报间隔：初始值从 reportAlarmIntervalMs 继承（改顶部一行即可改起手默认），
    //   远端 config 的 report_interval_seconds 仍可动态覆盖
    private long reportIntervalMs = reportAlarmIntervalMs;
    private String accuracyMode = "coarse";
    private volatile Location lastLocation = null;
    private final AtomicInteger reportCount = new AtomicInteger(0);

    private boolean enableWifiLock = true;
    private boolean enableMotionTrigger = true;

    private static volatile long lastReportTime = 0;
    private final java.util.concurrent.atomic.AtomicBoolean reportInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final Object reportLock = new Object();
    private static volatile int consecutiveFailures = 0;
    private static volatile boolean isListeningForRescue = false;

    private static long lastNotificationClickTime = 0;
    private static int notificationClickCount = 0;
    private static long lastSensorTriggerTime = 0;

    private static String lastReceivedMsg = "";
    private static long lastReceivedMsgTime = 0;

    // ★ [修复] 2026-11-04 AI 将 URL 截断了，已恢复为你实际使用的后端接口
    private static String defaultServerUrl = "http://dingwei.bai001.de5.net/api/report";
    private static final String DEFAULT_REMOTE_CONFIG_URL = "https://your-config-url.com/config.txt";
    private static final String AUTH_TOKEN = "your_private_secret_key";
    private static final String RESCUE_SECRET = "your_private_secret_key";

    // ===== 协议前缀（P0-1 拆分）=====
    /** v2 悬浮文字：FIND_ME_FLOAT:secret:content（content 可含冒号） */
    private static final String FIND_ME_FLOAT_MSG = "FIND_ME_FLOAT";

    private static long lastConfigFetchTime = 0;
    private static final String PREF_BUBBLE = "bubble_pos_prefs";

    private static final String CHANNEL_ID_MESSAGE = "message_channel";
    private static final int NOTIF_ID_MESSAGE = 2001;
    private static final String PREF_ELDER_MODE = "elder_mode";
    private static final String CHANNEL_ID_SELF_CHECK = "self_check_channel";
    private static final int NOTIF_ID_SELF_CHECK = 3001;

    private BroadcastReceiver batteryMonitor;

    // ★ 2026-11-07 新增：供 SmsReceiver 等外围组件零锁读取的全局电量快照。
    //   TrackingService 启动后由 batteryMonitor 持续刷新，-1 表示尚未采集。
    public static volatile int GLOBAL_BATTERY_LEVEL = -1;

    private volatile boolean serviceDestroyed = false;
    // 每个进程只生成一次；进程被系统杀死后重启会得到新的 Session。
    private static final String PROCESS_SESSION_ID = java.util.UUID.randomUUID().toString();
    private static final long PROCESS_START_WALL_TIME_MS = System.currentTimeMillis();
    // 每次 TrackingService 实例创建时生成，用于区分同一进程内的服务重建。
    private final String serviceInstanceId = java.util.UUID.randomUUID().toString();
    private volatile DatagramSocket rescueSocketRef = null;
    // LAN P2P v2：消息 ACK 等待表。每条消息只保留一个短时 latch，不启动常驻线程。
    // Coalesce one bounded recheck when a broadcast ACK races its peer-route handshake.
    private final java.util.Set<String> pendingChatAckRouteRechecks =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> pendingChatAcks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> pendingChatAckPeers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingChatAckStartedAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    // 防止发送端重试/多端口重复广播时，同一消息在持久化完成前重复占用后台队列。
    private final java.util.concurrent.atomic.AtomicBoolean lanChatInboxProjectionInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // Preserve projection requests that arrive while another inbox batch is in flight.
    private final java.util.concurrent.atomic.AtomicBoolean lanChatInboxProjectionRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> pendingLanChatReceives =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    // LAN 对讲先持久化消息正文与消息 ID，再发 ACK；防止进程在 ACK 后、历史写入前退出。
    private final Object lanChatReceiveLock = new Object();
    private static final String PREF_LAN_CHAT_RECEIVED_IDS = "lan_chat_received_message_ids";
    // 本地 LAN 聊天待确认队列：SharedPreferences 同步 commit，最多保留 50 条。
    private static final String PREF_LAN_CHAT_QUEUE = "lan_p2p_pending_messages";
    private static final int LAN_CHAT_QUEUE_MAX = 50;
    private static final long LAN_CHAT_DISCOVERY_WINDOW_MS = LanChatStore.FIRST_DISCOVERY_WINDOW_MS;
    // Keep route refresh responsive without broadcasting once per keystroke/message.
    private static final long LAN_PEER_DISCOVERY_COOLDOWN_MS = 1_500L;
    // Keep discovery nonces valid long enough for bounded persistence/ACK work to finish.
    private static final long LAN_PEER_CHALLENGE_TIMEOUT_MS = 2_500L;
    private final Object lanChatQueueLock = new Object();
    private volatile LanChatStore lanChatStore;
    private final java.util.concurrent.atomic.AtomicBoolean lanChatRetryInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // Coalesce a recovery event that arrives while a retry batch is already in flight.
    private final java.util.concurrent.atomic.AtomicBoolean lanChatRecoveryEventPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // A route refresh is an ordinary retry wake, not permission to reopen an exhausted epoch.
    private final java.util.concurrent.atomic.AtomicBoolean lanChatRouteRefreshPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean lanChatRetryWakeScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> activeLanChatTransmissions =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    // Keep a message active until its asynchronous ACK observation window is reconciled.
    private final java.util.Set<String> lanChatAckGraceInFlight =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    // Preserve FIFO send order while discovery/ACK state is shared across messages.
    // Fair admission prevents a long retry batch from repeatedly barging ahead of fresh user sends.
    // Keep cross-lane serialization until per-stage measurements identify a safe narrower boundary.
    private final java.util.concurrent.locks.ReentrantLock lanChatTransmitLock =
            new java.util.concurrent.locks.ReentrantLock(true);
    // Invalidates queued sends and listener startups whenever the Wi-Fi LAN path changes.
    private final java.util.concurrent.atomic.AtomicLong lanSocketGeneration =
            new java.util.concurrent.atomic.AtomicLong(0L);
    // 仅缓存经本机 challenge/response 验证的 LAN peer；网络变化时必须清空。
    private final java.util.concurrent.ConcurrentHashMap<String, java.net.InetSocketAddress> verifiedChatPeers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> verifiedChatPeerSeen =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong verifiedChatPeerRouteVersionCounter =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.ConcurrentHashMap<String, Long> verifiedChatPeerRouteVersions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> pendingPeerChallenges =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Directed challenges are bound to the peer identity and exact source endpoint.
    // Broadcast discovery challenges intentionally leave both expectations unset.
    private final java.util.concurrent.ConcurrentHashMap<String, String> pendingPeerChallengeExpectedIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.net.InetSocketAddress> pendingPeerChallengeExpectedEndpoints =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Deduplicate directed challenges while they wait in the peer executor queue.
    private final java.util.concurrent.ConcurrentHashMap<String, Object> pendingPeerChallengeDispatches =
            new java.util.concurrent.ConcurrentHashMap<>();
    // 有效 peer 存在时也定期重新发现，纳入后来上线/加入局域网的设备。
    private volatile long lastPeerDiscoveryAt = 0L;
    private static final long PEER_DISCOVERY_REFRESH_MS = 30_000L;
    // 多条消息并发到达时只允许一个线程执行广播发现，避免重复探测和额外等待。
    private final java.util.concurrent.atomic.AtomicBoolean peerDiscoveryInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // 与 buildNetworkSnapshot 的关键路径代数对齐；普通 capabilities/link 回调不应清空有效 peer。
    private volatile long lastPeerRouteGeneration = -1L;

    private void refreshPeerRoutesForNetworkChange(String reason) {
        try {
            JSONObject snapshot = buildNetworkSnapshot();
            long generation = snapshot.optLong("network_generation", -1L);
            long previous = lastPeerRouteGeneration;
            lastPeerRouteGeneration = generation;
            if (previous >= 0L && generation >= 0L && generation != previous) {
                // The default Internet route can switch between cellular and Wi-Fi while
                // the underlying Wi-Fi LAN remains reachable. Do not revoke UDP peers
                // from this signal; refreshWifiLanTransport compares the actual Wi-Fi
                // interface/address/routes and owns LAN route invalidation and socket rebind.
                FileLogger.logD("CHAT", "默认网络路径变化但保留 LAN peer；等待 Wi-Fi LAN 指纹判断，generation="
                        + previous + "->" + generation + "，回调=" + reason);
            } else {
                FileLogger.logD("CHAT", "LAN P2P 保留已验证 peer：默认网络变化未影响 LAN，generation="
                        + generation + "，回调=" + reason);
            }
        } catch (Exception e) {
            FileLogger.log(this, "CHAT", "⚠️ 网络回调快照检查失败，保留 LAN peer 等待 Wi-Fi LAN 指纹检查："
                    + reason + " / " + e.getMessage());
        }
    }

    /** 网络路径变化立即撤销旧 LAN endpoint，避免跨子网沿用旧 IP。 */
    private void invalidateVerifiedChatPeers(String reason) {
        synchronized (verifiedChatPeerRouteVersions) {
            verifiedChatPeers.clear();
            verifiedChatPeerSeen.clear();
            verifiedChatPeerRouteVersions.clear();
        }
        pendingPeerChallenges.clear();
        pendingPeerChallengeExpectedIds.clear();
        pendingPeerChallengeExpectedEndpoints.clear();
        pendingPeerChallengeDispatches.clear();
        FileLogger.log(this, "CHAT", "LAN P2P peer 路由已失效，等待重新发现: " + reason);
    }
    // The listener-running flag is process-wide; its guard must be process-wide too.
    private static final Object rescueLock = new Object();
    // 多路唤醒若从不同线程同时进入自愈，只允许一个自愈检查在途。
    private final java.util.concurrent.atomic.AtomicBoolean selfHealInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // 看门狗线程
    private Thread watchdogThread;

    // ★ [核心优化] 2026-11-04 全局同步锁与键值，用于持久化“上次成功上报时间”，解决冷启动看门狗失效问题
    private static final Object FILE_LOCK = new Object();
    private static final String PREF_LAST_SUCCESS_REPORT_TIME = "last_success_report_time";

    // ==================== [HTTP下行] 字段区 ====================
    private static final String PREF_EXECUTED_CMD_IDS = "executed_cmd_ids";
    private static final int EXECUTED_CMD_IDS_MAX = 100;
    // ★ 2026-11-20 [低危1] 回执集合「读-改-写」互斥锁：上报线程（回收已确认回执）与
    //   parseResponse（追加新回执）可能在不同工作线程并发，无锁会造成丢更新。
    private final Object executedCmdIdsLock = new Object();
    // ★ v2.5.13-r1：设备端指令反馈单独持久化；HTTP 200 只有在服务端明确回报 feedback_accepted 后才回收。
    //   旧版 server2 未返回该字段时，反馈继续保留并重试，避免升级期间丢失执行结果。
    private static final String PREF_COMMAND_FEEDBACK = "command_feedback_v1";
    private static final int COMMAND_FEEDBACK_MAX = 100;
    private static final Object COMMAND_FEEDBACK_LOCK = new Object();
    // executing 状态在进程重启后无法判断是否已产生副作用；仅对本进程仍在执行的命令允许继续等待。
    private final java.util.Set<String> commandsInFlight = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    // 网络快照状态仅用于标识本机网络路径代数；不作为 UDP 可达性的证明。
    private static final Object NETWORK_STATE_LOCK = new Object();
    private static volatile String lastNetworkSignature = "";
    private static volatile long lastNetworkChangedAt = 0L;
    private static volatile long networkGeneration = 0L;
    private static final int FAST_POLL_BATTERY_THRESHOLD = 20;
    private static final int FAST_POLL_MAX_CONSECUTIVE = 5;
    private static final long CMD_TIME_WINDOW_MS = 60 * 1000L;

    private volatile boolean isFastPolling = false;
    private volatile int fastPollConsecutiveCount = 0;
    private final Object fastPollLock = new Object();
    private final java.util.Map<String, Long> recentExecutions =
            new java.util.concurrent.ConcurrentHashMap<>();
    // ==================== [HTTP下行] 字段区结束 ====================

    private final List<android.view.View> activeBubbles = new ArrayList<>();

    // ★ H4 修复 2026-11-07：前台通知去重标记。onCreate 首次成功挂起后置 true，
    //   后续 onStartCommand 普通 action 不再重发，避免 15 分钟保活导致通知图标闪烁。
    //   仅在 onCreate 失败 / ACTION_RESET_NOTIFICATION / ACTION_SECRET_UNLOCK 时重置。
    private volatile boolean isForegroundStarted = false;

    private long lastAlarmExpectedAt = 0;
    private long lastHeartbeatExpectedAt = 0;
    private long serviceCreateTimeMs = 0;
    private int udpBindFailCount = 0;

    private long lastLocationLogTime = 0;
    private static long lastOnTaskRemovedTime = 0;
    private static final long ON_TASK_REMOVED_THROTTLE_MS = 10 * 60 * 1000L;
    private static final String PENDING_REPORTS_FILE = "pending_reports.jsonl";
    private static final long PENDING_QUEUE_MAX_BYTES = 1024 * 1024;

    // ★ 新增：防重试闭包堆积的消息 ID
    private static final int MSG_RETRY_RESCUE_LISTENER = 8888;

    // ★ H1b 修复 2026-11-07：flush 之间串行化锁。
    //   原 H1 拆锁后，onCreate 的 5s 延迟 flush 与 onAvailable 的 flush 可能并发，
    //   两个线程都读到同一快照 → 都发送 → 服务器收到重复数据。
    //   本锁只保护 flush 自己，不影响 queuePendingReport 入队（它用 FILE_LOCK）。
    private static final Object FLUSH_LOCK = new Object();
    // 新增2026-11-20 放在 TrackingService 类字段区（FLUSH_LOCK 附近）
    private final java.util.concurrent.atomic.AtomicBoolean flushInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int FLUSH_BATCH_MAX = 30;
    private static final int FLUSH_MAX_CONSECUTIVE_FAILS = 2;
    private static final long FLUSH_CONTINUE_DELAY_MS = 3000L; // ★ 2026-11-20 [B1] 残留批次自我续跑间隔
    // 新增2026-11-20 结束

    // ★ 2026-11-20 新增：服务端过载(502/503/504/429)指数退避 & 离线队列静止防抖合并参数
    //   高优触发源（SMS/UDP/远程指令/运动/低电量/手动/临终上报，均以 force=true 进入）具备找回特权，
    //   可无视退避窗口立即抢占发包；普通周期上报在退避窗口内直接并入离线队列，省电省流量。
    private int  consecutiveServerBusyCount = 0;                       // 连续过载次数（退避阶梯）
    private long serverBackoffUntilMs        = 0L;                     // 退避冷却截止时刻(ms)
    private static final long SERVER_BACKOFF_BASE_MS  = 15 * 60 * 1000L;  // 首次过载冷却 15 分钟
    private static final long SERVER_BACKOFF_MAX_MS   = 60 * 60 * 1000L;  // 最大冷却上限 60 分钟
    private static final long OFFLINE_MERGE_WINDOW_MS = 15 * 60 * 1000L;  // 离线静止合并窗口 15 分钟
    private static final double OFFLINE_MERGE_DIST_M  = 50.0;             // 静止位移判定阈值 50 米

    // v2.0.1 补丁：防抖与排期锁
    private final Object alarmScheduleLock = new Object();
    private static long lastAlarmTriggerTime = 0;
    private static long lastTrimMemoryLogTime = 0;

    // ★ 核心替换：改造线程池，使用有界队列和抛弃最老任务策略，防止弱网卡死导致 OOM
    private final java.util.concurrent.ExecutorService reportExecutor =
            new ThreadPoolExecutor(
                    5, 5, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(20),
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // Fresh user messages use a separate bounded lane so a backlog of durable retries
    // cannot delay interactive sends. The shared transmit lock still serializes UDP writes.
    private final java.util.concurrent.ExecutorService lanChatImmediateExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(100),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LanChat-Immediate");
                        thread.setPriority(Thread.NORM_PRIORITY);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // Single bounded FIFO lane for durable retry batches; it never owns interactive sends.
    private final java.util.concurrent.ExecutorService lanChatSendExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(100),
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // Peer route commits only. They may fan out across open outbox rows and must never
    // occupy the message-ACK lane while a burst of peers is being discovered.
    private final java.util.concurrent.ExecutorService lanChatControlExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(128),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LanChat-Control");
                        thread.setPriority(Thread.NORM_PRIORITY);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // Dedicated message ACK lane: no peer discovery/route persistence jobs are admitted here.
    // This keeps delivery confirmation latency independent from peer-presence bursts.
    private final java.util.concurrent.ExecutorService lanChatAckExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(256),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LanChat-Ack");
                        thread.setPriority(Thread.NORM_PRIORITY);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // Peer presence announcements may perform SQLite work and directed challenges.
    // Bounded FIFO isolation keeps peer presence bursts from delaying authenticated ACK commits.
    private final java.util.concurrent.ExecutorService lanChatPeerExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(128),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LanChat-Peer");
                        thread.setPriority(Thread.NORM_PRIORITY);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // Outbound discovery/challenge sends are isolated from inbound peer admission.
    // A slow UDP probe must not stall processing of the peer.ack that restores a route.
    private final java.util.concurrent.ExecutorService lanChatProbeExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(64),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LanChat-Probe");
                        thread.setPriority(Thread.NORM_PRIORITY);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // Keep durable inbox work independent from location/HTTP reporting and control ACKs.
    private final java.util.concurrent.ExecutorService lanChatIoExecutor =
            new ThreadPoolExecutor(
                    2, 2, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(128),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LanChat-IO");
                        thread.setPriority(Thread.NORM_PRIORITY);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

    // P2P retry/deadline timers must not depend on main-Looper responsiveness.
    private final java.util.concurrent.ScheduledExecutorService lanChatScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "LanChat-Scheduler");
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            });
    private final java.util.concurrent.atomic.AtomicBoolean rescueListenerRetryScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private boolean scheduleLanChatTask(String taskName, Runnable task, long delayMs) {
        if (serviceDestroyed || task == null) return false;
        try {
            lanChatScheduler.schedule(() -> {
                if (serviceDestroyed) return;
                try {
                    task.run();
                } catch (Exception e) {
                    FileLogger.log(this, "CHAT", "LAN 调度任务失败 task=" + taskName
                            + " error=" + e.getClass().getSimpleName());
                }
            }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            if (!serviceDestroyed) {
                FileLogger.log(this, "CHAT", "LAN 调度器拒绝任务 task=" + taskName);
            }
            return false;
        }
    }

    // Serialize service maintenance work away from the UI thread. Multiple system
    // wake sources (JobScheduler/accessibility/alarm) are coalesced by selfHealInFlight.
    private final java.util.concurrent.ExecutorService maintenanceExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(8),
                    runnable -> {
                        Thread thread = new Thread(runnable, "TrackingService-Maintenance");
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

    private void logLifecycle(String event, String detail) {
        FileLogger.log(this, "LIFECYCLE",
                "event=" + event
                        + " pid=" + Process.myPid()
                        + " processSession=" + PROCESS_SESSION_ID
                        + " processStartWallMs=" + PROCESS_START_WALL_TIME_MS
                        + " serviceInstance=" + serviceInstanceId
                        + " serviceCreateElapsedMs=" + serviceCreateTimeMs
                        + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Foreground promotion is the only startup operation kept synchronous:
        // Android requires it immediately after startForegroundService().
        startForegroundNotification();
        try {
            // SQLiteOpenHelper construction is lightweight; doing this before any
            // onStartCommand action avoids a race where an immediate chat action sees null.
            lanChatStore = new LanChatStore(this);
        } catch (Exception e) {
            FileLogger.log(this, "CHAT",
                    "LAN delivery store initialization failed: " + e.getClass().getSimpleName());
        }
        scheduleLanChatInboxProjection();
        serviceCreateTimeMs = SystemClock.elapsedRealtime();
        logLifecycle("SERVICE_CREATE", "foregroundStarted=" + isForegroundStarted);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // IMPORTANT: handler is bound to the main Looper. Posting startup work to it
        // does not make that work asynchronous; permission-return launches previously
        // ran provider queries, network registration, package-manager changes and
        // scheduler IPC on the UI thread. Keep the UI responsive with one bounded,
        // one-shot worker. No periodic startup thread or polling is introduced.
        Thread startupThread = new Thread(() -> {
            long startupStartedAt = SystemClock.elapsedRealtime();
            try {
                FileLogger.log(this, "SERVICE", "🚀 后台启动初始化开始");
                logStartupSelfCheck();

                if (serviceDestroyed) return;

                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceFinder::WakeLock");
                    wakeLock.setReferenceCounted(false);
                }

                WifiManager wifiManager = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeviceFinder::WifiLock");
                    wifiLock.setReferenceCounted(false);
                    multicastLock = wifiManager.createMulticastLock("DeviceFinder::Multicast");
                    multicastLock.setReferenceCounted(false);
                }

                alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    scheduleKeepAliveJob();
                    scheduleHeartbeatAlarm();
                }

                Intent reportIntent = new Intent(this, TrackingService.class);
                reportIntent.setAction("ACTION_ALARM_TRIGGER");
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT;
                // Keep the existing foreground-service PendingIntent behavior on API 26+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    alarmPendingIntent = PendingIntent.getForegroundService(this, 1001, reportIntent, flags);
                } else {
                    alarmPendingIntent = PendingIntent.getService(this, 1001, reportIntent, flags);
                }

                sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
                if (sensorManager != null) {
                    motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
                }

                if (serviceDestroyed) return;
                handleIconVisibility(true);
                triggerRescueListener();

                // SensorManager is kept on the main Looper; these calls are quick and
                // are posted only after the worker has created the sensor references.
                handler.post(() -> {
                    if (!serviceDestroyed) toggleMotionSensor(true);
                });

                // Location registration and ConnectivityManager IPC run off the UI thread.
                // Location callbacks are explicitly delivered on the main Looper.
                requestSingleLocationAndReport();
                if (serviceDestroyed) return;
                registerNetworkCallback();
                startLocationUpdates();
                registerBatteryMonitor();

                if (alarmManager == null) {
                    alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    if (alarmManager != null) {
                        scheduleKeepAliveJob();
                        scheduleHeartbeatAlarm();
                        FileLogger.log(this, "ALARM", "启动阶段补建保活与长心跳闹钟");
                    }
                }

                // File/network queue flushing is delegated to the existing report executor.
                handler.postDelayed(() -> {
                    if (serviceDestroyed) return;
                    try {
                        reportExecutor.execute(() -> {
                            if (!serviceDestroyed) flushPendingReports();
                        });
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        FileLogger.log(this, "REPORT", "启动补发任务队列繁忙，保留离线队列等待下次触发");
                    }
                }, 5000L);

                KeepAliveJobService.scheduleJob(this);
                startWatchdog();
                FileLogger.log(this, "SERVICE", "后台启动初始化完成 elapsedMs="
                        + (SystemClock.elapsedRealtime() - startupStartedAt));
            } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "后台启动初始化异常: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "TrackingService-Startup");
        startupThread.setPriority(Thread.NORM_PRIORITY - 1);
        startupThread.start();
    }

    private void startWatchdog() {
        if (watchdogThread != null && watchdogThread.isAlive()) return;
        watchdogThread = new Thread(() -> {
            while (!serviceDestroyed) {
                try {
                    Thread.sleep(10 * 60 * 1000); // 每 10 分钟检查一次
                    if (serviceDestroyed) break;

                    long now = System.currentTimeMillis();
                    SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                    long lastSuccess = sp.getLong(PREF_LAST_SUCCESS_REPORT_TIME, 0);

                    // ★ [核心优化] 2026-11-04 冷启动时lastSuccess从 SharedPreferences 读取，确保看门狗时刻在线
                    if (lastSuccess > 0 && (now - lastSuccess > 60 * 60 * 1000L)) {
                        FileLogger.log(this, "WATCHDOG", "⚠️ 发现超 1 小时未成功上报，强制触发自救");
                        // ★ [优化] 去掉 handler.post 冗余线程切换，因为 sendReport 内部自带线程池
                        sendReport(true, true);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    FileLogger.log(this, "WATCHDOG", "异常: " + e.getMessage());
                }
            }
        });
        watchdogThread.start();
    }

    private void logStartupSelfCheck() {
    try {
        FileLogger.log(this, "SERVICE", "===== 启动自检 =====");
        logLifecycle("STARTUP_SELF_CHECK", "build=" + BuildInfo.BUILD_VERSION);
        FileLogger.log(this, "SERVICE", "[TS] 版本: " + BuildInfo.BUILD_VERSION
                + " (协议 v" + BuildInfo.PROTOCOL_VERSION + ")");
        String rom = Build.DISPLAY != null ? Build.DISPLAY : "unknown";
            FileLogger.log(this, "SERVICE", "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + " | ROM: " + rom);
            FileLogger.log(this, "SERVICE", "device_id: " + getUniqueDeviceId());

            try { FileLogger.log(this, "SERVICE", "安全补丁: " + Build.VERSION.SECURITY_PATCH); } catch (Exception ignored) {}
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                boolean lowRam = am != null && am.isLowRamDevice();
                FileLogger.log(this, "SERVICE", "低内存设备: " + (lowRam ? "是" : "否"));
            } catch (Exception ignored) {}

            if (Build.VERSION.SDK_INT >= 23) {
                StringBuilder perm = new StringBuilder("权限: ");
                perm.append("LOC=").append(permStr(Manifest.permission.ACCESS_FINE_LOCATION)).append(", ");
                if (Build.VERSION.SDK_INT >= 29)
                    perm.append("BG_LOC=").append(permStr(Manifest.permission.ACCESS_BACKGROUND_LOCATION)).append(", ");
                if (Build.VERSION.SDK_INT >= 33)
                    perm.append("NOTIF=").append(permStr(Manifest.permission.POST_NOTIFICATIONS)).append(", ");
                perm.append("SMS=").append(permStr(Manifest.permission.RECEIVE_SMS)).append(", ");
                if (Build.VERSION.SDK_INT >= 31)
                    perm.append("ALARM=").append(canScheduleExactAlarmsStr()).append(", ");
                perm.append("OVERLAY=").append(Settings.canDrawOverlays(this) ? "✅" : "❌").append(", ");
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                    ComponentName admin = new ComponentName(this, MyDeviceAdminReceiver.class);
                    perm.append("ADMIN=").append(dpm != null && dpm.isAdminActive(admin) ? "✅" : "❌");
                } catch (Exception ignored) { perm.append("ADMIN=❓"); }
                FileLogger.log(this, "SERVICE", perm.toString());
            }

            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = registerReceiver(null, ifilter);
                int level = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
                int scale = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) : 100;
                int plugged = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) : 0;
                int pct = scale > 0 ? level * 100 / scale : -1;
                int tempRaw = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
                String tempStr = tempRaw >= 0 ? (tempRaw / 10.0f) + "°C" : "未知";
                FileLogger.log(this, "SERVICE", "电池: 白名单=" + (ignored ? "✅" : "❌")
                        + " | 充电中=" + (plugged != 0 ? "✅" : "❌")
                        + " | 电量=" + pct + "% | 温度=" + tempStr);
            } catch (Exception ignored) {}

            try {
                boolean gps = locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                boolean net = locationManager != null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                FileLogger.log(this, "SERVICE", "定位 provider: GPS=" + (gps ? "on" : "off")
                        + ", NETWORK=" + (net ? "on" : "设置未启用(可能仍可用)"));
            } catch (Exception ignored) {}

            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                String typeName = (ni != null && ni.isConnected()) ? ni.getTypeName() : "离线";
                String[] wifi = DeviceInfoUtils.getWifiInfo(this);
                String ip = DeviceInfoUtils.getLocalIpAddress();
                FileLogger.log(this, "SERVICE", "网络: " + typeName + " | SSID=" + wifi[0] + " | IP=" + ip);
            } catch (Exception ignored) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    boolean canExact = am != null && am.canScheduleExactAlarms();
                    FileLogger.log(this, "SERVICE", "闹钟权限: 精确闹钟=" + (canExact ? "✅" : "❌"));
                } catch (Exception ignored) {}
            }

            try { FileLogger.log(this, "SERVICE", "老人模式: " + (isElderMode() ? "👴 开启" : "🕶️ 无痕")); } catch (Exception ignored) {}

            try {
                java.io.File qf = new java.io.File(getExternalFilesDir(null), PENDING_REPORTS_FILE);
                if (qf.exists() && qf.length() > 0) {
                    FileLogger.log(this, "SERVICE", "离线队列: " + (qf.length() / 1024) + "KB");
                }
            } catch (Exception ignored) {}

            FileLogger.log(this, "SERVICE", "===================");
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "❌ 自检块异常: " + e.getMessage());
        }
    }

    private String permStr(String perm) {
        try { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ? "✅" : "❌"; }
        catch (Exception e) { return "❓"; }
    }

    private String canScheduleExactAlarmsStr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "N/A";
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            return (am != null && am.canScheduleExactAlarms()) ? "✅" : "❌";
        } catch (Exception e) { return "❓"; }
    }

    private void scheduleKeepAliveJob() {
        if (alarmManager == null) return;
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_KEEP_ALIVE");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pi = PendingIntent.getForegroundService(this, 1002, intent, flags);
        } else {
            pi = PendingIntent.getService(this, 1002, intent, flags);
        }

        // ★ 补丁4：在向系统内核排队下一次保活闹钟前，强制 cancel 掉旧的句柄。
        // 彻底清除 Android 电源管理内核中可能残留的幽灵闹钟，完美绕开 Android 12+ 精确闹钟 Quota 节流。
        try { alarmManager.cancel(pi); } catch (Exception ignored) {}

        // ★ 补丁2：使用全局可调变量 keepAliveAlarmIntervalMs
        long triggerAt = System.currentTimeMillis() + keepAliveAlarmIntervalMs;
        // ★ H2 修复 2026-11-07：15 分钟保活不需要秒级精度，改用 setAndAllowWhileIdle，
        //   避免占用 Android 12+ 精确闹钟配额（72 次/天）。配额优先留给 onTaskRemoved 的 2 秒复活。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (Exception e) {
            FileLogger.log(this, "ALARM", "⚠️ 保活闹钟设置失败: " + e.getMessage());
        }
    }

    private void toggleMotionSensor(boolean enable) {
        if (sensorManager == null || motionSensor == null) return;
        if (enable && enableMotionTrigger) sensorManager.requestTriggerSensor(motionListener, motionSensor);
        else sensorManager.cancelTriggerSensor(motionListener, motionSensor);
    }

    private final TriggerEventListener motionListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            long now = System.currentTimeMillis();
            if (now - lastSensorTriggerTime < 15 * 60 * 1000L) {
                toggleMotionSensor(true);
                return;
            }
            lastSensorTriggerTime = now;
            FileLogger.log(TrackingService.this, "SENSOR", "检测到运动，触发强制上报");
            sendReport(true, true);
            toggleMotionSensor(true);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 记录每次 start 请求；同 PID/Session 下 startId 增长代表同一服务实例收到多次启动。
        String actionForLog = intent != null ? intent.getAction() : "<null>";
        logLifecycle("SERVICE_START", "startId=" + startId
                + " flags=" + flags + " action=" + actionForLog);
        // ★ [新增] 2026-11-05 第一时间归类唤醒源，便于排查是谁救了服务
        logWakeSource(intent);

        if (intent == null || intent.getAction() == null) {
            FileLogger.log(this, "SERVICE", "🔄 STICKY/无Action重启，触发兜底自愈");
            try { startForegroundNotification(); } catch (Exception ignored) {}
            // ★ 强力补丁 2：异步自愈，绝不阻塞，防止 5 秒秒杀
            executeMaintenance("sticky-recovery", () -> {
                if (!serviceDestroyed) {
                    scheduleNextReport();
                    selfHeal();
                }
            });
            return START_STICKY;
        }

        if (intent != null) {
            String action = intent.getAction();
            if (action != null) FileLogger.log(this, "SERVICE", "📥 onStartCommand action=" + action);

            if ("ACTION_RESET_NOTIFICATION".equals(action)) {
                notificationClickCount = 0;
                // ★ H4 修复：点击重置需要刷新通知内容，强制重发
                restartForegroundNotification();
            } else if ("ACTION_MANUAL_REPORT".equals(action) || "ACTION_FORCE_REPORT".equals(action)) {
                sendReport(true, true);
            } else if ("ACTION_UPDATE_SERVER".equals(action) || "ACTION_ADB_UPDATE".equals(action)) {
                String newUrl = intent.getStringExtra("server_url");
                if (newUrl != null && !newUrl.isEmpty()) { saveNewServerUrl(newUrl); sendReport(false, true); }
            } else if ("ACTION_UPDATE_CONFIG_URL".equals(action)) {
                String newConfigUrl = intent.getStringExtra("config_url");
                handleUpdateConfigUrl(newConfigUrl);
            } else if ("ACTION_HEARTBEAT".equals(action)) {
                long actual = System.currentTimeMillis();
                long drift = lastHeartbeatExpectedAt > 0 ? actual - lastHeartbeatExpectedAt : -1;
                FileLogger.log(this, "ALARM", "💓 6小时心跳被唤醒" + (drift >= 0 ? " (偏差 " + drift + "ms)" : ""));
                executeMaintenance("long-heartbeat", () -> {
                    triggerRescueListener();
                    fetchRemoteConfig();
                    scheduleHeartbeatAlarm();
                });
            } else if ("ACTION_PLAY_ALARM".equals(action)) {
                playAlarm();
            } else if ("ACTION_SHOW_MSG".equals(action)) {
                String msg = intent.getStringExtra("message");
                if (msg != null) showFloatingMessage(msg);
            } else if ("ACTION_SHOW_HISTORY".equals(action)) {
                showMessageHistory();
            } else if ("ACTION_CLEAR_HISTORY".equals(action)) {
                clearMessageHistory();
            } else if ("ACTION_ALARM_TRIGGER".equals(action)) {
                // v2.0.1 补丁：2秒内相同来源的重复唤醒，直接掐断
                long now = System.currentTimeMillis();
                if (now - lastAlarmTriggerTime < 2000) {
                    FileLogger.logD("ALARM", "⏭ 拦截 2 秒内的重复定时上报");
                    return START_STICKY;
                }
                lastAlarmTriggerTime = now;

                long actual = System.currentTimeMillis();
                long drift = lastAlarmExpectedAt > 0 ? actual - lastAlarmExpectedAt : -1;
                FileLogger.log(this, "ALARM", "⏰ 定时上报闹钟被唤醒" + (drift >= 0 ? " (偏差 " + drift + "ms)" : ""));
                if (enableWifiLock && wifiLock != null && !wifiLock.isHeld()) {
                    try { wifiLock.acquire(); } catch (Exception e) {
                        FileLogger.log(this, "REPORT", "⚠️ WifiLock acquire 失败: " + e.getMessage());
                    }
                }
                // 周期闹钟属于普通上报，遵守服务端退避；手动/找回触发仍使用 force=true。
                sendReport(true, false);
                handler.postDelayed(() -> { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }, 15000);
            } else if ("ACTION_SECRET_UNLOCK".equals(action)) {
                long now = System.currentTimeMillis();
                if (now - lastNotificationClickTime > 3000) { notificationClickCount = 1; } else { notificationClickCount++; }
                lastNotificationClickTime = now;

                // ★ H4 修复：第 2 次点击刷新提示、第 3 次点击进入并重置，均需强制重发
                if (notificationClickCount == 2) { restartForegroundNotification(); }
                else if (notificationClickCount >= 3) {
                    notificationClickCount = 0;
                    restartForegroundNotification();
                    if (!isChatLocked()) {
                        Intent chatIntent = new Intent(this, ChatActivity.class);
                        chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        try { startActivity(chatIntent); } catch (Exception ignored) {}
                    } else {
                        android.widget.Toast.makeText(this, "服务已锁定，无法进入", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            } else if ("ACTION_SEND_UDP_CHAT".equals(action)) {
                String msg = intent.getStringExtra("message");
                if (msg != null) sendLocalUdpMessage(msg);
            } else if ("ACTION_KEEP_ALIVE".equals(action)) {
                long uptimeMin = (SystemClock.elapsedRealtime() - serviceCreateTimeMs) / 60000;
                FileLogger.log(this, "ALARM", "💓 15分钟保活心跳被唤醒 (已运行 " + uptimeMin + " 分钟)");
                try {
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    long maxMb = rt.maxMemory() / (1024 * 1024);
                    FileLogger.log(this, "MEMORY", "💾 内存占用: " + usedMb + "MB / " + maxMb + "MB");
                } catch (Exception ignored) {}

                try {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        StatFs stat = new StatFs(dir.getAbsolutePath());
                        long availMb = stat.getAvailableBytes() / (1024 * 1024);
                        long totalMb = stat.getTotalBytes() / (1024 * 1024);
                        FileLogger.log(this, "STORAGE", "💾 可用空间: " + availMb + "MB / " + totalMb + "MB");
                    }
                } catch (Exception ignored) {}

                try {
                    long rx = TrafficStats.getUidRxBytes(Process.myUid());
                    long tx = TrafficStats.getUidTxBytes(Process.myUid());
                    if (rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED) {
                        FileLogger.log(this, "TRAFFIC", "📶 累计流量: RX=" + (rx / 1024) + "KB, TX=" + (tx / 1024) + "KB");
                    }
                } catch (Exception ignored) {}

                executeMaintenance("keep-alive", () -> {
                    selfHeal();
                    scheduleKeepAliveJob();
                });

            // ★ [核心优化] 2026-11-04 新增独立分支处理 JobScheduler 唤醒，避免和 Alarm 冲突
            } else if ("ACTION_JOB_WAKE_UP".equals(action)) {
                long uptimeMin = (SystemClock.elapsedRealtime() - serviceCreateTimeMs) / 60000;
                FileLogger.log(this, "JOB", "💓 JobScheduler 保底唤醒 (已运行 " + uptimeMin + " 分钟)");
                // 仅触发后台自愈检查，绝不重新 schedule 闹钟
                scheduleSelfHeal("job");

            // ★ [新增] 2026-11-05 无障碍服务唤醒入口
            } else if ("ACTION_WAKE_BY_ACCESSIBILITY".equals(action)) {
                long uptimeMin = (SystemClock.elapsedRealtime() - serviceCreateTimeMs) / 60000;
                FileLogger.log(this, "ACCESSIBILITY", "♿ 无障碍唤醒，执行自愈 (已运行 " + uptimeMin + " 分钟)");
                scheduleSelfHeal("accessibility");

            // ★ [新增] 2026-11-05 组件自救唤醒入口（预留，配套 AlarmManager 兜底使用）
            } else if ("ACTION_WAKE_BY_COMPONENT_SELF_HEAL".equals(action)) {
                FileLogger.log(this, "SERVICE", "🔄 组件自救唤醒，执行后台自愈");
                scheduleSelfHeal("component");

            } else if ("ACTION_LOCK_CHAT".equals(action)) {
                setChatLocked(true);
            } else if ("ACTION_UNLOCK_CHAT".equals(action)) {
                setChatLocked(false);
            } else if ("ACTION_UPLOAD_LOG".equals(action)) {
                String tempUrl = intent.getStringExtra("temp_url");
                boolean clearAfter = intent.getBooleanExtra("clear_after", false);
                uploadLog(tempUrl, clearAfter);
            } else if ("ACTION_UPDATE_RUNTIME_CONFIG".equals(action)) {
                StringBuilder changed = new StringBuilder();
                String intervalStr = intent.getStringExtra("report_interval_seconds");
                if (intervalStr != null) {
                    try {
                        long seconds = Long.parseLong(intervalStr);
                        if (seconds < 60L || seconds > 7L * 24L * 60L * 60L) throw new IllegalArgumentException("间隔必须为 60 秒至 7 天");
                        long newInterval = TimeUnit.SECONDS.toMillis(seconds);
                        reportIntervalMs = newInterval;
                        changed.append("interval=").append(intervalStr).append("s, ");
                    } catch (Exception e) { FileLogger.log(this, "CONFIG", "⚠️ interval 参数非法: " + intervalStr); }
                }
                String accuracy = intent.getStringExtra("accuracy_mode");
                if (accuracy != null && (accuracy.equals("coarse") || accuracy.equals("fine"))) {
                    accuracyMode = accuracy;
                    changed.append("accuracy=").append(accuracy).append(", ");
                    handler.post(() -> startLocationUpdates());
                }
                String motionStr = intent.getStringExtra("enable_motion_trigger");
                if (motionStr != null) {
                    enableMotionTrigger = Boolean.parseBoolean(motionStr);
                    toggleMotionSensor(enableMotionTrigger);
                    changed.append("motion=").append(enableMotionTrigger).append(", ");
                }
                String wifiLockStr = intent.getStringExtra("enable_wifi_lock");
                if (wifiLockStr != null) {
                    enableWifiLock = Boolean.parseBoolean(wifiLockStr);
                    changed.append("wifiLock=").append(enableWifiLock);
                }
                if (changed.length() > 0) FileLogger.log(this, "CONFIG", "📝 运行时配置已更新: " + changed);
                else FileLogger.log(this, "CONFIG", "⚠️ UPDATE_RUNTIME_CONFIG 未提供任何参数");
            } else if ("ACTION_LOCATE_ONCE".equals(action)) {
                FileLogger.log(this, "LOCATION", "📍 收到手动定位指令");
                requestSingleLocationAndReport();
            } else if ("ACTION_SCAN_WIFI".equals(action)) {
                new Thread(() -> {
                    try {
                        // ★ [修复] 2026-11-04 补全 AI 丢失的泛型 List<JSONObject>
                        List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this);
                        FileLogger.log(TrackingService.this, "WIFI", "📡 手动扫描完成: " + nearby.size() + " 个热点");
                        for (JSONObject obj : nearby) {
                            FileLogger.log(TrackingService.this, "WIFI",
                                    "  - " + obj.optString("ssid") + " (" + obj.optInt("rssi") + " dBm)");
                        }
                    } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "WIFI", "❌ 手动扫描失败: " + e.getMessage());
                    }
                }).start();
            } else if ("ACTION_RESTART_UDP".equals(action)) {
                FileLogger.log(this, "UDP", "🔄 收到手动重启监听指令");
                if (rescueSocketRef != null) { try { rescueSocketRef.close(); } catch (Exception ignored) {} }
            } else if ("ACTION_TEST_LOW_BATTERY".equals(action)) {
                FileLogger.log(this, "BATTERY", "🧪 模拟低电量告警 (15%)");
                sendReport(true, true);
                sendLowBatterySms(15);
            } else if ("ACTION_TOGGLE_ELDER_MODE".equals(action)) {
                String val = intent.getStringExtra("elder_mode");
                boolean target;
                if (val != null) target = "1".equals(val) || "true".equalsIgnoreCase(val);
                else target = !isElderMode();
                setElderMode(target);
                handler.post(() -> android.widget.Toast.makeText(this,
                        target ? "👴 老人模式已开启（脱敏通知）" : "🕶️ 无痕模式已开启（仅悬浮窗）",
                        android.widget.Toast.LENGTH_LONG).show());
            } else if ("ACTION_SELF_CHECK".equals(action)) {
                FileLogger.log(this, "SELF_CHECK", "📋 收到自检指令");
                showSelfCheckNotification();
            }
        }

        try { startForegroundNotification(); } catch (Exception ignored) {}
        return START_STICKY;
    }

    // ==================== 唤醒源日志归类 (2026-11-05 新增) ====================
    private void logWakeSource(Intent intent) {
        String source = "未知原因唤醒/冷启动";
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case "ACTION_WAKE_BY_ACCESSIBILITY":
                        source = "♿ [无障碍服务] 强力注入拉活";
                        break;
                    case "ACTION_WAKE_BY_COMPONENT_SELF_HEAL":
                        source = "🔄 [组件自救] 侧滑销毁瞬间就地复活";
                        break;
                    case "ACTION_JOB_WAKE_UP":
                        source = "⏰ [JobScheduler] 定期保底拉活";
                        break;
                    case "ACTION_KEEP_ALIVE":
                        source = "💓 [Alarm保活闹钟] 15分钟心跳复活";
                        break;
                    case "ACTION_ALARM_TRIGGER":
                        source = "⏰ [上报闹钟] 定时任务主动唤醒";
                        break;
                    case "ACTION_HEARTBEAT":
                        source = "💓 [长心跳] 6小时保底业务唤醒";
                        break;
                    default:
                        source = "📥 业务动作 Action: " + action;
                        break;
                }
            }
        }
        FileLogger.log(this, "WAKE_SOURCE", "🚩 当前服务启动源: " + source);
    }

    // Network callbacks can fire several times for one transition (availability,
    // capabilities and link-properties). Keep only one queued refresh per category;
    // if another event arrives while it runs, perform one more fresh-snapshot pass.
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            coalescedMaintenanceStates = new java.util.concurrent.ConcurrentHashMap<>();

    private void executeCoalescedMaintenance(String taskName, Runnable task) {
        java.util.concurrent.atomic.AtomicInteger state =
                coalescedMaintenanceStates.computeIfAbsent(taskName,
                        key -> new java.util.concurrent.atomic.AtomicInteger(0));
        if (!state.compareAndSet(0, 1)) {
            state.compareAndSet(1, 2); // mark one rerun; further events collapse into it
            return;
        }
        Runnable worker = () -> {
            boolean rerun;
            do {
                if (!serviceDestroyed) {
                    try {
                        task.run();
                    } catch (Exception e) {
                        FileLogger.log(this, "SERVICE", "⚠️ 合并网络维护任务失败 task="
                                + taskName + " error=" + e.getClass().getSimpleName());
                    }
                }
                int completedState = state.getAndSet(0);
                rerun = completedState == 2 && !serviceDestroyed;
                if (rerun && !state.compareAndSet(0, 1)) rerun = false;
            } while (rerun);
        };
        try {
            maintenanceExecutor.execute(worker);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            state.set(0);
            if (!serviceDestroyed) {
                FileLogger.log(this, "SERVICE", "⚠️ 网络维护队列繁忙，保留后续回调重试: " + taskName);
            }
        }
    }

    private void executeMaintenance(String taskName, Runnable task) {
        if (serviceDestroyed || task == null) return;
        // These tasks read current system state, so callback bursts are safely coalesced.
        if ("peer-route-refresh".equals(taskName)
                || "wifi-lan-refresh".equals(taskName)
                || "network-refresh".equals(taskName)) {
            executeCoalescedMaintenance(taskName, task);
            return;
        }
        try {
            maintenanceExecutor.execute(() -> {
                if (serviceDestroyed) return;
                long startedAt = SystemClock.elapsedRealtime();
                try {
                    task.run();
                } catch (Exception e) {
                    FileLogger.log(this, "SERVICE", "⚠️ 后台维护任务失败 task=" + taskName
                            + " error=" + e.getClass().getSimpleName());
                } finally {
                    long elapsed = SystemClock.elapsedRealtime() - startedAt;
                    if (elapsed >= 1000L) {
                        FileLogger.logD("SERVICE", "maintenance task=" + taskName
                                + " elapsedMs=" + elapsed);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            FileLogger.log(this, "SERVICE", "⚠️ 后台维护队列繁忙，合并本次任务: " + taskName);
        }
    }

    private void scheduleSelfHeal(String source) {
        executeMaintenance("self-heal:" + source, this::selfHeal);
    }

    private void selfHeal() {
        // 多路唤醒并发到达时合并正在执行的自愈检查；不拦截后续独立唤醒。
        if (!selfHealInFlight.compareAndSet(false, true)) {
            FileLogger.log(this, "SERVICE", "⏭ 自愈检查已在执行，合并本次并发请求");
            return;
        }
        try {
            try {
                try { startForegroundNotification(); } catch (Exception ignored) {}

                boolean listening;
                synchronized (rescueLock) {
                    listening = isListeningForRescue;
                }
                if (!listening && !serviceDestroyed) {
                    FileLogger.log(this, "SERVICE", "⚠️ 自愈: UDP 监听已死，重新拉起");
                    triggerRescueListener();
                }

                try {
                    List<String> providers = locationManager.getProviders(true);
                    if (providers == null || providers.isEmpty()) {
                        FileLogger.log(this, "SERVICE", "⚠️ 自愈: 无可用 provider，重新注册定位监听");
                        startLocationUpdates();
                    }
                } catch (Exception e) {
                    FileLogger.log(this, "SERVICE", "⚠️ 自愈: 检查定位失败，尝试重注册: " + e.getMessage());
                    try { startLocationUpdates(); } catch (Exception ignored) {}
                }

                if ((networkCallback == null || wifiLanNetworkCallback == null)
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    FileLogger.log(this, "SERVICE", "⚠️ 自愈: networkCallback 为空，重新注册");
                    registerNetworkCallback();
                }

                // ★ [核心优化] 2026-11-04 从 SharedPreferences 读取持久化的成功上报时间
                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                long lastSuccess = sp.getLong(PREF_LAST_SUCCESS_REPORT_TIME, 0);
                if (lastSuccess > 0 && System.currentTimeMillis() - lastSuccess > 30 * 60 * 1000L) {
                    long gapMin = (System.currentTimeMillis() - lastSuccess) / 60000;
                    FileLogger.log(this, "SERVICE", "⚠️ 自愈: 距上次成功上报 " + gapMin + " 分钟，强制触发一次");
                    sendReport(true, false);
                }
            } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "❌ 自愈心跳异常: " + e.getMessage());
            }
        } finally {
            selfHealInFlight.set(false);
        }
    }

    /**
     * ★ 2026-11-07 新增：发送显式广播通知聊天界面刷新。
     * 显式指定包名，彻底绕过 Android 14+/MIUI 对隐式广播的拦截限制。
     */
    private void notifyChatUi() {
        if (ChatActivity.isForeground) {
            Intent intent = new Intent("UPDATE_CHAT_UI");
            intent.setPackage(getPackageName()); // 核心：限定只发给自己
            sendBroadcast(intent);
        }
    }

    private boolean isChatLocked() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getBoolean("chat_locked", false);
    }

    private void setChatLocked(boolean locked) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putBoolean("chat_locked", locked).apply();
    }

    private boolean isCloudLogEnabled() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getBoolean("enable_cloud_log", false);
    }

    private boolean isElderMode() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getBoolean(PREF_ELDER_MODE, false);
    }

    private void setElderMode(boolean enabled) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putBoolean(PREF_ELDER_MODE, enabled).commit();
        FileLogger.log(this, "CONFIG", enabled ? "👴 老人模式已开启（脱敏通知）" : "🕶️ 无痕模式已开启（仅悬浮窗）");
    }

    private void saveMessageToHistory(String message) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("msg_history", "");
        String timeStr = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
        String newRecord = timeStr + " | " + message;
        String updatedHistory = newRecord + "\n" + history;
        String[] lines = updatedHistory.split("\n");
        if (lines.length > 100) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) sb.append(lines[i]).append("\n");
            updatedHistory = sb.toString();
        }
        sp.edit().putString("msg_history", updatedHistory).apply();
    }

    private void saveChatToHistory(String message) {
        // Serialize legacy/HTTP history appends with the asynchronous LAN inbox projection.
        synchronized (lanChatReceiveLock) {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            String history = sp.getString("chat_history", "");
            String timeStr = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String newRecord = timeStr + " | " + message;
            String updatedHistory = history.isEmpty() ? newRecord : history + "\n" + newRecord;
            String[] lines = updatedHistory.split("\n");
            if (lines.length > 100) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 100; i < lines.length; i++) sb.append(lines[i]).append("\n");
                updatedHistory = sb.toString().trim();
            }
            sp.edit().putString("chat_history", updatedHistory).apply();
        }
    }

    private void syncMessageToServer(String message) {
        new Thread(() -> {
            try {
                String timeStr = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
                JSONObject json = new JSONObject();
                json.put("token", AUTH_TOKEN);
                json.put("device_id", getUniqueDeviceId());
                json.put("message", message);
                json.put("time_str", timeStr);
                json.put("timestamp", System.currentTimeMillis());

                String msgUrl = buildApiUrl(getCurrentServerUrl(), "message");
                if (msgUrl == null) { Log.e("TrackingService", "syncMessageToServer: server_url 非法，跳过"); return; }
                URL url = new URL(msgUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Connection", "close");

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void clearMessageHistory() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("msg_history", "").apply();

        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                TextView textView = new TextView(this);
                textView.setText("🧹 历史留言已清空");
                textView.setTextSize(20f);
                textView.setTextColor(Color.WHITE);
                textView.setBackgroundColor(Color.parseColor("#CC000000"));
                textView.setPadding(40, 40, 40, 40);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.CENTER;
                wm.addView(textView, params);
                handler.postDelayed(() -> { try { wm.removeView(textView); } catch (Exception ignored) {} }, 3000);
            } catch (Exception ignored) {}
        });
    }

    private void showMessageHistory() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("msg_history", "还没有留言哦~");

        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenW = dm.widthPixels, screenH = dm.heightPixels;

                android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(Color.parseColor("#F2FFFFFF"));
                shape.setCornerRadius(50);
                layout.setBackground(shape);
                layout.setPadding(60, 60, 60, 60);

                TextView titleView = new TextView(this);
                titleView.setText("📜 家庭留言板");
                titleView.setTextSize(22f);
                titleView.setTextColor(Color.parseColor("#333333"));
                titleView.setTypeface(null, android.graphics.Typeface.BOLD);
                titleView.setGravity(Gravity.START);
                android.widget.LinearLayout.LayoutParams titleParams = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                titleParams.bottomMargin = 30;
                titleView.setLayoutParams(titleParams);
                layout.addView(titleView);

                ScrollView scrollView = new ScrollView(this);
                TextView textView = new TextView(this);
                textView.setText(history);
                textView.setTextSize(18f);
                textView.setTextColor(Color.parseColor("#555555"));
                textView.setLineSpacing(20f, 1f);
                textView.setGravity(Gravity.START);
                scrollView.addView(textView);
                layout.addView(scrollView);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                int bubbleW = (int) (screenW * 0.85f);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        bubbleW, WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 80;
                params.y = 450;
                wm.addView(layout, params);

                scrollView.post(() -> {
                    int contentH = textView.getMeasuredHeight();
                    int maxH = (int) (screenH * 0.55f);
                    int finalH = Math.min(contentH + 20, maxH);
                    android.view.ViewGroup.LayoutParams lp2 = scrollView.getLayoutParams();
                    lp2.height = finalH;
                    scrollView.setLayoutParams(lp2);
                });

                // ★ [修复] 2026-11-04 按照你的要求，恢复长按复制功能。
                // 注意：极少数老旧机型可能因焦点问题在特殊操作下闪退，若以后发现异常，请改回 false
                textView.setTextIsSelectable(true);
                textView.setFocusable(true);
                textView.setFocusableInTouchMode(true);

                final android.widget.LinearLayout layoutRef = layout;
                attachDragAndRemember(wm, layout, params, "bubble_pos",
                        () -> { try { wm.removeView(layoutRef); } catch (Exception ignored) {} }, null);
            } catch (Exception e) {
                FileLogger.log(this, "UI", "❌ showMessageHistory 失败: " + e.getMessage());
            }
        });
    }

    private void showFloatingMessage(String message) {
        saveMessageToHistory(message);
        syncMessageToServer(message);
        if (isElderMode()) showElderMessageNotification();

        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                for (android.view.View oldBubble : activeBubbles) {
                    try { wm.removeView(oldBubble); } catch (Exception ignored) {}
                }
                activeBubbles.clear();

                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenW = dm.widthPixels;
                final int msgMaxW = (int) (screenW * 0.85f);

                android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                layout.setGravity(Gravity.START);

                TextView textView = new TextView(this);
                textView.setText(message);
                textView.setTextSize(20f);
                textView.setTypeface(null, android.graphics.Typeface.NORMAL);
                textView.setTextColor(Color.WHITE);
                textView.setShadowLayer(8f, 3f, 3f, Color.parseColor("#99000000"));
                textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                textView.setPadding(dpToPx(10), dpToPx(15), dpToPx(20), dpToPx(15));
                textView.setMaxWidth(msgMaxW);
                textView.setLineSpacing(15f, 1f);
                textView.setHorizontallyScrolling(false);
                textView.setSingleLine(false);

                android.widget.LinearLayout.LayoutParams tvParams = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                textView.setLayoutParams(tvParams);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    textView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                    textView.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
                }
                layout.addView(textView);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        msgMaxW, WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 20;
                params.y = 450;

                wm.addView(layout, params);
                activeBubbles.add(layout);

                layout.post(() -> {
                    int textNaturalW = measureTextNaturalWidth(textView, message);
                    int finalW = Math.min(textNaturalW, msgMaxW);
                    finalW = Math.max(finalW, dpToPx(60));
                    if (finalW != params.width) {
                        params.width = finalW;
                        try { wm.updateViewLayout(layout, params); } catch (Exception ignored) {}
                    }
                });

                final android.widget.LinearLayout layoutRef = layout;
                attachDragAndRemember(wm, layout, params, "bubble_pos",
                        () -> { try { wm.removeView(layoutRef); } catch (Exception ignored) {} activeBubbles.remove(layoutRef); },
                        () -> { try { wm.removeView(layoutRef); } catch (Exception ignored) {} activeBubbles.remove(layoutRef); showMessageHistory(); });

                handler.postDelayed(() -> {
                    try { wm.removeView(layoutRef); } catch (Exception ignored) {}
                    activeBubbles.remove(layoutRef);
                }, 24 * 60 * 60 * 1000L);
            } catch (Exception e) {
                FileLogger.log(this, "UI", "❌ showFloatingMessage 失败: " + e.getMessage());
            }
        });
    }

    private void ensureElderMessageChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID_MESSAGE) != null) return;

            NotificationChannel ch = new NotificationChannel(CHANNEL_ID_MESSAGE, "家庭留言（老人模式）", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("收到新留言时提醒（内容脱敏，点击查看）");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 400, 200, 400});

            android.net.Uri soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            if (soundUri != null) {
                ch.setSound(soundUri, new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            }
            nm.createNotificationChannel(ch);
        } catch (Exception e) {
            FileLogger.log(this, "NOTIFY", "⚠️ 创建通知渠道失败: " + e.getMessage());
        }
    }

    private void showElderMessageNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                FileLogger.log(this, "NOTIFY", "⚠️ 无 POST_NOTIFICATIONS 权限，跳过通知");
                return;
            }
            ensureElderMessageChannel();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent historyIntent = new Intent(this, TrackingService.class);
            historyIntent.setAction("ACTION_SHOW_HISTORY");
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getService(this, 3001, historyIntent, piFlags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder = new Notification.Builder(this, CHANNEL_ID_MESSAGE);
            else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_HIGH);
                builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
            }

            Notification n = builder.setContentTitle("📩 家庭留言").setContentText("有新留言，点击查看")
                    .setSmallIcon(android.R.drawable.ic_dialog_email).setContentIntent(pi)
                    .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
            nm.notify(NOTIF_ID_MESSAGE, n);
            FileLogger.log(this, "NOTIFY", "📩 已发送脱敏通知（老人模式）");
        } catch (Exception e) {
            FileLogger.log(this, "NOTIFY", "❌ 发送通知失败: " + e.getMessage());
        }
    }

    private void ensureSelfCheckChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID_SELF_CHECK) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID_SELF_CHECK, "设备自检", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("拨号 *#*#4321#*#* 触发的设备自检结果");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {}
    }

    private void showSelfCheckNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                FileLogger.log(this, "SELF_CHECK", "⚠️ 无 POST_NOTIFICATIONS 权限，无法发送自检通知");
                return;
            }
            ensureSelfCheckChannel();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            StringBuilder sb = new StringBuilder();
            sb.append("[TS] 版本：").append(BuildInfo.BUILD_VERSION)
              .append("（协议 v").append(BuildInfo.PROTOCOL_VERSION).append("）\n");
            sb.append("老人模式：").append(isElderMode() ? "👴 已开启" : "🕶️ 无痕").append("\n");
            try {
                boolean loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean bgLoc = Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
                sb.append("定位权限：").append(loc ? "✅ 前台" : "❌ 前台").append(" / ").append(bgLoc ? "✅ 后台" : "❌ 后台").append("\n");
            } catch (Exception ignored) {}
            try {
                boolean notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                sb.append("通知权限：").append(notif ? "✅ 已授予" : "❌ 未授予").append("\n");
            } catch (Exception ignored) {}
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID_MESSAGE);
                    sb.append("留言通知渠道：").append(ch != null ? "✅ 已创建" : "⚠️ 未创建（老人模式首条留言时自动创建）").append("\n");
                }
            } catch (Exception ignored) {}
            try {
                boolean overlay = Settings.canDrawOverlays(this);
                sb.append("悬浮窗权限：").append(overlay ? "✅" : "❌").append("\n");
            } catch (Exception ignored) {}
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                String typeName = (ni != null && ni.isConnected()) ? ni.getTypeName() : "离线";
                String[] wifi = DeviceInfoUtils.getWifiInfo(this);
                sb.append("网络：").append(typeName).append("（").append(wifi[0]).append("）\n");
            } catch (Exception ignored) {}
            try {
                int[] battery = DeviceInfoUtils.getBatteryInfo(this);
                sb.append("电量：").append(battery[0]).append("%（").append(battery[1] == 1 ? "充电中" : "未充电").append("）\n");
            } catch (Exception ignored) {}
            sb.append("上报间隔：").append(reportIntervalMs / 60000).append(" 分钟\n");

            // ★ [核心优化] 2026-11-04 从 SharedPreferences 读取持久化时间
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            long lastSuccess = sp.getLong(PREF_LAST_SUCCESS_REPORT_TIME, 0);
            if (lastSuccess > 0) {
                long gapMin = (System.currentTimeMillis() - lastSuccess) / 60000;
                sb.append("上次成功上报：").append(gapMin).append(" 分钟前");
            } else sb.append("上次成功上报：本次启动后暂无");

            Intent piIntent = new Intent(this, TrackingService.class);
            piIntent.setAction("ACTION_RESET_NOTIFICATION");
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getService(this, 3002, piIntent, piFlags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder = new Notification.Builder(this, CHANNEL_ID_SELF_CHECK);
            else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_DEFAULT);
            }

            Notification n = builder.setContentTitle("📋 设备自检")
                    .setContentText("老人模式：" + (isElderMode() ? "已开启" : "无痕"))
                    .setStyle(new Notification.BigTextStyle().bigText(sb.toString()))
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentIntent(pi).setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build();
            nm.notify(NOTIF_ID_SELF_CHECK, n);
            FileLogger.log(this, "SELF_CHECK", "📋 自检通知已发送");
        } catch (Exception e) {
            FileLogger.log(this, "SELF_CHECK", "❌ 自检通知发送失败: " + e.getMessage());
        }
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int getNavBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int clampX(int x, int viewW, int screenW) { return Math.max(0, Math.min(x, screenW - viewW)); }
    private int clampY(int y, int viewH, int screenH, int statusH, int navH) {
        int minY = statusH; int maxY = screenH - navH - viewH;
        if (maxY < minY) maxY = minY;
        return Math.max(minY, Math.min(y, maxY));
    }
    private int dpToPx(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }

    private int measureTextNaturalWidth(TextView tv, String text) {
        if (text == null || text.isEmpty()) return 0;
        // 强力加固：不依赖尚未完成 Measure 的 View 底层属性，直接通过 Paint 在 JVM 纯内存计算
        android.text.TextPaint paint = tv.getPaint();
        int maxW = 0;
        for (String line : text.split("\n")) {
            maxW = Math.max(maxW, (int) Math.ceil(paint.measureText(line)));
        }
        // ★ 采用 dpToPx 静态保底估算左右边距（30dp），彻底避开系统 getPadding() 未初始化时的底层崩溃深坑
        return maxW + dpToPx(30);
    }

    private void attachDragAndRemember(WindowManager wm, android.view.View view,
                                       WindowManager.LayoutParams lp, String prefKey,
                                       Runnable onSingleTap, Runnable onDoubleTap) {
        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int screenW = dm.widthPixels, screenH = dm.heightPixels;
        final int statusH = getStatusBarHeight(), navH = getNavBarHeight();
        final String posKey = prefKey + "_" + screenW + "x" + screenH;

        final float[] downRaw = new float[2];
        final int[] startPos = new int[2];
        final boolean[] dragging = {false};

        final Runnable[] pendingSingleTap = new Runnable[1];
        final Handler gestureHandler = new Handler(Looper.getMainLooper());

        android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(android.view.MotionEvent e) {
                        dragging[0] = true;
                        if (pendingSingleTap[0] != null) gestureHandler.removeCallbacks(pendingSingleTap[0]);
                    }
                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        if (!dragging[0] && onSingleTap != null) {
                            if (pendingSingleTap[0] != null) gestureHandler.removeCallbacks(pendingSingleTap[0]);
                            pendingSingleTap[0] = () -> { if (onSingleTap != null) onSingleTap.run(); };
                            gestureHandler.postDelayed(pendingSingleTap[0], 300);
                        }
                        return true;
                    }
                    @Override
                    public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (pendingSingleTap[0] != null) { gestureHandler.removeCallbacks(pendingSingleTap[0]); pendingSingleTap[0] = null; }
                        if (onDoubleTap != null) onDoubleTap.run();
                        return true;
                    }
                });

        view.setOnTouchListener((v, event) -> {
            gd.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX(); downRaw[1] = event.getRawY();
                    if (lp.gravity != (Gravity.TOP | Gravity.START)) {
                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.x = loc[0]; lp.y = loc[1];
                        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
                    }
                    startPos[0] = lp.x; startPos[1] = lp.y;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (dragging[0]) {
                        int nx = startPos[0] + (int) (event.getRawX() - downRaw[0]);
                        int ny = startPos[1] + (int) (event.getRawY() - downRaw[1]);
                        lp.x = clampX(nx, v.getWidth(), screenW);
                        lp.y = clampY(ny, v.getHeight(), screenH, statusH, navH);
                        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) {
                        dragging[0] = false;
                        getSharedPreferences(PREF_BUBBLE, MODE_PRIVATE).edit()
                                .putInt(posKey + "_x", lp.x).putInt(posKey + "_y", lp.y).apply();
                    }
                    return true;
            }
            return false;
        });

        view.post(() -> {
            SharedPreferences sp = getSharedPreferences(PREF_BUBBLE, MODE_PRIVATE);
            int sx = sp.getInt(posKey + "_x", Integer.MIN_VALUE);
            int sy = sp.getInt(posKey + "_y", Integer.MIN_VALUE);
            if (sx != Integer.MIN_VALUE && sy != Integer.MIN_VALUE) {
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = clampX(sx, view.getWidth(), screenW);
                lp.y = clampY(sy, view.getHeight(), screenH, statusH, navH);
                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
            }
        });
    }

    private void registerBatteryMonitor() {
        batteryMonitor = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
                int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                int plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                int percent = (scale > 0) ? (level * 100 / scale) : -1;
                boolean charging = plugged != 0;
                // ★ 2026-11-07 新增：瞬时刷新全局电量快照，供 SmsReceiver 免锁读取
                if (percent >= 0) GLOBAL_BATTERY_LEVEL = percent;

                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                long lastSmsTime = sp.getLong("last_low_battery_sms_time", 0);
                long now = System.currentTimeMillis();

                if (percent >= 0 && percent <= 15 && !charging) {
                    if (now - lastSmsTime > 6 * 60 * 60 * 1000L) {
                        FileLogger.log(TrackingService.this, "BATTERY", "🪫 触发低电量告警 (" + percent + "%)");
                        sp.edit().putLong("last_low_battery_sms_time", now).apply();
                        sendReport(true, true);
                        sendLowBatterySms(percent);
                    }
                } else if (charging || percent > 20) {
                    if (lastSmsTime != 0) sp.edit().putLong("last_low_battery_sms_time", 0).apply();
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(batteryMonitor, filter);
        } catch (Exception e) {
            FileLogger.log(this, "BATTERY", "❌ 注册电池监听失败: " + e.getMessage());
        }
    }

    private void sendLowBatterySms(int percent) {
        try {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            String myPhoneNumber = sp.getString("emergency_sms_number", "");
            if (myPhoneNumber == null || myPhoneNumber.trim().isEmpty()) {
                FileLogger.log(this, "BATTERY", "⚠️ 低电量告警但未设置紧急号码");
                return;
            }
            android.telephony.SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getSystemService(android.telephony.SmsManager.class) : android.telephony.SmsManager.getDefault();
            if (smsManager != null) {
                smsManager.sendTextMessage(myPhoneNumber, null, "【紧急警告】备用机电量 " + percent + "% 即将关机，请立刻查看服务器最后定位！", null, null);
                FileLogger.log(this, "BATTERY", "📩 低电量短信已发送 → " + myPhoneNumber);
            }
        } catch (Exception e) {
            FileLogger.log(this, "BATTERY", "❌ 低电量短信发送失败: " + e.getMessage());
        }
    }

    private void playAlarm() {
        try {
            android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;

            int originalMode = audioManager.getRingerMode();
            FileLogger.log(this, "ALARM", "🔊 开始播放警报，当前 RingerMode=" + originalMode);

            if (originalMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                try {
                    audioManager.setRingerMode(android.media.AudioManager.RINGER_MODE_NORMAL);
                    FileLogger.log(this, "ALARM", "⚠️ 已临时切到正常模式（原 mode=" + originalMode + "）");
                } catch (SecurityException e) {
                    FileLogger.log(this, "ALARM", "❌ 无法切 RingerMode（缺 MODIFY_AUDIO_SETTINGS）: " + e.getMessage());
                }
            }

            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVolume, 0);

            android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(
                    getApplicationContext(),
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM));

            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                }
                ringtone.play();

                try {
                    android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        long[] pattern = {0, 500, 200, 500, 200, 500, 200, 500, 200, 500};
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
                        } else {
                            vibrator.vibrate(pattern, -1);
                        }
                    }
                } catch (Exception ignored) {}

                FileLogger.log(this, "ALARM", "🔊 警报已播放 + 震动");

                final int finalOriginalMode = originalMode;
                handler.postDelayed(() -> {
                    try {
                        if (ringtone.isPlaying()) ringtone.stop();
                        if (finalOriginalMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                            audioManager.setRingerMode(finalOriginalMode);
                            FileLogger.log(this, "ALARM", "🔇 已恢复原 RingerMode=" + finalOriginalMode);
                        }
                    } catch (Exception ignored) {}
                }, 30000);
            } else FileLogger.log(this, "ALARM", "❌ 获取警报铃声失败");
        } catch (Exception e) {
            FileLogger.log(this, "ALARM", "❌ 播放警报异常: " + e.getMessage());
        }
    }

    private void startForegroundNotification() {
        // ★ H4 修复 2026-11-07：已在前台则跳过，避免普通 onStartCommand 重发导致通知闪烁。
        //   需要强制刷新的场景（点击重置 / 密钥解锁）调 restartForegroundNotification。
        if (isForegroundStarted) {
            return;
        }
        String channelId = "device_guardian_channel";
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(channelId, "系统服务", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false); channel.setSound(null, null); channel.enableVibration(false); channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            if (manager != null) manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this); builder.setPriority(Notification.PRIORITY_MIN);
        }

        int iconRes = android.R.drawable.stat_notify_sync;
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent chatPi;
        if (notificationClickCount >= 2 && !isChatLocked()) {
            Intent chatIntent = new Intent(this, ChatActivity.class);
            chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            chatPi = PendingIntent.getActivity(this, 888, chatIntent, piFlags);
        } else {
            Intent unlockIntent = new Intent(this, TrackingService.class);
            unlockIntent.setAction("ACTION_SECRET_UNLOCK");
            chatPi = PendingIntent.getService(this, 888, unlockIntent, piFlags);
        }

        Notification notification = builder.setContentTitle("系统同步").setContentText("正在运行...").setSmallIcon(iconRes).setContentIntent(chatPi).setOngoing(true).build();
        notification.priority = Notification.PRIORITY_MIN;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1001, notification);
            }
            isForegroundStarted = true;  // ★ H4 修复：标记已挂起前台
        } catch (Exception e) {
            FileLogger.log(this, "SERVICE", "❌ 带 location type 的 startForeground 失败: " + e.getMessage());
            // 降级尝试：使用 dataSync 类型
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(1001, notification);
                }
                isForegroundStarted = true;  // ★ H4 修复：降级成功也标记
                FileLogger.log(this, "SERVICE", "✅ 已降级为 dataSync 类型前台服务");
            } catch (Exception e2) {
                FileLogger.log(this, "SERVICE", "❌❌ 所有类型均失败，服务可能被系统清理: " + e2.getMessage());
                // ★ [终极兜底] 如果服务彻底起不来，必须把隐藏的图标恢复，否则用户彻底失联！
                try {
                    LauncherIconUtils.setLauncherIconVisible(this, true);
                    FileLogger.log(this, "SERVICE", "⚠️ 已强制恢复桌面图标，防止失联");
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * ★ H4 修复 2026-11-07：强制重发前台通知。
     * 只在 onCreate 首次 / ACTION_RESET_NOTIFICATION / ACTION_SECRET_UNLOCK 需要重发时调用。
     */
    private void restartForegroundNotification() {
        isForegroundStarted = false;
        startForegroundNotification();
    }

    private void scheduleHeartbeatAlarm() {
        if (alarmManager == null) return;
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_HEARTBEAT");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pi = PendingIntent.getForegroundService(this, 9999, intent, flags);
        } else {
            pi = PendingIntent.getService(this, 9999, intent, flags);
        }

        // ★ 补丁4：设置前强制 cancel 旧句柄，规避 Android 12+ 精确闹钟 Quota 节流斩杀
        try { alarmManager.cancel(pi); } catch (Exception ignored) {}

        // ★ 补丁2：使用全局可调变量 heartbeatAlarmIntervalMs
        long triggerAt = System.currentTimeMillis() + heartbeatAlarmIntervalMs;
        lastHeartbeatExpectedAt = triggerAt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }

    private String getUniqueDeviceId() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String deviceId = sp.getString("saved_device_id", null);
        if (deviceId == null) {
            String model = Build.MODEL != null ? Build.MODEL.replaceAll("\\s+", "_") : "UnknownPhone";
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String suffix = (androidId != null && androidId.length() >= 4) ? androidId.substring(androidId.length() - 4) : "0000";
            deviceId = model + "_" + suffix;
            sp.edit().putString("saved_device_id", deviceId).apply();
        }
        return deviceId;
    }

    private String getCurrentServerUrl() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String saved = sp.getString("saved_server_url", defaultServerUrl);
        if (!isValidUrl(saved)) {
            FileLogger.log(this, "CONFIG", "⚠️ saved_server_url 非法: [" + saved + "]，回退默认值");
            sp.edit().putString("saved_server_url", defaultServerUrl).apply();
            return defaultServerUrl;
        }
        return saved;
    }

    private void saveNewServerUrl(String newUrl) {
        if (!isValidUrl(newUrl)) { FileLogger.log(this, "CONFIG", "⚠️ 拒绝保存非法 server_url: [" + newUrl + "]"); return; }
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("saved_server_url", newUrl).commit();
        FileLogger.log(this, "CONFIG", "📝 server_url 已更新: " + newUrl);
    }

    private String getRemoteConfigUrl() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String saved = sp.getString("saved_config_url", DEFAULT_REMOTE_CONFIG_URL);
        if (!isValidUrl(saved)) {
            FileLogger.log(this, "CONFIG", "⚠️ saved_config_url 非法: [" + saved + "]，回退默认值");
            return DEFAULT_REMOTE_CONFIG_URL;
        }
        return saved;
    }

    private void saveRemoteConfigUrl(String url) {
        if (!isValidUrl(url)) { FileLogger.log(this, "CONFIG", "⚠️ 拒绝保存非法 config_url: [" + url + "]"); return; }
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        sp.edit().putString("saved_config_url", url).commit();
        FileLogger.log(this, "CONFIG", "📝 config_url 已更新: " + url);
    }

    private void handleUpdateConfigUrl(String newConfigUrl) {
        if (newConfigUrl != null && !newConfigUrl.isEmpty()) { saveRemoteConfigUrl(newConfigUrl); lastConfigFetchTime = 0; fetchRemoteConfig(); }
    }

    private void handleIconVisibility(boolean hideIcon) {
        boolean ok = LauncherIconUtils.setLauncherIconVisible(this, !hideIcon);
        if (ok) FileLogger.log(this, "SERVICE", "🔧 图标状态: " + (hideIcon ? "隐藏" : "显示"));
        else FileLogger.log(this, "SERVICE", "⚠️ 修改图标状态失败 (hideIcon=" + hideIcon + ")");
    }

    /**
     * Socket binding, local fingerprinting and broadcast selection must use the same Wi-Fi
     * Network. Selecting the active Wi-Fi wins; when cellular is default, only one
     * non-default Wi-Fi candidate is unambiguous. Never pick an arbitrary interface.
     */
    private Network selectWifiLanNetwork(ConnectivityManager cm) throws java.io.IOException {
        // This is the single authority for the socket, its path fingerprint and its broadcast NIC.
        if (cm == null) throw new java.io.IOException("ConnectivityManager unavailable");
        List<Network> wifiNetworks = new ArrayList<>();
        boolean incompleteNetworkSnapshot = false;
        try {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) {
                    incompleteNetworkSnapshot = true;
                    continue;
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks.add(network);
                }
            }
            if (wifiNetworks.isEmpty()) {
                if (incompleteNetworkSnapshot) {
                    throw new java.io.IOException("No complete Wi-Fi candidate; defer UDP socket binding");
                }
                return null;
            }
            Network active = cm.getActiveNetwork();
            if (active != null && wifiNetworks.contains(active)) return active;
            if (wifiNetworks.size() == 1) return wifiNetworks.get(0);
            throw new java.io.IOException("Multiple non-default Wi-Fi networks; defer ambiguous UDP socket binding");
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            throw new java.io.IOException("Unable to inspect Wi-Fi network candidates", e);
        }
    }

    private String buildWifiLanFingerprint(ConnectivityManager cm) {
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "UNAVAILABLE";
        final Network selected;
        try {
            selected = selectWifiLanNetwork(cm);
        } catch (java.io.IOException unavailable) {
            return "UNAVAILABLE";
        }
        if (selected == null) return "NO_WIFI";
        try {
            LinkProperties links = cm.getLinkProperties(selected);
            if (links == null || links.getInterfaceName() == null
                    || links.getInterfaceName().isEmpty()
                    || links.getLinkAddresses() == null
                    || links.getLinkAddresses().isEmpty()) return "UNAVAILABLE";
            List<String> addressEntries = new ArrayList<>();
            for (android.net.LinkAddress address : links.getLinkAddresses()) {
                addressEntries.add(address.toString());
            }
            java.util.Collections.sort(addressEntries);
            List<String> routeEntries = new ArrayList<>();
            for (android.net.RouteInfo route : links.getRoutes()) {
                routeEntries.add(route.toString());
            }
            java.util.Collections.sort(routeEntries);
            // Only the selected socket-bound LAN path participates. Changes to a secondary
            // non-default Wi-Fi candidate must not revoke peers or restart this socket.
            return selected.toString() + '|' + links.getInterfaceName() + '|'
                    + addressEntries + '|' + routeEntries;
        } catch (Exception e) {
            return "ERROR";
        }
    }

    /**
     * Keep the full canonical fingerprint locally for change detection, but never put
     * Android LinkProperties.toString() output on the wire. Route lists can be large
     * enough to exceed the strict 1200-byte UDP envelope. This digest is only a compact
     * diagnostic/scope hint; it is not peer identity, authentication, or reachability proof.
     */
    static String buildLanProtocolNetworkScope(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()
                || "UNAVAILABLE".equals(fingerprint)
                || "ERROR".equals(fingerprint)
                || "NO_WIFI".equals(fingerprint)) return "";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder out = new StringBuilder("sha256:");
            for (byte value : digest) {
                int b = value & 0xff;
                out.append(hex[b >>> 4]).append(hex[b & 0x0f]);
            }
            return out.toString();
        } catch (Exception unavailable) {
            // The hint is optional. Never fail discovery because a local digest provider
            // is unavailable; the authenticated nonce challenge remains authoritative.
            return "";
        }
    }

    private String getLanProtocolNetworkScope() {
        return buildLanProtocolNetworkScope(wifiLanFingerprint);
    }

    /**
     * LAN chat must not consume retry attempts or broadcast over a cellular-only socket.
     * A transient/incomplete Android network snapshot fails closed; the next callback
     * or listener rebind will resume the durable outbox.
     */
    private boolean hasUsableWifiLanPath() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            String broadcast = getWifiBroadcastAddress();
            return broadcast != null && !broadcast.isEmpty()
                    && !"255.255.255.255".equals(broadcast);
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        String current = buildWifiLanFingerprint(cm);
        if ("NO_WIFI".equals(current) || "ERROR".equals(current) || "UNAVAILABLE".equals(current)) {
            return false;
        }
        String known = wifiLanFingerprint;
        if (known == null) {
            wifiLanFingerprint = current;
            return true;
        }
        if (!known.equals(current)) {
            executeMaintenance("wifi-lan-refresh",
                    () -> refreshWifiLanTransport("send-path-validation"));
            return false;
        }
        return true;
    }

    private boolean isCurrentLanChatSocket(DatagramSocket socket, long generation) {
        return socket != null && !socket.isClosed()
                && rescueSocketRef == socket
                && generation == lanSocketGeneration.get();
    }

    /**
     * Wi-Fi can carry local P2P even when cellular remains the default route.
     * Rebind the UDP listener whenever the actual Wi-Fi LAN path changes.
     */
    private void refreshWifiLanTransport(String reason) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            String current = buildWifiLanFingerprint(cm);
            // A transient ConnectivityManager/API failure is not evidence that the LAN
            // changed. Preserve the last known-good fingerprint and verified routes;
            // a later callback will retry the comparison with a real snapshot.
            if ("ERROR".equals(current) || "UNAVAILABLE".equals(current)) {
                FileLogger.logD("CHAT", "Wi-Fi LAN fingerprint unavailable; retaining verified peer routes");
                return;
            }
            final long socketGeneration;
            final DatagramSocket oldSocket;
            // Publish the new epoch and snapshot its socket under the same monitor used
            // when a listener publishes/clears its socket reference.
            synchronized (rescueLock) {
                String previous = wifiLanFingerprint;
                wifiLanFingerprint = current;
                if (previous == null || previous.equals(current)) return;
                socketGeneration = lanSocketGeneration.incrementAndGet();
                lanChatBoundWifiNetwork = null;
                oldSocket = rescueSocketRef;
            }
            FileLogger.log(this, "NETWORK", "Wi-Fi LAN socket generation advanced generation="
                    + socketGeneration + " reason=" + reason);
            invalidateVerifiedChatPeers("Wi-Fi LAN path changed: " + reason);
            lastPeerDiscoveryAt = 0L;
            if (oldSocket != null && !oldSocket.isClosed()) {
                FileLogger.log(this, "NETWORK", "Wi-Fi LAN changed; closing old UDP socket for rebind generation="
                        + socketGeneration);
                oldSocket.close();
            } else {
                // A LAN path can appear/change while the listener is not running yet.
                // Do not rely solely on the old receive loop's finally block to restart it.
                triggerRescueListener();
            }
            // 队列补发改由 UDP socket 重新绑定成功后触发，避免对着已关闭/未就绪 socket 空跑。
        } catch (Exception e) {
            FileLogger.log(this, "NETWORK", "Wi-Fi LAN refresh failed: " + e.getClass().getSimpleName());
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        // Registration is a pair: default-route and all-Wi-Fi observers must both exist.
        if (networkCallback != null && wifiLanNetworkCallback != null) return;
        try {
            if (networkCallback != null) {
                try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
                networkCallback = null;
            }
            if (wifiLanNetworkCallback != null) {
                try { cm.unregisterNetworkCallback(wifiLanNetworkCallback); } catch (Exception ignored) {}
                wifiLanNetworkCallback = null;
            }
            {
                lastPeerRouteGeneration = buildNetworkSnapshot().optLong("network_generation", -1L);
                wifiLanFingerprint = buildWifiLanFingerprint(cm);
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        // onAvailable 可能先于 LinkProperties/默认网络切换稳定；稍作合并后再上报。
                        executeMaintenance("peer-route-refresh", () -> refreshPeerRoutesForNetworkChange("network available/switch"));
                        FileLogger.log(TrackingService.this, "NETWORK", "✅ 网络可用/发生切换，刷新默认网络信息");
                        handler.removeCallbacks(networkRefreshRunnable);
                        handler.postDelayed(networkRefreshRunnable, 1200L);
                        executeMaintenance("network-available-flush", TrackingService.this::flushPendingReports);
                    }
                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        // onLost 可能只是链路变化，不直接认定设备断网；延迟读取当前默认网络再判断。
                        executeMaintenance("peer-route-refresh", () -> refreshPeerRoutesForNetworkChange("network lost"));
                        FileLogger.log(TrackingService.this, "NETWORK", "ℹ️ 网络链路变化，重新检查当前默认网络");
                        handler.removeCallbacks(networkRefreshRunnable);
                        handler.postDelayed(networkRefreshRunnable, 1500L);
                    }
                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        super.onCapabilitiesChanged(network, capabilities);
                        executeMaintenance("peer-route-refresh", () -> refreshPeerRoutesForNetworkChange("network capabilities changed"));
                        handler.removeCallbacks(networkRefreshRunnable);
                        handler.postDelayed(networkRefreshRunnable, 800L);
                    }
                    @Override
                    public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                        super.onLinkPropertiesChanged(network, linkProperties);
                        executeMaintenance("peer-route-refresh", () -> refreshPeerRoutesForNetworkChange("link properties changed"));
                        handler.removeCallbacks(networkRefreshRunnable);
                        handler.postDelayed(networkRefreshRunnable, 800L);
                    }
                };
                // Android 7.0+ 可直接观察默认网络；Android 5/6 使用 INTERNET 请求回调
                // 兼容旧系统。旧系统回调可能覆盖多个候选网络，刷新逻辑始终重新读取系统当前默认网络。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(networkCallback);
                } else {
                    android.net.NetworkRequest defaultNetworkRequest =
                            new android.net.NetworkRequest.Builder()
                                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    .build();
                    cm.registerNetworkCallback(defaultNetworkRequest, networkCallback);
                }
                // Observe all Wi-Fi networks even when the active default network is cellular.
                wifiLanNetworkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) {
                        super.onAvailable(network);
                        executeMaintenance("wifi-lan-refresh", () -> refreshWifiLanTransport("Wi-Fi available"));
                    }
                    @Override public void onLost(Network network) {
                        super.onLost(network);
                        executeMaintenance("wifi-lan-refresh", () -> refreshWifiLanTransport("Wi-Fi lost"));
                    }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        super.onCapabilitiesChanged(network, capabilities);
                        executeMaintenance("wifi-lan-refresh", () -> refreshWifiLanTransport("Wi-Fi capabilities changed"));
                    }
                    @Override public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                        super.onLinkPropertiesChanged(network, linkProperties);
                        executeMaintenance("wifi-lan-refresh", () -> refreshWifiLanTransport("Wi-Fi link properties changed"));
                    }
                };
                android.net.NetworkRequest wifiRequest = new android.net.NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                cm.registerNetworkCallback(wifiRequest, wifiLanNetworkCallback);
            }
        } catch (Exception e) {
            // Roll back the other observer too if either half of the pair failed.
            if (networkCallback != null) {
                try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            }
            if (wifiLanNetworkCallback != null) {
                try { cm.unregisterNetworkCallback(wifiLanNetworkCallback); } catch (Exception ignored) {}
            }
            networkCallback = null;
            wifiLanNetworkCallback = null;
            FileLogger.log(this, "NETWORK", "❌ 网络回调成对注册失败，已回滚并等待自愈重试: " + e.getMessage());
        }
    }

    // The debounce timer runs on the main Handler, but network IPC, offline queue
    // flush and LAN recovery are maintenance work and must not run on the UI thread.
    private final Runnable networkRefreshRunnable = () -> executeMaintenance("network-refresh", () -> {
        try {
            long previousChangedAt = lastNetworkChangedAt;
            JSONObject snapshot = buildNetworkSnapshot();
            boolean changed = snapshot.optLong("changed_at", 0L) != previousChangedAt;
            String state = snapshot.optString("transport", "UNKNOWN");
            String ip = snapshot.optString("local_ip", "");
            FileLogger.log(TrackingService.this, "NETWORK", "网络状态更新: " + state + " | IP=" + ip
                    + " | 前缀=" + snapshot.optString("subnet_prefix", "-"));
            if (changed) sendReport(true, false);
            if ("WIFI".equals(state) || "ETHERNET".equals(state)) {
                flushPendingReports();
                if (changed) triggerRescueListener();
                // 首次启动、网络回调重建或服务重启也要恢复待确认消息，不只依赖指纹变化。
                retryPendingLanChatMessages(true);
            }
        } catch (Exception e) {
            FileLogger.log(TrackingService.this, "NETWORK", "⚠️ 刷新网络状态失败: " + e.getMessage());
        }
    });

    /**
     * 获取当前默认网络的轻量快照。SSID/BSSID 仅作可选诊断信息，不参与同网授权；
     * 子网前缀由 LinkProperties 的真实 IPv4 地址/前缀长度生成。
     *
     * LAN P2P 的按需 UDP challenge/response 在聊天发送路径中独立执行，不在网络快照构建时探测。
     * 本快照仅记录系统提供的当前默认网络信息；不扫描 Wi-Fi、不将网段指纹作为可达证明。
     */
    private JSONObject buildNetworkSnapshot() {
        JSONObject out = new JSONObject();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = null;
            NetworkCapabilities caps = null;
            LinkProperties links = null;
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                active = cm.getActiveNetwork();
                if (active != null) {
                    caps = cm.getNetworkCapabilities(active);
                    links = cm.getLinkProperties(active);
                }
            }
            String transport = "OFFLINE";
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "WIFI";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "MOBILE";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ETHERNET";
                else transport = "OTHER";
            } else if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    if (ni.getType() == ConnectivityManager.TYPE_WIFI) transport = "WIFI";
                    else if (ni.getType() == ConnectivityManager.TYPE_MOBILE) transport = "MOBILE";
                    else transport = "OTHER";
                }
            }
            String localIp = "";
            String subnetPrefix = "";
            String interfaceName = "";
            String gatewayIp = "";
            if (links != null) {
                interfaceName = links.getInterfaceName() != null ? links.getInterfaceName() : "";
                try {
                    List<RouteInfo> routes = links.getRoutes();
                    if (routes != null) {
                        for (RouteInfo route : routes) {
                            if (route != null && route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                                gatewayIp = route.getGateway().getHostAddress();
                                if (gatewayIp != null && !gatewayIp.isEmpty()) break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                List<LinkAddress> addresses = links.getLinkAddresses();
                if (addresses != null) {
                    for (LinkAddress la : addresses) {
                        if (la == null || !(la.getAddress() instanceof Inet4Address)) continue;
                        String candidate = la.getAddress().getHostAddress();
                        if (candidate == null || candidate.startsWith("127.") || candidate.startsWith("169.254.")) continue;
                        localIp = candidate;
                        subnetPrefix = candidate + "/" + la.getPrefixLength();
                        break;
                    }
                }
            }
            if (localIp.isEmpty()) {
                String fallbackIp = DeviceInfoUtils.getLocalIpAddress();
                if (fallbackIp != null && !fallbackIp.isEmpty()) localIp = fallbackIp;
            }
            boolean hasInternet = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean validated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            out.put("transport", transport);
            out.put("network_type", transport);
            out.put("local_ip", localIp);
            out.put("subnet_prefix", subnetPrefix);
            out.put("interface_name", interfaceName);
            if (!gatewayIp.isEmpty()) out.put("gateway_ip", gatewayIp);
            out.put("has_internet", hasInternet ? 1 : 0);
            out.put("validated", validated ? 1 : 0);
            out.put("network_subtype", "MOBILE".equals(transport) ? "cellular" : transport.toLowerCase(java.util.Locale.US));
            String networkIdentity = active != null ? active.toString() : "";
            String signature = networkIdentity + "|" + transport + "|" + localIp + "|" + subnetPrefix + "|" + interfaceName
                    + "|" + gatewayIp + "|" + hasInternet + "|" + validated;
            synchronized (NETWORK_STATE_LOCK) {
                if (!signature.equals(lastNetworkSignature)) {
                    lastNetworkSignature = signature;
                    lastNetworkChangedAt = System.currentTimeMillis();
                    networkGeneration++;
                    if (networkGeneration <= 0L) networkGeneration = 1L;
                }
                out.put("changed_at", lastNetworkChangedAt);
                out.put("network_generation", networkGeneration);
            }
        } catch (Exception e) {
            try { out.put("transport", "UNKNOWN"); out.put("network_type", "UNKNOWN"); } catch (Exception ignored) {}
            FileLogger.log(this, "NETWORK", "⚠️ 构建网络快照失败: " + e.getMessage());
        }
        return out;
    }

    private void startLocationUpdates() {
        try {
            locationManager.removeUpdates(this);
            boolean gpsOn = false;
            boolean netOn = false;
            String allProviders = "getProviders 失败";
            try {
                List<String> providers = locationManager.getProviders(true);
                if (providers != null) {
                    allProviders = providers.toString();
                    gpsOn = providers.contains(LocationManager.GPS_PROVIDER);
                    netOn = providers.contains(LocationManager.NETWORK_PROVIDER);
                }
            } catch (Exception e) {
                FileLogger.log(this, "LOCATION", "⚠️ getProviders 失败，fallback 到 isProviderEnabled: " + e.getMessage());
                try { gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                try { netOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                allProviders = "fallback(GPS=" + gpsOn + ",NET=" + netOn + ")";
            }
            FileLogger.log(this, "LOCATION", "provider 状态: GPS=" + (gpsOn ? "on" : "off")
                    + ", NETWORK=" + (netOn ? "on" : "off")
                    + " | 全部: " + allProviders + " | 模式=" + accuracyMode);

            long minTime = 60000L;
            if ("fine".equals(accuracyMode) && gpsOn) {
                try { locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, minTime, 0f, this, Looper.getMainLooper()); }
                catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 GPS 失败: " + e.getMessage()); }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    locationManager.requestLocationUpdates(
                            LocationManager.FUSED_PROVIDER, minTime, 0f, this, Looper.getMainLooper());
                    FileLogger.log(this, "LOCATION", "📡 已请求 FUSED_PROVIDER");
                } catch (Exception e) {
                    FileLogger.log(this, "LOCATION", "⚠️ FUSED_PROVIDER 不可用（忽略）: " + e.getMessage());
                }
            }
                        boolean hasNetworkProvider = false;
            try {
                List<String> providerList = locationManager.getProviders(true);
                hasNetworkProvider = providerList != null && providerList.contains(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignored) {}
            if (hasNetworkProvider) {
                try { locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, minTime, 0f, this, Looper.getMainLooper()); }
                catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 NETWORK 失败: " + e.getMessage()); }
            } else {
                FileLogger.log(this, "LOCATION", "ℹ️ NETWORK provider 不存在（Android 12+ 正常），使用 FUSED");
            }
        } catch (SecurityException e) {
            FileLogger.log(this, "LOCATION", "❌ 定位权限缺失，无法注册 provider: " + e.getMessage());
        } catch (Exception e) {
            FileLogger.log(this, "LOCATION", "❌ 注册定位监听失败: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            lastLocation = location;
            long now = System.currentTimeMillis();
            if (now - lastLocationLogTime > 5 * 60 * 1000L) {
                lastLocationLogTime = now;
                FileLogger.log(this, "LOCATION", "📍 lat=" + location.getLatitude()
                        + ", lng=" + location.getLongitude()
                        + ", acc=" + location.getAccuracy() + "m"
                        + ", provider=" + location.getProvider());
            }
        }
    }

    private void requestSingleLocationAndReport() {
        final boolean[] reported = new boolean[]{false};
        final Handler timeoutHandler = new Handler(Looper.getMainLooper());
        final LocationListener singleListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) {
                    lastLocation = location;
                    FileLogger.log(TrackingService.this, "LOCATION", "📍 单次定位成功: provider="
                            + location.getProvider() + ", acc=" + location.getAccuracy() + "m");
                }
                if (!reported[0]) {
                    reported[0] = true;
                    timeoutHandler.removeCallbacksAndMessages(null);
                    try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
                    sendReport(true, false);
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
                FileLogger.log(this, "LOCATION", "📡 已请求 FUSED_PROVIDER（单次）");
            } catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 FUSED_PROVIDER 失败（忽略）: " + e.getMessage()); }
        }
        try {
            List<String> pl = locationManager.getProviders(true);
            if (pl != null && pl.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
                FileLogger.log(this, "LOCATION", "📡 已请求 NETWORK provider（单次）");
            }
        } catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 NETWORK 失败: " + e.getMessage()); }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, singleListener, Looper.getMainLooper());
            FileLogger.log(this, "LOCATION", "📡 已请求 GPS provider（单次）");
        } catch (Exception e) { FileLogger.log(this, "LOCATION", "⚠️ 请求 GPS 失败: " + e.getMessage()); }

        timeoutHandler.postDelayed(() -> {
            if (!reported[0]) {
                reported[0] = true;
                try { locationManager.removeUpdates(singleListener); } catch (Exception ignored) {}
                FileLogger.log(this, "LOCATION", "⚠️ 60 秒内未获取到新定位，lastLocation="
                        + (lastLocation != null ? ("旧值(lat=" + lastLocation.getLatitude() + ")") : "无"));
                sendReport(true, false);
            }
        }, 60000);
    }

    private Location acquireFreshLocation(long timeoutMs) {
        final Location[] result = new Location[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        android.location.LocationListener tmpListener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null && result[0] == null) { result[0] = location; latch.countDown(); }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 0, 0, tmpListener, Looper.getMainLooper()); }
                catch (Exception ignored) {}
            }
            try {
                List<String> pl = locationManager.getProviders(true);
                if (pl != null && pl.contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, tmpListener, Looper.getMainLooper());
                }
            } catch (Exception ignored) {}
            try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, tmpListener, Looper.getMainLooper()); }
            catch (Exception ignored) {}
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try { locationManager.removeUpdates(tmpListener); } catch (Exception ignored) {}
        }
        return result[0];
    }

    // ★ 2026-11-20 20:20 借鉴双保险：内存级周期上报计时器（与 AlarmManager 硬件闹钟互补，绕过定制 ROM 对后台闹钟的限流）
    private final Runnable inMemoryReportRunnable = () -> {
        if (!serviceDestroyed) {
            long now = System.currentTimeMillis();
            if (now - lastAlarmTriggerTime < 2000) { // 与硬件闹钟 2 秒内撞车时自动防重
                FileLogger.log(TrackingService.this, "ALARM", "⏭ 内存级计时器与硬件闹钟撞车，已按完整周期自我续期，防双保险熄火");
                scheduleNextReport();
                return;
            }
            lastAlarmTriggerTime = now;
            FileLogger.log(this, "ALARM", "⏰ 内存级周期计时器触发上报");
            sendReport(true, false);
        }
    };

    private void scheduleNextReport() {
        synchronized (alarmScheduleLock) { // v2.0.1 补丁：确保排队与取消的原子性
            int rc = reportCount.get();
            long delay = (rc == 1 || rc == 2) ? 3 * 60 * 1000L : reportIntervalMs;

            // 1. 第一重保险（内存级）：进程存活期间准点触发，不受 MIUI/ColorOS 后台闹钟推迟影响
            handler.removeCallbacks(inMemoryReportRunnable);
            if (!serviceDestroyed) {
                handler.postDelayed(inMemoryReportRunnable, delay);
            }

            // 2. 第二重保险（硬件级）：保留原有 AlarmManager，防深度 Doze 休眠或进程被回收
            if (alarmManager == null || alarmPendingIntent == null) return;
            try { alarmManager.cancel(alarmPendingIntent); } catch (Exception ignored) {}

            long triggerAtMillis = System.currentTimeMillis() + delay;
            lastAlarmExpectedAt = triggerAtMillis;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
                }
            } catch (Exception e) {
                FileLogger.log(this, "ALARM", "⚠️ 主上报闹钟设置失败: " + e.getMessage());
            }
        }
    }


    private void sendReport(final boolean withNearbyWifi, boolean force) { sendReport(withNearbyWifi, force, null); }

    private void sendReport(final boolean withNearbyWifi, boolean force, final String overrideUrl) {
        long currentTime = System.currentTimeMillis();
        boolean dedupSuppressed = false;
        synchronized (reportLock) {
            if (!force && currentTime - lastReportTime < 5000) dedupSuppressed = true;
            else if (force && currentTime - lastReportTime < 2000) dedupSuppressed = true;
            else lastReportTime = currentTime;
        }
        if (dedupSuppressed) {
            // ★ 2026-11-20 [C2] 修复：被去重窗口抑制时原先直接 return，既不走上报的 finally、也不补排
            //   scheduleNextReport。一旦此刻硬件闹钟正好被 MIUI/ColorOS 限流推迟（正是双保险要防的场景），
            //   周期上报链会静默断裂，只能等短信/UDP/运动等其它触发源。这里在锁外补排下一轮，
            //   scheduleNextReport 自带 removeCallbacks + alarmManager.cancel，重复调用安全。
            FileLogger.log(this, "REPORT", "⏭ 去重窗口抑制本次上报，已补排下一轮定时任务，防止周期链断裂");
            if (!serviceDestroyed) scheduleNextReport();
            return;
        }
        final String activeUrl = (overrideUrl != null && !overrideUrl.isEmpty()) ? overrideUrl : getCurrentServerUrl();

        if (!reportInFlight.compareAndSet(false, true)) {
            FileLogger.log(this, "REPORT", "⏭ 已有上报任务在执行或排队，跳过重复触发");
            return;
        }
        try {
        reportExecutor.execute(() -> {
            boolean success = false;
            // ★ 2026-11-20 [低危1] 记录本次上报体携带的指令回执 id，服务端确认（HTTP 200）后本地回收
            final java.util.Set<String> sentAckIds = new java.util.LinkedHashSet<>();
            if (enableWifiLock && wifiLock != null && !wifiLock.isHeld()) {
                try { wifiLock.acquire(); } catch (Exception e) { FileLogger.log(TrackingService.this, "REPORT", "⚠️ WifiLock acquire 失败: " + e.getMessage()); }
            }
            try {
                // ★ H3 修复 2026-11-07：wakeLock 从 10s 延长到 60s。
                //   上报内部最坏耗时：acquireFreshLocation(10s) + WiFi 扫描(3s) + HTTP 读写(8s) ≈ 21s，
                //   10s 会在定位阶段就到期，导致后续 CPU 休眠，表现为偶发上报超时/被系统打断。
                //   60s 只是上限，finally 里 release 会提前释放。
                if (wakeLock != null) {
                    try { wakeLock.acquire(60_000); } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "REPORT", "⚠️ WakeLock acquire 失败: " + e.getMessage());
                    }
                }
                int[] battery = DeviceInfoUtils.getBatteryInfo(TrackingService.this);
                String[] wifi = DeviceInfoUtils.getWifiInfo(TrackingService.this);
                String wifiSsid = wifi[0];
                String ip = DeviceInfoUtils.getLocalIpAddress();

                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNet = cm.getActiveNetworkInfo();
                boolean isOnline = (activeNet != null && activeNet.isConnected());
                String netDesc = isOnline ? getNetworkTypeDesc(activeNet) : "离线";
                FileLogger.log(TrackingService.this, "REPORT", "准备上报. 网络:" + netDesc + " | SSID:" + wifiSsid + " | IP:" + ip + " | 电量:" + battery[0] + "%");

                Location loc = lastLocation;
                long locAgeMs = loc != null ? (System.currentTimeMillis() - loc.getTime()) : Long.MAX_VALUE;
                final long FRESH_THRESHOLD_MS = 10 * 60 * 1000L;
                final long FRESH_TIMEOUT_MS = 3000L; // v2.0.1: 缩短至3秒，防阻塞线程池

                if (loc == null || locAgeMs > FRESH_THRESHOLD_MS) {
                    if (loc != null && locAgeMs > 15 * 60 * 1000L && !force) {
                        FileLogger.log(TrackingService.this, "REPORT", "⏭ 跳过定位刷新（旧值 " + (locAgeMs / 60000) + " 分钟前），直接使用旧坐标");
                    } else {
                        long t0 = System.currentTimeMillis();
                        Location fresh = acquireFreshLocation(FRESH_TIMEOUT_MS);
                        long costMs = System.currentTimeMillis() - t0;
                        if (fresh != null) {
                            FileLogger.log(TrackingService.this, "REPORT", "🔎 主动刷新定位成功 (耗时 " + costMs + "ms, provider=" + fresh.getProvider() + ", acc=" + (int) fresh.getAccuracy() + "m)");
                            loc = fresh;
                            lastLocation = fresh;
                        } else {
                            FileLogger.log(TrackingService.this, "REPORT", "⚠️ 主动刷新定位超时 (耗时 " + costMs + "ms, 旧值 " + (locAgeMs == Long.MAX_VALUE ? "无" : (locAgeMs / 1000) + "s 前") + ")，将使用旧值/空值上报");
                        }
                    }
                }

                if (loc == null) {
                    Location netLoc = null, gpsLoc = null, passiveLoc = null;
                    try { netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                    try { gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                    try { passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER); } catch (Exception ignored) {}
                    loc = getBetterLocation(netLoc, gpsLoc);
                    loc = getBetterLocation(loc, passiveLoc);
                }

                JSONObject json = new JSONObject();
                json.put("device_id", getUniqueDeviceId()); json.put("token", AUTH_TOKEN); json.put("timestamp", System.currentTimeMillis());
                json.put("protocol_version", BuildInfo.PROTOCOL_VERSION);
                json.put("build_version", BuildInfo.BUILD_VERSION);
                json.put("os_version", "Android " + Build.VERSION.RELEASE); json.put("chat_locked", isChatLocked());
                json.put("elder_mode", isElderMode());
                // ============================================================
                // 🌟 2026-11-18 终极闭环：动态提取多媒体常驻状态回传后端 REPORTS 表
                // ============================================================
                SharedPreferences spCompanion = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                // 1. 提取陪伴插件全局物理开关状态
                boolean isCompanionOn = spCompanion.getBoolean("plugin_companion_enabled", false);
                json.put("companion_enabled", isCompanionOn ? 1 : 0);
                
                // 2. 只有在开启状态下，才去动态对齐回传当前屏幕正在常驻的多媒体 URL 镜像
                if (isCompanionOn) {
                    // 这四个键值在手机端加载媒体成功后，可以通过 SharedPreferences 动态存入，此处自动打包提取
                    json.put("push_photo_url", spCompanion.getString("current_push_photo_url", ""));
                    json.put("push_video_url", spCompanion.getString("current_push_video_url", ""));
                    json.put("push_audio_url", spCompanion.getString("current_push_audio_url", ""));
                    json.put("push_web_url", spCompanion.getString("current_push_web_url", ""));
                } else {
                    json.put("push_photo_url", "");
                    json.put("push_video_url", "");
                    json.put("push_audio_url", "");
                    json.put("push_web_url", "");
                }

                // v2.0 补丁：通道健康度快照（供 Web 面板可视化）
                //   注意：这是"本次上报前"的状态，不含本次上报结果
                try {
                    SharedPreferences _spCh = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                    long _lastSuccess = _spCh.getLong(PREF_LAST_SUCCESS_REPORT_TIME, 0);

                    boolean _udpOk;
                    synchronized (rescueLock) { _udpOk = isListeningForRescue; }

                    JSONObject channelObj = new JSONObject();
                    channelObj.put("udp_listening", _udpOk);
                    channelObj.put("udp_bind_fail", udpBindFailCount);
                    channelObj.put("consecutive_failures", consecutiveFailures);
                    channelObj.put("offline_queue_size", countPendingReports());
                    channelObj.put("last_success_ago_s",
                            _lastSuccess > 0 ? (System.currentTimeMillis() - _lastSuccess) / 1000 : -1);
                    json.put("channel", channelObj);
                } catch (Exception ignored) {}

                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE); json.put("is_screen_on", pm != null && pm.isInteractive()); json.put("uptime_ms", SystemClock.elapsedRealtime());
                JSONObject battObj = new JSONObject(); battObj.put("level", battery[0]); battObj.put("is_charging", battery[1] == 1); json.put("battery", battObj);
                JSONObject netObj = buildNetworkSnapshot();
                netObj.put("wifi_ssid", wifi[0]);
                netObj.put("wifi_bssid", wifi[1]);
                if (wifi[1] != null && !wifi[1].isEmpty() && !"UNKNOWN".equalsIgnoreCase(wifi[1]))
                    netObj.put("ap_bssid", wifi[1]);
                try { netObj.put("wifi_rssi", Integer.parseInt(wifi[2])); }
                catch (Exception ignored) { netObj.put("wifi_rssi", -127); }
                if (ip != null && !ip.isEmpty() && netObj.optString("local_ip", "").isEmpty())
                    netObj.put("local_ip", ip);
                json.put("network", netObj);

                JSONObject locObj = new JSONObject();
                if (loc != null) {
                    long ageS = Math.max(0, (System.currentTimeMillis() - loc.getTime()) / 1000);
                    locObj.put("latitude", loc.getLatitude());
                    locObj.put("longitude", loc.getLongitude());
                    locObj.put("accuracy", loc.getAccuracy());
                    locObj.put("provider", loc.getProvider());
                    locObj.put("time", loc.getTime());
                    locObj.put("age_s", ageS);
                } else {
                    locObj.put("latitude", JSONObject.NULL); locObj.put("longitude", JSONObject.NULL);
                    locObj.put("accuracy", -1.0); locObj.put("provider", "none");
                    locObj.put("time", 0L); locObj.put("age_s", -1);
                }
                json.put("location", locObj);

                if (isCloudLogEnabled()) {
                    String memoryLogs = FileLogger.getMemoryLogs();
                    if (!memoryLogs.isEmpty()) json.put("cloud_logs", memoryLogs);
                }

                try {
                    java.util.Set<String> executedIds = loadExecutedCmdIds();
                    if (!executedIds.isEmpty()) {
                        JSONArray execArr = new JSONArray();
                        for (String id : executedIds) execArr.put(id);
                        json.put("last_executed_cmd_ids", execArr);
                        sentAckIds.addAll(executedIds); // ★ 2026-11-20 [低危1] 随体上报，成功后回收
                    }
                } catch (Exception e) {
                    FileLogger.log(TrackingService.this, "HTTP_CMD", "⚠️ 上报 executedIds 失败: " + e.getMessage());
                }
                try {
                    JSONArray feedback = loadCommandFeedback();
                    if (feedback.length() > 0) json.put("command_feedback", feedback);
                } catch (Exception e) {
                    FileLogger.log(TrackingService.this, "HTTP_CMD", "⚠️ 上报 command_feedback 失败: " + e.getMessage());
                }

                if (!isOnline && overrideUrl == null) {
                    FileLogger.log(TrackingService.this, "REPORT", "⚠️ 网络离线，排队等待网络恢复后补发");
                    queuePendingReport(json);
                    return;
                }

                if (withNearbyWifi) {
                    try {
                        List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this);
                        if (nearby != null && !nearby.isEmpty()) {
                            JSONArray arr = new JSONArray();
                            for (JSONObject obj : nearby) arr.put(obj);
                            json.put("nearby_wifi", arr);
                        }
                    } catch (Exception ignored) {}
                }

                // ★ 2026-11-20 补丁3①：服务端过载退避拦截 + 找回特权直通
                //   本方法无 reason 参数，但所有高优触发源（短信/UDP唤醒/运动/低电量临终/远程指令/手动/强制）
                //   均以 sendReport(..., force=true) 进入；overrideUrl 直连（换址校验/临时探测）同样视为高优。
                boolean isHighPriority = force || (overrideUrl != null);
                long nowMs = System.currentTimeMillis();
                if (isHighPriority && serverBackoffUntilMs > nowMs) {
                    // 找回特权：立即解除退避，抢占发包（保证找回/定位类指令永不被退避拖慢）
                    serverBackoffUntilMs = 0L;
                    consecutiveServerBusyCount = 0;
                    FileLogger.log(TrackingService.this, "REPORT", "⚡ 高优/找回触发，立即破除过载退避并抢占发包");
                } else if (!isHighPriority && nowMs < serverBackoffUntilMs) {
                    long remainMin = Math.max(1, (serverBackoffUntilMs - nowMs) / 60000);
                    FileLogger.log(TrackingService.this, "REPORT", "⏳ 服务端过载退避中（约 " + remainMin + " 分钟后重试），本次跳过发包直接并入离线队列");
                    if (overrideUrl == null) queuePendingReport(json);
                    return;
                }

                URL url = new URL(activeUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);   // [P0-3] 15000 → 8000，服务器 down 时快速失败
                conn.setReadTimeout(8000);      // [P0-3]
                conn.setDoOutput(true);
                conn.setRequestProperty("Connection", "close");

                long httpStart = System.currentTimeMillis();
                try {
                    OutputStream os = conn.getOutputStream();
                    os.write(json.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    int httpCode = conn.getResponseCode();
                    long httpCost = System.currentTimeMillis() - httpStart;

                    if (httpCode == 200) {
                        // ★ 2026-11-20 补丁3②：收到 200，恢复常态并清零退避状态
                        if (consecutiveServerBusyCount > 0 || serverBackoffUntilMs > 0) {
                            consecutiveServerBusyCount = 0;
                            serverBackoffUntilMs = 0L;
                        }
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder(); String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        success = true; // HTTP 200 已确认上报成功，与下行响应解析分离
                        if (overrideUrl == null) {
                            try {
                                JSONObject responseJson = new JSONObject(sb.toString());
                                ackCommandFeedbackIfConfirmed(json, responseJson);
                                parseResponse(responseJson);
                            }
                            catch (Exception responseParseError) {
                                FileLogger.log(TrackingService.this, "REPORT", "⚠️ 上报已成功，但下行响应解析失败: " + responseParseError.getMessage());
                            }
                        }

                        String locDesc;
                        if (loc != null) {
                            long ageS = Math.max(0, (System.currentTimeMillis() - loc.getTime()) / 1000);
                            locDesc = String.format(java.util.Locale.US, "lat=%.5f lng=%.5f acc=%dm age=%ds src=%s",
                                    loc.getLatitude(), loc.getLongitude(), (int) loc.getAccuracy(), ageS,
                                    loc.getProvider() != null ? loc.getProvider() : "?");
                        } else locDesc = "无定位";
                        FileLogger.log(TrackingService.this, "REPORT", "✅ 上报成功 " + locDesc + " (耗时 " + httpCost + "ms)");
                    } else {
                        // ★ 2026-11-20 补丁3②：仅对网关/服务端过载(502/503/504/429)触发指数退避；普通网络异常不退避
                        if (httpCode == 502 || httpCode == 503 || httpCode == 504 || httpCode == 429) {
                            consecutiveServerBusyCount++;
                            long backoffMs = Math.min(SERVER_BACKOFF_MAX_MS,
                                    SERVER_BACKOFF_BASE_MS * (1L << Math.min(consecutiveServerBusyCount - 1, 2)));
                            serverBackoffUntilMs = System.currentTimeMillis() + backoffMs;
                            FileLogger.log(TrackingService.this, "REPORT", "🛡️ 服务端过载 (HTTP " + httpCode + ")，触发退避冷却 " + (backoffMs / 60000) + " 分钟");
                        }
                        FileLogger.log(TrackingService.this, "REPORT", "⚠️ 上报返回非200: HTTP " + httpCode + " (耗时 " + httpCost + "ms) | " + activeUrl);
                        if (overrideUrl == null) queuePendingReport(json);
                    }
                } catch (Exception e) {
                    String errMsg = e.getMessage();
                    if (errMsg != null && errMsg.contains("Unable to resolve host"))
                        FileLogger.log(TrackingService.this, "REPORT", "❌ DNS 解析失败: " + errMsg);
                    else FileLogger.log(TrackingService.this, "REPORT", "❌ 上报异常: " + errMsg);
                    Log.e("TrackingService", "===== 上报失败啦 =====");
                    Log.e("TrackingService", "目标URL: " + activeUrl);
                    Log.e("TrackingService", "报错原因: " + e.toString());
                    if (overrideUrl == null) queuePendingReport(json);
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.contains("Unable to resolve host"))
                    FileLogger.log(TrackingService.this, "REPORT", "❌ DNS 解析失败: " + errMsg);
                else FileLogger.log(TrackingService.this, "REPORT", "❌ 准备上报异常: " + errMsg);
            } finally {
                try {
                    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                    if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
                } catch (Exception ignored) {}
                reportInFlight.set(false);
                if (overrideUrl == null) {
                    reportCount.incrementAndGet();
                    scheduleNextReport();
                }
            }

            if (success) {
                // ★ 2026-11-20 [低危1] 修复：服务端已确认这批回执（会从下行队列删除），本地同步回收。
                //   旧版只有 load/save（只追加），executed_cmd_ids 永不清空 → 每条上报永久多带最多
                //   EXECUTED_CMD_IDS_MAX=100 个历史 id（约 2KB），白白消耗弱网带宽。
                //   overrideUrl 直连（换址校验/临时探测）的对端不一定是正式服务端，故不回收。
                if (overrideUrl == null && !sentAckIds.isEmpty()) removeExecutedCmdIds(sentAckIds);
                consecutiveFailures = 0;
                // ★ [核心优化] 2026-11-04 将成功时间写入 SharedPreferences，持久化状态，防进程被杀看门狗失效
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                        .edit().putLong(PREF_LAST_SUCCESS_REPORT_TIME, System.currentTimeMillis()).apply();
                fetchRemoteConfig();
            } else {
                if (overrideUrl == null) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 1) { triggerRescueListener(); fetchRemoteConfig(); }
                }
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            reportInFlight.set(false);
            FileLogger.log(this, "REPORT", "⚠️ 上报任务队列已满/执行器关闭，安排延迟重试: " + rejected.getMessage());
            if (!serviceDestroyed) handler.postDelayed(() -> {
                if (!serviceDestroyed) sendReport(withNearbyWifi, force, overrideUrl);
            }, 6000L);
        }
    }


    private String getNetworkTypeDesc(NetworkInfo activeNet) {
        String typeName = activeNet.getTypeName();
        if (ConnectivityManager.TYPE_MOBILE != activeNet.getType()) return typeName;
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return typeName;
            int subType = tm.getNetworkType();
            String sub;
            switch (subType) {
                case TelephonyManager.NETWORK_TYPE_NR: sub = "5G"; break;
                case TelephonyManager.NETWORK_TYPE_LTE: sub = "4G"; break;
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                    sub = "3G"; break;
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    sub = "2G"; break;
                default: sub = String.valueOf(subType); break;
            }
            return "MOBILE(" + sub + ")";
        } catch (Exception e) { return typeName; }
    }

    private String getWifiBroadcastAddress() {
        // Android 6+ uses the exact interface belonging to the Network that owns our socket.
        // Do not guess by wlan/ap prefix: OEM interface names and multiple Wi-Fi candidates vary.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                // Prefer the exact Network already bound to the listener socket; this
                // prevents a default-route callback racing broadcast selection onto another NIC.
                Network selected = lanChatBoundWifiNetwork;
                if (selected == null) selected = selectWifiLanNetwork(cm);
                if (selected == null) return "255.255.255.255";
                LinkProperties links = cm.getLinkProperties(selected);
                if (links == null || links.getInterfaceName() == null) {
                    return "255.255.255.255";
                }
                java.net.NetworkInterface intf =
                        java.net.NetworkInterface.getByName(links.getInterfaceName());
                if (intf == null || !intf.isUp() || intf.isLoopback()) {
                    return "255.255.255.255";
                }
                for (java.net.InterfaceAddress interfaceAddress : intf.getInterfaceAddresses()) {
                    java.net.InetAddress local = interfaceAddress.getAddress();
                    java.net.InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (local instanceof java.net.Inet4Address && !local.isLoopbackAddress()
                            && broadcast instanceof java.net.Inet4Address) {
                        return broadcast.getHostAddress();
                    }
                }
                return "255.255.255.255";
            }
            // Pre-M fallback: use the platform's Wi-Fi interface broadcast, without assuming /24.
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface intf = interfaces.nextElement();
                String name = intf.getName();
                if (!intf.isUp() || intf.isLoopback()
                        || !(name.startsWith("wlan") || name.startsWith("ap"))) continue;
                for (java.net.InterfaceAddress interfaceAddress : intf.getInterfaceAddresses()) {
                    java.net.InetAddress local = interfaceAddress.getAddress();
                    java.net.InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (local instanceof java.net.Inet4Address && !local.isLoopbackAddress()
                            && broadcast instanceof java.net.Inet4Address) {
                        return broadcast.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            FileLogger.logD("CHAT", "读取 Wi-Fi 接口广播地址失败: " + e.getClass().getSimpleName());
        }
        return "255.255.255.255";
    }

    private Network bindSocketToWifi(java.net.DatagramSocket socket) throws java.io.IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            // Preserve the pre-existing default-route listener behavior for non-chat UDP.
            return null;
        }
        Network selected = selectWifiLanNetwork(cm);
        if (selected == null) {
            // No Wi-Fi transport is currently visible; retain the platform default route
            // for existing non-chat UDP business paths. LAN chat itself fails closed.
            return null;
        }
        LinkProperties links = cm.getLinkProperties(selected);
        if (links == null || links.getInterfaceName() == null
                || links.getInterfaceName().isEmpty()
                || links.getLinkAddresses() == null || links.getLinkAddresses().isEmpty()) {
            throw new java.io.IOException("Selected Wi-Fi network has incomplete link properties");
        }
        try {
            selected.bindSocket(socket);
            FileLogger.logD("CHAT", "UDP listener socket bound to selected Wi-Fi LAN network="
                    + links.getInterfaceName());
            return selected;
        } catch (Exception bindError) {
            throw new java.io.IOException("Wi-Fi LAN socket binding failed", bindError);
        }
    }

    /**
     * v2.0 补丁：统计离线队列当前条数（本次上报前快照）。
     * 返回 -1 表示读取失败（服务端会把 -1 视为队列状态未知 → 保守降级）。
     */
    private int countPendingReports() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return -1;
            java.io.File qf = new java.io.File(dir, PENDING_REPORTS_FILE);
            if (!qf.exists()) return 0;
            int lines = 0;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(qf));
            try {
                while (br.readLine() != null) lines++;
            } finally {
                try { br.close(); } catch (Exception ignored) {}
            }
            return lines;
        } catch (Exception e) {
            return -1;
        }
    }

    // ★ 2026-11-20 补丁4：离线入队升级为“静止防抖合并”——15 分钟窗口内、位移<50米、电量变化<=2%、
    //   且无指令回执时，覆盖最后一条静止记录；一旦发生位移或带回执则追加新行，保证移动轨迹不丢失。
    //   注意：上报体中定位嵌套在 location.latitude/longitude、电量嵌套在 battery.level，须按嵌套路径读取，
    //   不能按补丁原文的顶层 lat/lng/battery 读取（否则位移恒判为 0，会误覆盖丢失轨迹）。

    /** 原子替换队列文件：先写同目录临时文件，再 rename，避免截断重写导致断电损坏。 */
    private void writeQueueAtomically(java.io.File queueFile, List<String> lines) throws Exception {
        java.io.File tmp = new java.io.File(queueFile.getParentFile(), queueFile.getName() + ".tmp");
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp, false), "UTF-8"))) {
            for (String line : lines) { writer.write(line); writer.newLine(); }
            writer.flush();
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp, true)) { fos.getFD().sync(); }
        if (!tmp.renameTo(queueFile)) {
            // ★ 2026-11-20 [低危3] rename 失败（跨文件系统 / 权限 / 瞬时 IO 抖动）时：
            //   ① 绝不用空文件覆盖旧队列（原语义保留）；② 顺手清理 .tmp 残留，避免垃圾文件堆积；
            //   ③ 抛出后由调用方决定降级策略（入队路径降级为追加写，补发路径留待下一轮重试）。
            try { tmp.delete(); } catch (Exception ignored) {}
            throw new java.io.IOException("队列原子重命名失败: " + tmp.getAbsolutePath());
        }
    }

    private List<String> enforceQueueLimits(List<String> source) {
        List<String> kept = new ArrayList<>();
        long bytes = 0L;
        // ★ 2026-11-20 [低危4] 修复：原先三类超限一律静默 continue，事后无法解释「记录为什么少了」。
        //   判定顺序与语义完全不变（仍是「从最新往旧扫、超限跳过该行而非中断」），仅补分类计数与留痕日志。
        int droppedByCount = 0;      // 条数超上限（300 条）
        int droppedOversize = 0;     // 单行本身超过队列字节预算
        int droppedByBudget = 0;     // 累计字节超预算
        for (int i = source.size() - 1; i >= 0; i--) {
            String line = source.get(i);
            long lineBytes = line.getBytes(java.nio.charset.Charset.forName("UTF-8")).length + 1L;
            if (kept.size() >= 300) { droppedByCount++; continue; }
            if (lineBytes > PENDING_QUEUE_MAX_BYTES) { droppedOversize++; continue; }
            if (bytes + lineBytes > PENDING_QUEUE_MAX_BYTES) { droppedByBudget++; continue; }
            kept.add(0, line);
            bytes += lineBytes;
        }
        int dropped = droppedByCount + droppedOversize + droppedByBudget;
        if (dropped > 0) {
            FileLogger.log(this, "REPORT", "⚠️ 离线队列超限裁剪：丢弃 " + dropped + " 条"
                    + "（条数超上限 " + droppedByCount + " / 单行超预算 " + droppedOversize
                    + " / 总量超预算 " + droppedByBudget + "），保留最新 " + kept.size() + " 条 / "
                    + bytes + "B（预算 " + PENDING_QUEUE_MAX_BYTES + "B）");
        }
        return kept;
    }

    private void queuePendingReport(JSONObject json) {
        if (json == null) return;
        // ★ [修复] 2026-11-04 并发保护：写入文件必须与 flushPendingReports 互斥
        synchronized (FILE_LOCK) {
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) return;
                java.io.File queueFile = new java.io.File(dir, PENDING_REPORTS_FILE);

                java.util.List<String> lines = new java.util.ArrayList<>();
                if (queueFile.exists() && queueFile.length() > 0) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(queueFile), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.trim().isEmpty()) lines.add(line);
                    }
                    br.close();
                }

                JSONObject queuedJson = new JSONObject(json.toString());
                if (!queuedJson.has("_queue_id")) queuedJson.put("_queue_id", java.util.UUID.randomUUID().toString());
                String jsonLine = queuedJson.toString();
                boolean merged = false;
                if (!lines.isEmpty()) {
                    try {
                        JSONObject lastObj = new JSONObject(lines.get(lines.size() - 1));
                        JSONObject currObj = json;

                        long lastTs = lastObj.optLong("timestamp", 0L);
                        long currTs = currObj.optLong("timestamp", System.currentTimeMillis());
                        // 兼容秒级与毫秒级时间戳
                        long diffMs = (currTs > 10_000_000_000L ? currTs - lastTs : (currTs - lastTs) * 1000L);

                        JSONObject lastLoc = lastObj.optJSONObject("location");
                        JSONObject currLoc = currObj.optJSONObject("location");
                        double lastLat = lastLoc != null ? lastLoc.optDouble("latitude", 0.0) : 0.0;
                        double lastLng = lastLoc != null ? lastLoc.optDouble("longitude", 0.0) : 0.0;
                        double currLat = currLoc != null ? currLoc.optDouble("latitude", 0.0) : 0.0;
                        double currLng = currLoc != null ? currLoc.optDouble("longitude", 0.0) : 0.0;

                        boolean sameLocation = false;
                        if (lastLat == 0.0 && lastLng == 0.0 && currLat == 0.0 && currLng == 0.0) {
                            sameLocation = true;                      // 双端均无有效定位（关机/无 fix），视为静止
                        } else if (lastLat != 0.0 && currLat != 0.0) {
                            float[] results = new float[1];
                            android.location.Location.distanceBetween(lastLat, lastLng, currLat, currLng, results);
                            sameLocation = results[0] < OFFLINE_MERGE_DIST_M;
                        } // else 一端有定位一端无 -> 判为发生位移，不合并

                        JSONObject lastBat = lastObj.optJSONObject("battery");
                        JSONObject currBat = currObj.optJSONObject("battery");
                        int lastBatLvl = lastBat != null ? lastBat.optInt("level", 0) : 0;
                        int currBatLvl = currBat != null ? currBat.optInt("level", 0) : 0;
                        int batDiff = Math.abs(currBatLvl - lastBatLvl);

                        JSONArray ackIds = currObj.optJSONArray("last_executed_cmd_ids");
                        boolean hasCmdAck = (ackIds != null && ackIds.length() > 0);
                        // ★ 2026-11-20 [C3] 修复：合并判定原先只检查「新行」有没有回执，没检查「被覆盖的旧行」。
                        //   旧行带回执、新行无回执时 lines.set() 会把回执直接覆盖掉 → 服务端指令滞留队列直到 TTL
                        //   （展示类 2 小时、其它 24 小时），控制台一直显示「待发」。旧行有回执同样禁止合并。
                        JSONArray lastAckIds = lastObj.optJSONArray("last_executed_cmd_ids");
                        boolean lastHasCmdAck = (lastAckIds != null && lastAckIds.length() > 0);

                        // 15分钟内 + 位移<50米 + 电量变化<=2% + 新旧两行均无指令回执 => 覆盖最后一条静止记录
                        if (diffMs >= 0 && diffMs < OFFLINE_MERGE_WINDOW_MS && sameLocation && batDiff <= 2
                                && !hasCmdAck && !lastHasCmdAck) {
                            lines.set(lines.size() - 1, jsonLine);
                            merged = true;
                        }
                    } catch (Exception ignored) {
                        // 解析异常则安全回退为追加模式
                    }
                }

                if (!merged) lines.add(jsonLine);

                // 同时严格限制条数和 UTF-8 实际字节数；保留最新记录。
                lines = enforceQueueLimits(lines);
                int finalCount = lines.size();
                try {
                    writeQueueAtomically(queueFile, lines);
                } catch (Exception rewriteError) {
                    // ★ 2026-11-20 [低危3] 修复：原实现把 renameTo 失败一路抛到外层 catch，
                    //   结果是「旧队列保住了、本次新记录直接丢失」且只有一条笼统失败日志。
                    //   现降级为对原文件追加写：宁可本轮少一次裁剪/合并，也绝不丢点。
                    FileLogger.log(this, "REPORT", "⚠️ 队列原子重写失败(" + rewriteError.getMessage()
                            + ")，降级为追加写保数据");
                    try (java.io.BufferedWriter appender = new java.io.BufferedWriter(
                            new java.io.OutputStreamWriter(new java.io.FileOutputStream(queueFile, true), "UTF-8"))) {
                        appender.write(jsonLine);
                        appender.newLine();
                        appender.flush();
                        finalCount = lines.size() + 1;   // 旧行仍在 + 本次追加 1 行
                    } catch (Exception appendError) {
                        FileLogger.log(this, "REPORT", "❌ 降级追加写同样失败，本次记录丢失: " + appendError.getMessage());
                        throw appendError;
                    }
                }

                long kb = Math.max(1, queueFile.length() / 1024);
                FileLogger.log(this, "REPORT", (merged ? "♻️ 离线静止合并更新" : "📥 离线排队一条上报")
                        + "（队列 " + kb + "KB，共 " + finalCount + " 条）");
            } catch (Exception e) {
                FileLogger.log(this, "REPORT", "❌ 离线排队写入失败: " + e.getMessage());
            }
        }
    }

    // ★ 2026-11-20 [低危5] 原 truncateQueueFile(File, int) 已移除：全文件无任何调用点（纯死代码），
    //   而文件头架构说明第 6 条仍宣称「队列裁剪 (truncateQueueFile) 被纳入 FILE_LOCK 矩阵」，注释与实现不符。
    //   队列裁剪职责实际由 enforceQueueLimits 承担（入队时按「300 条 + PENDING_QUEUE_MAX_BYTES」双上限
    //   保留最新记录，本轮并已补齐裁剪留痕日志），同样在 FILE_LOCK 内执行；架构说明第 6 条已同步校正。

    // ==================== [离线补发：批量优先 + 逐条降级] ====================
    // ★ 2026-11-20 新增：弱网恢复时优先把多条离线记录打包成一次 /api/report_batch 往返，
    //   批量接口不可用（404/405/501）或部分接收时自动回退到原有 postReportDirectly 逐条补发，
    //   保证向后兼容。复用 reportExecutor 线程池 + flushInFlight 防重入 + 连续失败熔断。

    private String buildReportBatchUrl(String baseUrl) {
        if (!isValidUrl(baseUrl)) return null;
        try {
            android.net.Uri uri = android.net.Uri.parse(baseUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) return null;
            int port = uri.getPort();
            String authority = port > 0 ? (host + ":" + port) : host;
            return scheme + "://" + authority + "/api/report_batch";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 批量上报结果封装：
     * - statusCode == 200 且解析成功时视为批量接口可用
     * - statusCode == 404 / 405 / 501 表示服务端未实现 /api/report_batch，需降级逐条补发
     */
    private static final class BatchPostResult {
        final int httpCode;
        final boolean endpointUnsupported;
        final int acceptedCount;
        final JSONObject responseJson;

        BatchPostResult(int httpCode, boolean endpointUnsupported, int acceptedCount, JSONObject responseJson) {
            this.httpCode = httpCode;
            this.endpointUnsupported = endpointUnsupported;
            this.acceptedCount = acceptedCount;
            this.responseJson = responseJson;
        }
    }

    private BatchPostResult postReportBatchDirectly(List<JSONObject> batchItems, String batchUrl) {
        HttpURLConnection conn = null;
        try {
            if (batchUrl == null || batchUrl.isEmpty() || batchItems == null || batchItems.isEmpty()) {
                return new BatchPostResult(-1, true, 0, null);
            }

            JSONArray arr = new JSONArray();
            for (JSONObject item : batchItems) {
                if (item != null) arr.put(item);
            }
            if (arr.length() == 0) {
                return new BatchPostResult(-1, true, 0, null);
            }

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("token", AUTH_TOKEN);
            bodyJson.put("device_id", getUniqueDeviceId());
            bodyJson.put("protocol_version", BuildInfo.PROTOCOL_VERSION);
            bodyJson.put("build_version", BuildInfo.BUILD_VERSION);
            bodyJson.put("reports", arr);

            byte[] payloadBytes = bodyJson.toString().getBytes("UTF-8");

            URL url = new URL(batchUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(8000);   // [P0-3] 快速失败，防弱网长时间阻塞
            conn.setReadTimeout(10000);     // 批量包略放宽读超时
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payloadBytes.length);
            conn.setRequestProperty("Connection", "close");

            OutputStream os = conn.getOutputStream();
            os.write(payloadBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            boolean unsupported = (code == 404 || code == 405 || code == 501);

            if (code == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject resJson;
                int accepted = 0;
                try {
                    resJson = new JSONObject(sb.toString());
                    // 只有服务端明确声明“按顺序接受前 N 条”才允许按数量确认。
                    // 若服务端返回 accepted_ids，则在 flush 阶段逐条匹配；本兼容客户端不猜测其语义。
                    if (resJson.optBoolean("accepted_is_prefix", false) && resJson.has("accepted")) {
                        accepted = Math.max(0, Math.min(arr.length(), resJson.optInt("accepted", 0)));
                    }
                } catch (Exception parseError) {
                    // HTTP 200 但响应不可解析：不能据此删除任何本地记录。
                    return new BatchPostResult(code, false, 0, null);
                }
                return new BatchPostResult(code, false, accepted, resJson);
            }

            return new BatchPostResult(code, unsupported, 0, null);
        } catch (Exception e) {
            return new BatchPostResult(-1, false, 0, null);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private String queueEntryIdentity(String raw, java.util.Map<String, Integer> occurrences) {
        try {
            JSONObject obj = new JSONObject(raw);
            String id = obj.optString("_queue_id", "");
            if (!id.isEmpty()) return "id:" + id;
        } catch (Exception ignored) {
            // 损坏行按原始字节内容和出现序号标识，允许精准跳过且不误删其他行。
        }
        int occurrence = occurrences.containsKey(raw) ? occurrences.get(raw) : 0;
        occurrences.put(raw, occurrence + 1);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b));
            return "legacy:" + hex + ":" + occurrence;
        } catch (Exception e) {
            return "legacy:" + raw.hashCode() + ":" + occurrence;
        }
    }

    private void flushPendingReports() {
        if (serviceDestroyed) return;
        if (!flushInFlight.compareAndSet(false, true)) {
            FileLogger.log(this, "REPORT", "⏭ 离线补发正在进行，跳过重复触发");
            return;
        }

        try {
            reportExecutor.execute(() -> {
                // ★ 2026-11-20 [B1] 本轮是否仍有未送出的残留（供 finally 里自我续跑判断）
                final boolean[] hasMorePending = {false};
                try {
                    synchronized (FLUSH_LOCK) {
                        final List<String> snapshotLines = new ArrayList<>();
                        synchronized (FILE_LOCK) {
                            java.io.File dir = getExternalFilesDir(null);
                            if (dir == null) return;
                            java.io.File queueFile = new java.io.File(dir, PENDING_REPORTS_FILE);
                            if (!queueFile.exists() || queueFile.length() == 0) return;
                            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                                    new java.io.FileInputStream(queueFile), "UTF-8"))) {
                                String line;
                                while ((line = br.readLine()) != null) if (!line.trim().isEmpty()) snapshotLines.add(line);
                            }
                        }
                        if (snapshotLines.isEmpty()) return;

                        final java.util.Map<String, Integer> snapshotOccurrences = new java.util.HashMap<>();
                        final List<String> snapshotKeys = new ArrayList<>();
                        final List<String> corruptKeys = new ArrayList<>();
                        final List<JSONObject> validItems = new ArrayList<>();
                        final List<String> validKeys = new ArrayList<>();
                        int limit = Math.min(snapshotLines.size(), FLUSH_BATCH_MAX);
                        for (int i = 0; i < limit; i++) {
                            String raw = snapshotLines.get(i);
                            String key = queueEntryIdentity(raw, snapshotOccurrences);
                            snapshotKeys.add(key);
                            try {
                                JSONObject obj = new JSONObject(raw);
                                if (!obj.has("token")) obj.put("token", AUTH_TOKEN);
                                if (!obj.has("device_id")) obj.put("device_id", getUniqueDeviceId());
                                validItems.add(obj);
                                validKeys.add(key);
                            } catch (Exception malformed) {
                                corruptKeys.add(key);
                            }
                        }

                        java.util.Set<String> removeKeys = new java.util.HashSet<>(corruptKeys);
                        int sent = 0;
                        boolean needFallback = false;
                        String serverUrl = getCurrentServerUrl();
                        String batchUrl = buildReportBatchUrl(serverUrl);
                        if (!validItems.isEmpty() && batchUrl != null) {
                            BatchPostResult batch = postReportBatchDirectly(validItems, batchUrl);
                            if (batch.httpCode == 200 && batch.acceptedCount > 0) {
                                // acceptedCount 仅在服务端显式 accepted_is_prefix=true 时才有意义。
                                for (int i = 0; i < batch.acceptedCount; i++) removeKeys.add(validKeys.get(i));
                                sent += batch.acceptedCount;
                                if (batch.responseJson != null) {
                                    int feedbackExpected = 0;
                                    for (int i = 0; i < batch.acceptedCount; i++) {
                                        JSONArray f = validItems.get(i).optJSONArray("command_feedback");
                                        if (f != null) feedbackExpected += f.length();
                                    }
                                    if (feedbackExpected > 0 && batch.responseJson.optInt("feedback_accepted", 0) >= feedbackExpected) {
                                        for (int i = 0; i < batch.acceptedCount; i++) ackCommandFeedbackIfConfirmed(validItems.get(i), batch.responseJson);
                                    }

                                    // ★ 2026-11-20 [B3] 修复：下行响应解析留在本工作线程，与单条上报路径
                                    //   （postReportDirectly 内直接 parseResponse）保持一致。parseResponse 会同步
                                    //   执行 dispatchHttpCommand —— 内含 clearMessageHistory()/saveChatToHistory()
                                    //   （SharedPreferences + 文件 IO）、LauncherIconUtils 组件开关（PackageManager
                                    //   binder 慢调用）、startLocationUpdates()；原先 handler.post 到主线程有 ANR 风险。
                                    //   其中真正需要主线程的动作（startLocationUpdates / scheduleNextReport）
                                    //   在 parseResponse 内部已自行 handler.post，无需在此上抛。
                                    try {
                                        parseResponse(batch.responseJson);
                                    } catch (Exception respError) {
                                        FileLogger.log(TrackingService.this, "REPORT",
                                                "⚠️ 批量补发成功，但下行响应解析失败: " + respError.getMessage());
                                    }
                                }
                                needFallback = batch.acceptedCount < validItems.size();
                            } else {
                                needFallback = true;
                            }
                        } else if (!validItems.isEmpty()) {
                            needFallback = true;
                        }

                        if (needFallback) {
                            int failStreak = 0;
                            for (int i = 0; i < validItems.size(); i++) {
                                if (removeKeys.contains(validKeys.get(i))) continue;
                                if (postReportDirectly(validItems.get(i), serverUrl)) {
                                    removeKeys.add(validKeys.get(i));
                                    sent++;
                                    failStreak = 0;
                                } else if (++failStreak >= FLUSH_MAX_CONSECUTIVE_FAILS) {
                                    break;
                                }
                            }
                        }

                        if (sent > 0) {
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                                    .putLong(PREF_LAST_SUCCESS_REPORT_TIME, System.currentTimeMillis()).apply();

                            // ★ 2026-11-20 [低危1] 补发路径同样回收已确认回执：本轮被服务端接收（进 removeKeys）
                            //   的那些行里携带的 last_executed_cmd_ids，服务端已处理，可从本地集合删除。
                            java.util.Set<String> ackedIds = new java.util.LinkedHashSet<>();
                            for (int i = 0; i < validItems.size(); i++) {
                                if (!removeKeys.contains(validKeys.get(i))) continue;
                                JSONArray ackArr = validItems.get(i).optJSONArray("last_executed_cmd_ids");
                                if (ackArr == null) continue;
                                for (int j = 0; j < ackArr.length(); j++) {
                                    String ackId = ackArr.optString(j, "");
                                    if (!ackId.isEmpty()) ackedIds.add(ackId);
                                }
                            }
                            if (!ackedIds.isEmpty()) removeExecutedCmdIds(ackedIds);
                        }

                        // 按稳定 ID/原始行身份精确删除本轮已确认项；保留失败项和补发期间新追加的行。
                        synchronized (FILE_LOCK) {
                            java.io.File dir = getExternalFilesDir(null);
                            if (dir == null) return;
                            java.io.File queueFile = new java.io.File(dir, PENDING_REPORTS_FILE);
                            if (!queueFile.exists()) return;
                            List<String> currentLines = new ArrayList<>();
                            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                                    new java.io.FileInputStream(queueFile), "UTF-8"))) {
                                String line;
                                while ((line = br.readLine()) != null) if (!line.trim().isEmpty()) currentLines.add(line);
                            }
                            java.util.Map<String, Integer> currentOccurrences = new java.util.HashMap<>();
                            List<String> retained = new ArrayList<>();
                            int removed = 0;
                            for (String raw : currentLines) {
                                String key = queueEntryIdentity(raw, currentOccurrences);
                                if (removeKeys.contains(key)) removed++;
                                else retained.add(raw);
                            }
                            if (removed > 0) {
                                if (retained.isEmpty()) {
                                    if (!queueFile.delete()) writeQueueAtomically(queueFile, retained);
                                } else {
                                    writeQueueAtomically(queueFile, retained);
                                }
                            }
                            // ★ 2026-11-20 [B1] 修复：FLUSH_BATCH_MAX=30，而 flushPendingReports 只有网络
                            //   onAvailable 与自身拒绝重试两个触发点，上报成功/入队/定时任务里都不补发。
                            //   离线攒了上百条时网络恢复一次只送 30 条，剩余 70 条要等「下一次网络状态变化」，
                            //   网络长期稳定时可能几小时不补。本轮有实际进展(sent>0)且仍有残留时标记续跑。
                            //   仅在 sent>0 时续跑：全失败（仍离线）时不空转烧电，交由 onAvailable / 上报失败路径重试。
                            hasMorePending[0] = (sent > 0) && (!retained.isEmpty() || snapshotLines.size() > limit);
                            FileLogger.log(TrackingService.this, "REPORT", "📤 离线队列补发: 成功 " + sent
                                    + " 条，清理损坏 " + corruptKeys.size() + " 条，精确移除 " + removed
                                    + " 条，剩余 " + retained.size() + " 条");
                        }
                    }
                } catch (Exception e) {
                    FileLogger.log(TrackingService.this, "REPORT", "❌ 离线队列补发异常: " + e.getMessage());
                } finally {
                    flushInFlight.set(false);
                    // ★ 2026-11-20 [B1] 修复：队列未清空且本轮有进展 → 3 秒后自动续跑下一批，
                    //   直到队列清空（每轮最多 FLUSH_BATCH_MAX 条），不再依赖网络状态变化才继续补发。
                    if (hasMorePending[0] && !serviceDestroyed) {
                        FileLogger.log(TrackingService.this, "REPORT", "⏩ 离线队列仍有残留，"
                                + (FLUSH_CONTINUE_DELAY_MS / 1000L) + " 秒后自动续跑下一批");
                        handler.postDelayed(() -> {
                            if (!serviceDestroyed) flushPendingReports();
                        }, FLUSH_CONTINUE_DELAY_MS);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            flushInFlight.set(false);
            FileLogger.log(this, "REPORT", "⚠️ 离线补发任务未入队，安排延迟重试: " + rejected.getMessage());
            if (!serviceDestroyed) handler.postDelayed(() -> {
                if (!serviceDestroyed) flushPendingReports();
            }, 5000L);
        }
    }

    private boolean postReportDirectly(JSONObject json, String serverUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(serverUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(8000);   // [P0-3] 15000 → 8000，离线队列补发不再一条卡 15 秒
            conn.setReadTimeout(8000);      // [P0-3] 51 条 × 8 秒 = 408 秒，比旧版 51 × 15 = 765 秒省一半
            conn.setDoOutput(true);
            conn.setRequestProperty("Connection", "close");
            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code == 200) {
                // ★ [核心优化] 2026-11-04 补发成功也更新持久化时间
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                        .edit().putLong(PREF_LAST_SUCCESS_REPORT_TIME, System.currentTimeMillis()).apply();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject response = new JSONObject(sb.toString());
                    ackCommandFeedbackIfConfirmed(json, response);
                } catch (Exception responseError) {
                    // HTTP 已接收但反馈确认字段缺失/解析失败：保留本地反馈，下次继续幂等补交。
                    FileLogger.log(this, "HTTP_CMD", "ℹ️ 离线补发成功但反馈确认不可读，保留 command_feedback");
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    private void uploadLog(final String tempUrl, final boolean clearAfter) {
        new Thread(() -> {
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) {
                    FileLogger.log(this, "UPLOAD_LOG", "❌ getExternalFilesDir 为 null");
                    return;
                }

                java.io.File[] logFiles = dir.listFiles((d, name) ->
                        name.equals("device_log.txt") || (name.startsWith("device_log_") && name.endsWith(".txt")));
                if (logFiles == null || logFiles.length == 0) {
                    FileLogger.log(this, "UPLOAD_LOG", "⚠️ 没有找到任何日志文件");
                    return;
                }
                java.util.Arrays.sort(logFiles, (f1, f2) -> {
                    String n1 = f1.getName(); String n2 = f2.getName();
                    if (n1.equals("device_log.txt")) return 1;
                    if (n2.equals("device_log.txt")) return -1;
                    return n1.compareTo(n2);
                });

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gzos = new GZIPOutputStream(baos);
                long totalOriginalSize = 0;
                for (java.io.File file : logFiles) {
                    if (file.length() == 0) continue;
                    totalOriginalSize += file.length();
                    String header = "\n\n========== File: " + file.getName() + " ==========\n";
                    gzos.write(header.getBytes("UTF-8"));
                    FileInputStream in = null;
                    try {
                        in = new FileInputStream(file);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) gzos.write(buf, 0, n);
                    } finally {
                        if (in != null) try { in.close(); } catch (Exception ignored) {}
                    }
                }
                gzos.close();
                byte[] gzipData = baos.toByteArray();

                String baseUrl = (tempUrl != null && !tempUrl.isEmpty()) ? tempUrl : getCurrentServerUrl();
                FileLogger.log(this, "UPLOAD_LOG", "📤 baseUrl=[" + baseUrl + "] (tempUrl=[" + tempUrl + "])");

                String uploadUrl = buildApiUrl(baseUrl, "upload_log");
                if (uploadUrl == null) {
                    FileLogger.log(this, "UPLOAD_LOG", "❌ baseUrl 非法，无法拼接上传 URL: [" + baseUrl + "]");
                    return;
                }

                String deviceId = getUniqueDeviceId();
                String fullUrl = uploadUrl + (uploadUrl.contains("?") ? "&" : "?")
                        + "token=" + java.net.URLEncoder.encode(AUTH_TOKEN, "UTF-8")
                        + "&device_id=" + java.net.URLEncoder.encode(deviceId, "UTF-8");

                FileLogger.log(this, "UPLOAD_LOG", "📤 开始上传 " + logFiles.length + " 个日志文件: "
                        + (totalOriginalSize / 1024) + "KB → 压缩后 " + (gzipData.length / 1024) + "KB → " + uploadUrl);

                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/gzip");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(gzipData.length);
                conn.setRequestProperty("Connection", "close");

                OutputStream os = conn.getOutputStream();
                os.write(gzipData);
                os.flush();
                os.close();

                long start = System.currentTimeMillis();
                int code = conn.getResponseCode();
                long cost = System.currentTimeMillis() - start;

                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    FileLogger.log(this, "UPLOAD_LOG", "✅ 上传成功 (耗时 " + cost + "ms): " + sb.toString());
                    if (clearAfter) {
                        int deleted = 0;
                        for (java.io.File file : logFiles) if (file.delete()) deleted++;
                        FileLogger.log(this, "UPLOAD_LOG", "🧹 本地日志已清空 (" + deleted + "/" + logFiles.length + ")");
                    }
                } else {
                    FileLogger.log(this, "UPLOAD_LOG", "⚠️ 上传失败: HTTP " + code + " (耗时 " + cost + "ms)");
                }
                conn.disconnect();
            } catch (Exception e) {
                FileLogger.log(this, "UPLOAD_LOG", "❌ 上传异常: " + e.getMessage());
            }
        }).start();
    }

    private void fetchRemoteConfig() {
        String configUrl = getRemoteConfigUrl();
        if (configUrl.contains("your-config-url.com")) return;
        long now = System.currentTimeMillis();
        if (now - lastConfigFetchTime < 10 * 60 * 1000L) return;
        lastConfigFetchTime = now;
        new Thread(() -> {
            try {
                URL url = new URL(configUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Connection", "close");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    String fetchedText = sb.toString().trim();
                    if (fetchedText.startsWith("MSG:")) showFloatingMessage(fetchedText.substring(4).trim());
                    else if (fetchedText.startsWith("http") && !fetchedText.equals(getCurrentServerUrl())) {
                        saveNewServerUrl(fetchedText); consecutiveFailures = 0; sendReport(true, true);
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private Location getBetterLocation(Location l1, Location l2) {
        if (l1 == null) return l2; if (l2 == null) return l1;
        long timeDelta = l2.getTime() - l1.getTime();
        boolean isSignificantlyNewer = timeDelta > 120000;
        boolean isSignificantlyOlder = timeDelta < -120000;
        boolean isNewer = timeDelta > 0;
        if (isSignificantlyNewer) return l2; else if (isSignificantlyOlder) return l1;
        int accuracyDelta = (int) (l2.getAccuracy() - l1.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;
        boolean isFromSameProvider = l2.getProvider() != null && l2.getProvider().equals(l1.getProvider());
        if (isMoreAccurate) return l2;
        else if (isNewer && !isLessAccurate) return l2;
        else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return l2;
        return l1;
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.contains("://")) return false;
        return url.length() >= 10;
    }

    private String buildApiUrl(String baseUrl, String apiName) {
        if (!isValidUrl(baseUrl)) return null;
        try {
            android.net.Uri uri = android.net.Uri.parse(baseUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) return null;
            int port = uri.getPort();
            String authority = port > 0 ? (host + ":" + port) : host;
            return scheme + "://" + authority + "/api/" + apiName;
        } catch (Exception e) { return null; }
    }

    /**
     * ★ 强力补丁 5：消除无状态延迟闭包，彻底堵死 UDP 线程泄漏与 fd 爆炸。
     * 通过固定 Message.what + removeMessages 前置清空，保证同一时刻队列中
     * 最多只有一个待执行的重试任务，避免网络风暴下闭包堆积。
     */
    private void triggerRescueListener() {
        if (serviceDestroyed) return;
        boolean alreadyListening;
        synchronized (rescueLock) {
            alreadyListening = isListeningForRescue;
        }

        if (alreadyListening) {
            // Coalesce listener recovery on the LAN scheduler, not the main Looper.
            if (rescueListenerRetryScheduled.compareAndSet(false, true)) {
                boolean scheduled = scheduleLanChatTask("rescue-listener-retry", () -> {
                    rescueListenerRetryScheduled.set(false);
                    boolean still;
                    synchronized (rescueLock) {
                        still = isListeningForRescue;
                    }
                    if (!still && !serviceDestroyed) startRescueListener();
                }, 8000L);
                if (!scheduled) rescueListenerRetryScheduled.set(false);
            }
            return;
        }
        startRescueListener();
    }

    /** 新消息在独立有界即时队列中按顺序完成持久化和发送，UI 入口保持原样。 */
    private void sendLocalUdpMessage(final String message) {
        final String messageId = java.util.UUID.randomUUID().toString();
        try {
            // Reject an oversized UDP text before creating a durable pending row that can never be sent.
            buildAuthenticatedLanChatPacket(getUniqueDeviceId(), "*", messageId, message);
        } catch (Exception tooLargeOrInvalid) {
            FileLogger.log(this, "CHAT", "⚠️ LAN 对讲消息超过单个 UDP 数据包限制，未入队: "
                    + tooLargeOrInvalid.getClass().getSimpleName());
            return;
        }
        final long enqueueAt = android.os.SystemClock.elapsedRealtime();
        try {
            lanChatImmediateExecutor.execute(() -> {
                if (serviceDestroyed) return;
                long queueWaitMs = android.os.SystemClock.elapsedRealtime() - enqueueAt;
                long persistStarted = android.os.SystemClock.elapsedRealtime();
                if (!persistPendingLanChat(messageId, message)) {
                    FileLogger.log(this, "CHAT", "⚠️ LAN 消息未能持久化，已阻止发送以免断网丢失");
                    return;
                }
                long persistMs = android.os.SystemClock.elapsedRealtime() - persistStarted;
                FileLogger.log(this, "CHAT", "⏱️ P2P 排队耗时 " + queueWaitMs
                        + "ms，持久化耗时 " + persistMs + "ms，开始发送 " + messageId);
                sendLocalUdpMessage(message, messageId);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            FileLogger.log(this, "CHAT", "⚠️ LAN 发送队列已满，未接受新消息: " + messageId);
        }
    }

    private boolean persistPendingLanChat(String messageId, String content) {
        try {
            LanChatStore store = lanChatStore;
            if (store == null) {
                FileLogger.log(this, "CHAT", "⚠️ LAN SQLite 账本未就绪，消息未接受");
                return false;
            }
            long createdAt = System.currentTimeMillis();
            boolean persisted = store.enqueueWithRecipientSnapshot(messageId, getUniqueDeviceId(),
                    content, createdAt, verifiedChatPeers);
            if (!persisted) {
                FileLogger.log(this, "CHAT", "⚠️ LAN SQLite 原子写入消息及固定收件人快照失败");
            }
            return persisted;
        } catch (Exception e) {
            FileLogger.log(this, "CHAT", "⚠️ LAN 聊天原子持久化异常: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Freeze historically known members at message creation. If none are known,
     * leave the bounded first-discovery window open so newly verified peers can join it.
     */
    private boolean initializeLanChatRecipientSnapshot(LanChatStore store, String messageId) {
        if (store == null || messageId == null || messageId.isEmpty()) return false;
        if (!store.isDiscoveryPending(messageId)) return true;
        List<LanChatStore.Target> snapshot = store.loadKnownPeers();
        java.util.Set<String> snapshotIds = new java.util.HashSet<>();
        for (LanChatStore.Target target : snapshot) {
            if (target != null && target.peerId != null) snapshotIds.add(target.peerId);
        }
        for (java.util.Map.Entry<String, java.net.InetSocketAddress> entry : verifiedChatPeers.entrySet()) {
            if (snapshotIds.add(entry.getKey())) {
                java.net.InetSocketAddress endpoint = entry.getValue();
                snapshot.add(new LanChatStore.Target(entry.getKey(), "pending", 0,
                        endpoint.getAddress().getHostAddress(), endpoint.getPort()));
            }
        }
        for (java.util.Iterator<LanChatStore.Target> it = snapshot.iterator(); it.hasNext();) {
            LanChatStore.Target target = it.next();
            if (target == null || target.peerId == null
                    || getUniqueDeviceId().equals(target.peerId)) it.remove();
        }
        return snapshot.isEmpty() || store.setTargets(messageId, snapshot);
    }

    private void removePendingLanChat(String messageId) {
        synchronized (lanChatQueueLock) {
            try {
                SharedPreferences prefs = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                JSONArray old = new JSONArray(prefs.getString(PREF_LAN_CHAT_QUEUE, "[]"));
                JSONArray kept = new JSONArray();
                for (int i = 0; i < old.length(); i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item != null && !messageId.equals(item.optString("message_id"))) kept.put(item);
                }
                LanChatStore store = lanChatStore;
                boolean delivered = store != null && store.allTargetsAcked(messageId);
                boolean waitingForPeers = store != null && store.isWaitingForPeers(messageId);
                if (!delivered && !waitingForPeers) {
                    FileLogger.log(this, "CHAT", "⚠️ 未满足逐设备 ACK 或有界发现结算条件，不回收 LAN 消息: " + messageId);
                    return;
                }
                prefs.edit().putString(PREF_LAN_CHAT_QUEUE, kept.toString()).commit();
                if (waitingForPeers) {
                    FileLogger.log(this, "CHAT", "ℹ️ 空目标消息已转 waiting_peers；SQLite 记录保留，停止自动重试: "
                            + messageId);
                }
            } catch (Exception e) {
                FileLogger.log(this, "CHAT", "⚠️ LAN 聊天队列回收失败: " + e.getMessage());
            }
        }
    }

    /**
     * Legacy SharedPreferences mirror cleanup is not part of ACK acceptance.
     * Run its synchronous commit away from the single-threaded ACK/control lane.
     */
    private void scheduleLanChatMirrorCleanup(String messageId) {
        if (messageId == null || messageId.isEmpty() || serviceDestroyed) return;
        try {
            lanChatPeerExecutor.execute(() -> {
                if (!serviceDestroyed) removePendingLanChat(messageId);
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // SQLite already records the terminal ACK state. Recovery can clean the mirror later.
            FileLogger.logD("CHAT", "legacy LAN queue cleanup deferred message=" + messageId);
        }
    }

    /**
     * 网络恢复后按 3 条一批补发；只有队列确实缩短才继续下一批，
     * 单次最多推进 3 批，避免离线成员导致无限重试/长时间占用线程。
     * 沿用原 message_id，接收端会 ACK 但不会重复显示。
     */
    private void retryPendingLanChatMessages() {
        retryPendingLanChatMessages(false);
    }

    /**
     * Timer wakeups consume the current finite retry budget. Only a concrete recovery event
     * (network restored, listener rebound, or peer route verified) may reopen an exhausted epoch.
     */
    private void retryPendingLanChatMessages(boolean recoveryEvent) {
        if (recoveryEvent) lanChatRecoveryEventPending.set(true);
        if (serviceDestroyed || !lanChatRetryInFlight.compareAndSet(false, true)) return;
        // Consume only the ordinary route-refresh wake captured before this batch starts.
        lanChatRouteRefreshPending.getAndSet(false);
        try {
            lanChatSendExecutor.execute(() -> {
                try {
                    LanChatStore store = lanChatStore;
                    if (store == null) return;
                    int finalizedDiscovery = store.finalizeExpiredDiscoveryWindows(System.currentTimeMillis());
                    if (finalizedDiscovery > 0) {
                        FileLogger.log(this, "CHAT", "LAN 恢复时结算已过期发现窗口 messages=" + finalizedDiscovery);
                    }
                    if (lanChatRecoveryEventPending.getAndSet(false)) {
                        int resumed = store.resumeExhaustedRetriesForRecoveryEvent(System.currentTimeMillis());
                        if (resumed > 0) {
                            FileLogger.log(this, "CHAT", "LAN 恢复事件重新开启有限补发预算 messages=" + resumed);
                        }
                    }
                    synchronized (lanChatQueueLock) {
                        JSONArray legacy = new JSONArray(getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                                .getString(PREF_LAN_CHAT_QUEUE, "[]"));
                        for (int i = 0; i < legacy.length(); i++) {
                            JSONObject item = legacy.optJSONObject(i);
                            if (item == null) continue;
                            String id = item.optString("message_id", "");
                            String body = item.optString("content", "");
                            if (!id.isEmpty()) {
                                if (!store.enqueue(id, getUniqueDeviceId(), body,
                                        item.optLong("created_at", System.currentTimeMillis()))) {
                                    FileLogger.log(this, "CHAT", "⚠️ SQLite outbox 补偿迁移失败，保留旧队列");
                                    return;
                                }
                                if (!initializeLanChatRecipientSnapshot(store, id)) {
                                    FileLogger.log(this, "CHAT", "⚠️ SQLite 旧队列收件人快照补偿失败，保留旧队列");
                                    return;
                                }
                                if (store.isWaitingForPeers(id)) {
                                    // A previous process may have been stopped before its close callback
                                    // reclaimed the compatibility mirror for this terminal empty snapshot.
                                    removePendingLanChat(id);
                                }
                            }
                        }
                    }
                    final int batchSize = 3;
                    final int maxBatchesPerWake = 3;
                    // Take one stable snapshot for this wake. An unacknowledged first batch
                    // must not prevent later queued messages from receiving their first attempt.
                    List<LanChatStore.PendingMessage> pending = store.loadPending(50);
                    int queuedBefore = pending.size();
                    for (int batchNo = 0; batchNo < maxBatchesPerWake && !serviceDestroyed; batchNo++) {
                        int startIndex = batchNo * batchSize;
                        if (startIndex >= pending.size()) break;
                        int endIndex = Math.min(startIndex + batchSize, pending.size());

                        for (int i = startIndex; i < endIndex && !serviceDestroyed; i++) {
                            LanChatStore.PendingMessage item = pending.get(i);
                            String id = item.messageId;
                            String content = item.body;
                            if (id == null || id.isEmpty() || content == null || content.isEmpty()) continue;
                            if (store.allTargetsAcked(id)) {
                                // Recover the crash window between SQLite delivery commit and legacy queue cleanup.
                                removePendingLanChat(id);
                                continue;
                            }
                            transmitLocalUdpMessage(content, id);
                            if (store.allTargetsAcked(id)) {
                                // Reclaim the compatibility mirror only after every fixed target is ACKed.
                                removePendingLanChat(id);
                            }
                        }

                        int queuedAfter = store.loadPending(50).size();
                        FileLogger.log(this, "CHAT", "LAN SQLite Outbox 补发批次 " + (batchNo + 1)
                                + "，处理 " + (endIndex - startIndex) + " 条，待确认账本变化 "
                                + queuedBefore + "->" + queuedAfter);
                    }
                    scheduleLanChatRetryFromOutbox();
                } catch (Exception e) {
                    FileLogger.log(this, "CHAT", "⚠️ LAN 聊天补发失败: " + e.getMessage());
                } finally {
                    lanChatRetryInFlight.set(false);
                    // Do not lose a network/peer recovery event that arrived during this batch.
                    if (lanChatRecoveryEventPending.get()) {
                        retryPendingLanChatMessages(true);
                    } else if (lanChatRouteRefreshPending.get()) {
                        // An ACK-timeout route refresh must not be lost behind this batch.
                        retryPendingLanChatMessages(false);
                    } else {
                        // Re-arm after releasing the in-flight guard. A delayed callback may
                        // have fired while this batch was still running and been coalesced.
                        scheduleLanChatRetryFromOutbox();
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            lanChatRetryInFlight.set(false);
            FileLogger.log(this, "CHAT", "⚠️ LAN 补发队列已满，等待下次网络恢复触发");
        }
    }

    // 仅由 lanChatSendExecutor 调用的补发工作线程入口结束；即时发送走独立队列。
    // Both the immediate and retry lanes enter the same guarded transmission path;
    // the active-message set and shared transmit lock prevent duplicate concurrent sends.
    private void sendLocalUdpMessage(final String message, final String messageId) {
        transmitLocalUdpMessage(message, messageId);
    }

    private void transmitLocalUdpMessage(final String message, final String messageId) {
        if (!activeLanChatTransmissions.add(messageId)) {
            FileLogger.log(this, "CHAT", "ℹ️ 同一 message_id 已在发送/补发，跳过并发副本: " + messageId);
            return;
        }
        boolean attemptRecorded = false;
        int attemptNumber = -1;
        LanChatStore store = lanChatStore;
        try {
            if (serviceDestroyed) return;
            DatagramSocket socket = rescueSocketRef;
            long socketGeneration = lanSocketGeneration.get();
            if (socket == null || socket.isClosed() || store == null) {
                FileLogger.log(this, "CHAT", "ℹ️ LAN 监听或账本尚未就绪，消息保留待恢复");
                return;
            }
            if (!hasUsableWifiLanPath()) {
                FileLogger.log(this, "CHAT", "ℹ️ 当前没有可确认的 Wi-Fi LAN 路径，消息保留账本且不消耗发送预算");
                return;
            }
            // Read the durable recipient snapshot before FIFO admission. This is read-only:
            // ACK races are still closed by markAttempt() immediately before each UDP send.
            // Keeping SQLite target enumeration outside the shared sender lock prevents a slow
            // database read from delaying a fresh interactive message behind a retry batch.
            final List<LanChatStore.Target> preparedTargets;
            try {
                preparedTargets = store.loadTargets(messageId);
            } catch (Exception targetReadFailure) {
                FileLogger.log(this, "CHAT", "⚠️ LAN 目标账本读取失败，消息保留待重试: "
                        + targetReadFailure.getClass().getSimpleName());
                return;
            }
            // Wait for the FIFO transmit turn before charging this message's retry epoch.
            // Time spent queued behind another sender is not a delivery attempt.
            long lockRequestedAt = android.os.SystemClock.elapsedRealtime();
            int queueDepthAtRequest = lanChatTransmitLock.getQueueLength();
            lanChatTransmitLock.lock();
            long lockAcquiredAt = android.os.SystemClock.elapsedRealtime();
            try {
                long lockWaitMs = lockAcquiredAt - lockRequestedAt;
                if (lockWaitMs >= 150L) {
                    FileLogger.log(this, "CHAT", "P2P 发送串行锁等待 message="
                            + messageId + " waitMs=" + lockWaitMs
                            + " queuedAtRequest=" + queueDepthAtRequest
                            + " thread=" + Thread.currentThread().getName());
                }
                if (serviceDestroyed) return;
                if (!isCurrentLanChatSocket(socket, socketGeneration)) {
                    FileLogger.log(this, "CHAT", "ℹ️ LAN socket generation changed while queued; message remains pending: "
                            + messageId);
                    return;
                }
                long outboxAttemptDbStartedAt = android.os.SystemClock.elapsedRealtime();
                try {
                    attemptNumber = store.recordOutboxAttempt(messageId);
                } finally {
                    long outboxAttemptDbMs = android.os.SystemClock.elapsedRealtime() - outboxAttemptDbStartedAt;
                    if (outboxAttemptDbMs >= 50L) {
                        FileLogger.log(this, "CHAT", "P2P SQLite outbox attempt message="
                                + messageId + " dbMs=" + outboxAttemptDbMs);
                    }
                }
                if (attemptNumber < 0) {
                    FileLogger.log(this, "CHAT", "⚠️ LAN SQLite 发送尝试记账失败或本轮预算已耗尽，停止发送");
                    return;
                }
                attemptRecorded = true;
                if (!serviceDestroyed) {
                    transmitLocalUdpMessageInternal(message, messageId, socket, socketGeneration, preparedTargets);
                }
            } finally {
                long lockHoldMs = android.os.SystemClock.elapsedRealtime() - lockAcquiredAt;
                if (lockHoldMs >= 150L) {
                    FileLogger.log(this, "CHAT", "P2P 发送串行锁持有 message=" + messageId
                            + " holdMs=" + lockHoldMs
                            + " queueDepthAfter=" + lanChatTransmitLock.getQueueLength()
                            + " thread=" + Thread.currentThread().getName());
                }
                lanChatTransmitLock.unlock();
            }
        } catch (Exception e) {
            FileLogger.log(this, "CHAT", "⚠️ LAN 对讲发送/恢复异常: " + e.getClass().getSimpleName());
        } finally {
            if (!lanChatAckGraceInFlight.contains(messageId)) {
                activeLanChatTransmissions.remove(messageId);
            }
        }
        if (attemptRecorded) {
            if (attemptNumber >= LanChatStore.MAX_AUTOMATIC_RETRY_ATTEMPTS
                    && !store.allTargetsAcked(messageId)) {
                FileLogger.log(this, "CHAT", "LAN 自动补发预算已耗尽，SQLite 消息及未 ACK 目标继续保留，等待网络/peer 恢复事件: "
                        + messageId + " attempts=" + attemptNumber);
            }
            // The ACK observation window owns retry planning. Scheduling here would
            // arm a stale 1.5s wake before the 250ms fast-retry burst has settled.
            if (!lanChatAckGraceInFlight.contains(messageId)) {
                requestLanChatRetryPlan();
            }
        }
    }

    /**
     * A fresh send must not perform the outbox-wide retry scan on the interactive
     * sender thread. Retry batches re-arm their own timer after releasing the
     * single-flight guard; immediate sends hand planning to the retry executor.
     */
    private void requestLanChatRetryPlan() {
        if (serviceDestroyed || lanChatRetryInFlight.get()) return;
        try {
            lanChatSendExecutor.execute(this::scheduleLanChatRetryFromOutbox);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // Rare queue saturation fallback: defer planning rather than block the immediate lane.
            scheduleLanChatTask("outbox-retry-plan-fallback",
                    this::scheduleLanChatRetryFromOutbox, 500L);
        }
    }

    /**
     * Persisted retry wakeups use exponential backoff and a finite per-epoch attempt budget.
     * Exhausted rows remain durable in SQLite but are not timer-polled; a real recovery event
     * may reopen one bounded epoch after the persisted cooldown. waiting_peers stays frozen.
     */
    private void scheduleLanChatRetryFromOutbox() {
        // The active batch re-arms the scheduler from its finally block after releasing this guard.
        // Do not enqueue timer callbacks that will only fail the single-flight CAS.
        if (serviceDestroyed || lanChatRetryInFlight.get() || lanChatRetryWakeScheduled.get()) return;
        if (!hasUsableWifiLanPath()) return;
        DatagramSocket socket = rescueSocketRef;
        LanChatStore store = lanChatStore;
        if (socket == null || socket.isClosed() || store == null) return;
        try {
            List<LanChatStore.PendingMessage> pending = store.loadPending(50);
            int minimumAttempts = Integer.MAX_VALUE;
            boolean hasUnattemptedRouteTargets = false;
            for (LanChatStore.PendingMessage item : pending) {
                if (item.retryAttempts > 0) {
                    minimumAttempts = Math.min(minimumAttempts, item.retryAttempts);
                } else if (store.hasUnattemptedTargets(item.messageId)) {
                    // No route means markAttempt() has correctly not consumed a delivery attempt.
                    // Still schedule a bounded re-challenge, otherwise a lost first peer.ack can
                    // strand a durable fixed target until an unrelated network event occurs.
                    hasUnattemptedRouteTargets = true;
                }
            }
            if (minimumAttempts == Integer.MAX_VALUE && !hasUnattemptedRouteTargets) return;
            // loadPending() excludes messages that exhausted this epoch's finite send budget.
            // Their SQLite rows and unacked target records remain intact for recovery events.
            // Empty discovery snapshots are finalized separately as waiting_peers.
            final long sendRetryDelayMs = minimumAttempts == Integer.MAX_VALUE ? Long.MAX_VALUE
                    : (minimumAttempts <= 1 ? 1_500L
                    : (minimumAttempts == 2 ? 4_000L
                    : Math.min(60_000L, 10_000L << Math.min(3, minimumAttempts - 3))));
            // Fixed targets that have not yet had a real send get a separate route-probe wake.
            // It does not increment attempt_count or weaken the per-message delivery budget.
            final long delayMs = hasUnattemptedRouteTargets
                    ? Math.min(5_000L, sendRetryDelayMs) : sendRetryDelayMs;
            if (!lanChatRetryWakeScheduled.compareAndSet(false, true)) return;
            boolean scheduled = scheduleLanChatTask("outbox-retry", () -> {
                lanChatRetryWakeScheduled.set(false);
                retryPendingLanChatMessages();
            }, delayMs);
            if (!scheduled) lanChatRetryWakeScheduled.set(false);
        } catch (Exception e) {
            FileLogger.log(this, "CHAT", "⚠️ 无法安排 LAN 持久化补发: " + e.getClass().getSimpleName());
        }
    }

    private void transmitLocalUdpMessageInternal(final String message, final String messageId,
            DatagramSocket socket, long socketGeneration,
            final List<LanChatStore.Target> savedTargets) {
        final long preparationStartedAt = android.os.SystemClock.elapsedRealtime();
        if (!isCurrentLanChatSocket(socket, socketGeneration)) {
            FileLogger.log(this, "CHAT", "ℹ️ P2P send rejected for stale UDP socket generation message=" + messageId);
            return;
        }
        long broadcastLookupMs = 0L;
        long targetLoadMs = 0L;
        long routeProbeMs = 0L;
        long packetBuildMs = 0L;
        if (!isCurrentLanChatSocket(socket, socketGeneration)) {
            FileLogger.log(this, "CHAT", "⚠️ P2P UDP socket epoch expired before preparation message=" + messageId);
            return;
        }
        LanChatStore store = lanChatStore;
        if (store == null) {
            FileLogger.log(this, "CHAT", "⚠️ LAN SQLite 账本尚未就绪，消息保留待恢复");
            return;
        }
        String myId = getUniqueDeviceId();
        long stageStartedAt = android.os.SystemClock.elapsedRealtime();

        // The durable recipient snapshot was loaded before FIFO admission. It is read-only;
        // every actual recipient send is still revalidated transactionally by markAttempt().
        // This keeps SQLite target enumeration out of the shared transmission critical section.
        final long targetLoadMs = 0L;
        java.util.Set<String> pendingTargetIds = new java.util.HashSet<>();
        for (LanChatStore.Target target : savedTargets) {
            if (!"acked".equals(target.status)) pendingTargetIds.add(target.peerId);
        }
        if (pendingTargetIds.isEmpty()) {
            // Call the ledger even when its deadline has elapsed so legacy empty-target
            // rows from earlier builds are migrated to waiting_peers and leave retry queues.
            boolean firstDispatch = store.beginDiscoveryWindow(messageId);
            final boolean discoveryWindowOpen;
            try {
                discoveryWindowOpen = store.isDiscoveryPending(messageId);
            } catch (Exception storageFailure) {
                FileLogger.log(this, "CHAT", "⚠️ LAN 发现窗口账本读取失败，停止本轮发送以防扩大收件人范围: "
                        + storageFailure.getClass().getSimpleName());
                return;
            }
            if (!discoveryWindowOpen) {
                if (store.isWaitingForPeers(messageId)) {
                    FileLogger.log(this, "CHAT", "P2P 空目标消息已结算为 waiting_peers，不向晚加入设备广播: "
                            + messageId);
                    removePendingLanChat(messageId);
                }
                return;
            }
            if (firstDispatch) {
                FileLogger.log(this, "CHAT", "P2P 首次发现窗口开始，限时 "
                        + LAN_CHAT_DISCOVERY_WINDOW_MS + "ms，消息 " + messageId);
                scheduleLanChatTask("discovery-window-close", () -> {
                    try {
                        lanChatSendExecutor.execute(() -> {
                            if (store.finalizeDiscoveryWindow(messageId)) {
                                FileLogger.log(this, "CHAT", "P2P 首次发现窗口关闭，消息状态已结算: "
                                        + messageId);
                                removePendingLanChat(messageId);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException ignored) {
                        // A later retry or authenticated peer discovery will settle the durable row.
                    }
                }, LAN_CHAT_DISCOVERY_WINDOW_MS + 100L);
            }
        }

        stageStartedAt = android.os.SystemClock.elapsedRealtime();
        final long routeProbeStartedAt = stageStartedAt;
        // 先清理过期租约；同一网络内仍有已验证成员时直接复用单播路由，
        // 不再让每条聊天消息都额外等待一次 500ms 广播发现窗口。
        long now = System.currentTimeMillis();
        boolean expiredPeerRouteRemoved = false;
        for (java.util.Map.Entry<String, Long> e : verifiedChatPeerSeen.entrySet()) {
            if (now - e.getValue() > 120_000L) {
                synchronized (verifiedChatPeerRouteVersions) {
                    if (verifiedChatPeerSeen.remove(e.getKey(), e.getValue())) {
                        verifiedChatPeers.remove(e.getKey());
                        verifiedChatPeerRouteVersions.remove(e.getKey());
                        expiredPeerRouteRemoved = true;
                    }
                }
            }
        }
        if (expiredPeerRouteRemoved && verifiedChatPeers.isEmpty()) {
            // A genuinely expired final route should trigger one fresh discovery immediately.
            lastPeerDiscoveryAt = 0L;
        }

        // Revalidate persisted endpoints off the FIFO transmit lock. A slow challenge
        // must never hold up an ordinary chat message or unrelated ACK/control traffic.
        int directedChallenges = 0;
        for (LanChatStore.Target target : savedTargets) {
            if (!isCurrentLanChatSocket(socket, socketGeneration)) break;
            if (target == null || target.peerId == null || "acked".equals(target.status)
                    || verifiedChatPeers.containsKey(target.peerId)
                    || target.endpointIp == null || target.endpointIp.isEmpty()
                    || !(target.endpointPort == 8888 || target.endpointPort == 8887
                         || target.endpointPort == 8889 || target.endpointPort == 8890)) {
                continue;
            }
            final String challengePeerId = target.peerId;
            final Object challengeDispatchToken = new Object();
            if (pendingPeerChallengeDispatches.putIfAbsent(challengePeerId, challengeDispatchToken) != null) {
                continue;
            }
            final String challengeIp = target.endpointIp;
            final int challengePort = target.endpointPort;
            final long challengeQueuedAt = android.os.SystemClock.elapsedRealtime();
            try {
                lanChatProbeExecutor.execute(() -> {
                    try {
                        long queueMs = android.os.SystemClock.elapsedRealtime() - challengeQueuedAt;
                        if (serviceDestroyed || !isCurrentLanChatSocket(socket, socketGeneration)
                                || verifiedChatPeers.containsKey(challengePeerId)) return;
                        java.net.InetAddress savedAddress = java.net.InetAddress.getByName(challengeIp);
                        if (!(savedAddress instanceof java.net.Inet4Address)
                                || !savedAddress.isSiteLocalAddress()
                                || savedAddress.isLoopbackAddress()
                                || savedAddress.isAnyLocalAddress()) return;
                        long dispatchStartedAt = android.os.SystemClock.elapsedRealtime();
                        sendDirectedPeerChallenge(socket, challengePeerId, savedAddress, challengePort);
                        long dispatchMs = android.os.SystemClock.elapsedRealtime() - dispatchStartedAt;
                        if (queueMs >= 150L || dispatchMs >= 150L) {
                            FileLogger.log(this, "CHAT", "P2P directed challenge queued message="
                                    + messageId + " peer=" + challengePeerId
                                    + " queueMs=" + queueMs + " dispatchMs=" + dispatchMs);
                        }
                    } catch (Exception staleEndpoint) {
                        FileLogger.logD("CHAT", "persisted peer endpoint challenge skipped peer="
                                + challengePeerId + " reason=" + staleEndpoint.getClass().getSimpleName());
                    } finally {
                        pendingPeerChallengeDispatches.remove(challengePeerId, challengeDispatchToken);
                    }
                });
                directedChallenges++;
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                pendingPeerChallengeDispatches.remove(challengePeerId, challengeDispatchToken);
                FileLogger.log(this, "CHAT", "P2P directed challenge queue full peer=" + challengePeerId);
            }
        }
        if (directedChallenges > 0) {
            FileLogger.log(this, "CHAT", "P2P 已排队验证已保存目标路由 count="
                    + directedChallenges + " message=" + messageId);
        }

        // Discovery is deliberately non-blocking: probe dispatch runs away from the FIFO
        // sender and peer-admission lane. The chat packet below remains ordered on the
        // transmit lane; authenticated discovery ACK grace and durable recipient snapshots
        // continue to govern any newly learned peer.
        boolean discoveryNeeded = pendingTargetIds.isEmpty();
        if (!discoveryNeeded) {
            for (LanChatStore.Target target : savedTargets) {
                if (target == null || target.peerId == null || "acked".equals(target.status)) continue;
                // A missing route needs discovery immediately; an unacknowledged prior
                // attempt also needs a route refresh in case the peer changed IP silently.
                if (!verifiedChatPeers.containsKey(target.peerId) || target.attemptCount > 0) {
                    discoveryNeeded = true;
                    break;
                }
            }
        }
        boolean discoveryDue = discoveryNeeded && (lastPeerDiscoveryAt == 0L
                || now - lastPeerDiscoveryAt >= LAN_PEER_DISCOVERY_COOLDOWN_MS);
        boolean shouldDiscoverPeers = discoveryDue
                && peerDiscoveryInFlight.compareAndSet(false, true);

        // Only resolve the Wi-Fi broadcast address when a real discovery broadcast or
        // an empty-recipient first delivery needs it. Fixed-target unicast avoids a full
        // NetworkInterface enumeration on every message.
        final String[] broadcasts;
        if (shouldDiscoverPeers || pendingTargetIds.isEmpty()) {
            stageStartedAt = android.os.SystemClock.elapsedRealtime();
            String subnetBcast = getWifiBroadcastAddress();
            broadcastLookupMs = android.os.SystemClock.elapsedRealtime() - stageStartedAt;
            broadcasts = new String[]{subnetBcast};
        } else {
            broadcasts = new String[0];
        }

        if (shouldDiscoverPeers) {
            lastPeerDiscoveryAt = now;
            String nonce = java.util.UUID.randomUUID().toString();
            java.util.concurrent.CountDownLatch peerLatch = new java.util.concurrent.CountDownLatch(1);
            pendingPeerChallenges.put(nonce, peerLatch);
            scheduleLanChatTask("peer-discovery-timeout", () -> {
                if (pendingPeerChallenges.get(nonce) == peerLatch) {
                    clearPendingPeerChallenge(nonce);
                }
            }, LAN_PEER_CHALLENGE_TIMEOUT_MS);
            final long discoveryQueuedAt = android.os.SystemClock.elapsedRealtime();
            try {
                lanChatProbeExecutor.execute(() -> {
                    long dispatchStartedAt = android.os.SystemClock.elapsedRealtime();
                    try {
                        if (serviceDestroyed || !isCurrentLanChatSocket(socket, socketGeneration)) return;
                        JSONObject helloPacket = LanP2pProtocol.create("peer.hello", myId, "android", "android-main");
                        helloPacket.put("nonce", nonce);
                        helloPacket.put("network_scope", getLanProtocolNetworkScope());
                        helloPacket.put("capabilities", new JSONArray().put("chat.text").put("chat.ack"));
                        byte[] hello = LanP2pProtocol.encode(helloPacket, RESCUE_SECRET);
                        for (String target : broadcasts) {
                            if (!isCurrentLanChatSocket(socket, socketGeneration)) break;
                            for (int peerPort : new int[]{8888, 8887, 8889, 8890}) {
                                if (!isCurrentLanChatSocket(socket, socketGeneration)) break;
                                try {
                                    DatagramPacket packet = new DatagramPacket(hello, hello.length,
                                            java.net.InetAddress.getByName(target), peerPort);
                                    sendLanUdpPacket(socket, packet, "PEER_HELLO_" + peerPort);
                                } catch (Exception e) {
                                    FileLogger.log(this, "CHAT", "⚠️ P2P 发现广播失败 port="
                                            + peerPort + ": " + e.getClass().getSimpleName());
                                }
                            }
                        }
                        long queueMs = dispatchStartedAt - discoveryQueuedAt;
                        long dispatchMs = android.os.SystemClock.elapsedRealtime() - dispatchStartedAt;
                        if (queueMs >= 150L || dispatchMs >= 150L) {
                            FileLogger.log(this, "CHAT", "P2P discovery probe queueMs="
                                    + queueMs + " dispatchMs=" + dispatchMs
                                    + " socketGeneration=" + socketGeneration);
                        }
                        FileLogger.logD("CHAT", "P2P discovery broadcast dispatched cachedPeers="
                                + verifiedChatPeers.size());
                    } catch (Exception e) {
                        FileLogger.log(this, "CHAT", "⚠️ P2P 发现失败: " + e.getClass().getSimpleName());
                        lastPeerDiscoveryAt = 0L;
                    } finally {
                        // The nonce remains valid for late authenticated peer.ack responses.
                        peerDiscoveryInFlight.set(false);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                clearPendingPeerChallenge(nonce);
                peerDiscoveryInFlight.set(false);
                lastPeerDiscoveryAt = 0L;
                FileLogger.log(this, "CHAT", "P2P discovery queue full; retry on next send");
            }
        }
        routeProbeMs = Math.max(0L, android.os.SystemClock.elapsedRealtime()
                - routeProbeStartedAt - broadcastLookupMs);
        stageStartedAt = android.os.SystemClock.elapsedRealtime();
        byte[] broadcastData;
        try { broadcastData = buildAuthenticatedLanChatPacket(myId, "*", messageId, message); }
        catch (Exception e) {
            FileLogger.log(this, "CHAT", "⚠️ LAN v2 消息编码失败，消息保留待确认: " + e.getClass().getSimpleName());
            return;
        }
        packetBuildMs = android.os.SystemClock.elapsedRealtime() - stageStartedAt;
        java.util.Map<String, java.net.InetSocketAddress> dispatchEndpoints = new java.util.HashMap<>();
        java.util.Set<String> verifiedRoutePeerIds = new java.util.HashSet<>();
        for (LanChatStore.Target target : savedTargets) {
            if (target == null || target.peerId == null || "acked".equals(target.status)) continue;
            java.net.InetSocketAddress liveRoute = verifiedChatPeers.get(target.peerId);
            if (liveRoute != null) {
                dispatchEndpoints.put(target.peerId, liveRoute);
                verifiedRoutePeerIds.add(target.peerId);
                continue;
            }
            // A cached endpoint is sufficient for an immediate message attempt; the packet's
            // stable target_peer_id prevents delivery to a different host reusing this address.
            if (target.endpointIp == null || target.endpointIp.isEmpty()
                    || !(target.endpointPort == 8888 || target.endpointPort == 8887
                         || target.endpointPort == 8889 || target.endpointPort == 8890)) continue;
            try {
                java.net.InetAddress cachedAddress = java.net.InetAddress.getByName(target.endpointIp);
                if (!(cachedAddress instanceof java.net.Inet4Address)
                        || !cachedAddress.isSiteLocalAddress()
                        || cachedAddress.isLoopbackAddress() || cachedAddress.isAnyLocalAddress()) continue;
                dispatchEndpoints.put(target.peerId,
                        new java.net.InetSocketAddress(cachedAddress, target.endpointPort));
            } catch (Exception invalidCachedEndpoint) {
                FileLogger.logD("CHAT", "cached LAN endpoint skipped peer=" + target.peerId
                        + " reason=" + invalidCachedEndpoint.getClass().getSimpleName());
            }
        }
        java.util.Set<String> awaitingPeerIds = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
        awaitingPeerIds.addAll(dispatchEndpoints.keySet());
        final int totalPeerCount = pendingTargetIds.size();
        final int reachablePeerCount = awaitingPeerIds.size();
        final int verifiedRouteCount = verifiedRoutePeerIds.size();
        final int cachedEndpointCount = Math.max(0, reachablePeerCount - verifiedRouteCount);
        if (cachedEndpointCount > 0) {
            FileLogger.log(this, "CHAT", "P2P 即时投递走持久化 endpoint count="
                    + cachedEndpointCount + " message=" + messageId);
        }
        boolean firstDiscoveryWindowOpen = false;
        if (totalPeerCount == 0) {
            try {
                firstDiscoveryWindowOpen = store.isDiscoveryPending(messageId);
            } catch (Exception storageFailure) {
                FileLogger.log(this, "CHAT", "⚠️ LAN 发现窗口状态复核失败，停止广播以防误投: "
                        + storageFailure.getClass().getSimpleName());
                return;
            }
        }
        if (totalPeerCount == 0 && !firstDiscoveryWindowOpen) {
            FileLogger.log(this, "CHAT", "ℹ️ 消息首次发现窗口已关闭且没有固定目标；禁止向晚加入设备广播旧消息，保留待诊断状态: "
                    + messageId);
            return;
        }
        java.util.concurrent.CountDownLatch ack = new java.util.concurrent.CountDownLatch(
                reachablePeerCount > 0 ? reachablePeerCount : (totalPeerCount == 0 ? 1 : 0));
        pendingChatAckPeers.put(messageId, awaitingPeerIds);
        pendingChatAckStartedAt.put(messageId, android.os.SystemClock.elapsedRealtime());
        pendingChatAcks.put(messageId, ack);
        long preparationMs = android.os.SystemClock.elapsedRealtime() - preparationStartedAt;
        if (preparationMs >= 100L) {
            FileLogger.log(this, "CHAT", "P2P 发送准备耗时 message=" + messageId
                    + " totalMs=" + preparationMs
                    + " broadcastLookupMs=" + broadcastLookupMs
                    + " targetDbMs=" + targetLoadMs
                    + " routeProbeMs=" + routeProbeMs
                    + " packetBuildMs=" + packetBuildMs
                    + " otherMs=" + Math.max(0L, preparationMs - broadcastLookupMs
                            - targetLoadMs - routeProbeMs - packetBuildMs));
        }
        FileLogger.log(this, "CHAT", "⏱️ P2P UDP 开始发送，固定目标 " + totalPeerCount
                + "，已验证路由 " + verifiedRouteCount + "，历史端点直投 " + cachedEndpointCount
                + "，消息 " + messageId);
        long ackLoopStartedAt = android.os.SystemClock.elapsedRealtime();
        java.util.concurrent.atomic.AtomicBoolean hadUdpDispatchAttempt =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean ackCleanupDeferred = false;
        try {
            if (totalPeerCount == 0 && !socket.isClosed()) {
                // 没有经过 challenge 的 peer 时仅广播一次；首个匹配 messageId 的认证 ACK
                // 只证明至少一个接收端已收到，不把该来源加入 verifiedChatPeers。
                for (String target : broadcasts) {
                    if (!isCurrentLanChatSocket(socket, socketGeneration)) break;
                    for (int peerPort : new int[]{8888, 8887, 8889, 8890}) {
                        if (!isCurrentLanChatSocket(socket, socketGeneration)) break;
                        try {
                            DatagramPacket p = new DatagramPacket(broadcastData, broadcastData.length,
                                    java.net.InetAddress.getByName(target), peerPort);
                            hadUdpDispatchAttempt.set(true);
                            sendLanUdpPacket(socket, p, "CHAT_BROADCAST_" + peerPort);
                        } catch (Exception e) {
                            FileLogger.log(this, "CHAT", "⚠️ P2P 广播失败 port="
                                    + peerPort + ": " + e.getMessage());
                        }
                    }
                }
            } else if (reachablePeerCount > 0) {
                for (int attempt = 1; attempt <= 1 && ack.getCount() > 0; attempt++) {
                    if (socket.isClosed()) break;
                    for (String peerId : new java.util.ArrayList<>(awaitingPeerIds)) {
                        if (!isCurrentLanChatSocket(socket, socketGeneration)) break;
                        java.net.InetSocketAddress peer = dispatchEndpoints.get(peerId);
                        if (peer == null) continue;
                        try {
                            String attemptedEndpoint = peer.getAddress().getHostAddress() + ":" + peer.getPort();
                            // Persist the exact endpoint before UDP dispatch; this confirms this
                            // message only and does not promote the reusable peer route.
                            // Recheck the durable target immediately before UDP dispatch.
                            // ACK may have arrived after the recipient snapshot was loaded;
                            // a failed ledger check must fail closed rather than resend to an ACKed peer.
                            long targetAttemptDbStartedAt = android.os.SystemClock.elapsedRealtime();
                            boolean targetAttemptAllowed;
                            try {
                                targetAttemptAllowed = store != null
                                        && store.markAttempt(messageId, peerId, attemptedEndpoint, "");
                            } finally {
                                long targetAttemptDbMs = android.os.SystemClock.elapsedRealtime() - targetAttemptDbStartedAt;
                                if (targetAttemptDbMs >= 50L) {
                                    FileLogger.log(this, "CHAT", "P2P SQLite target attempt message="
                                            + messageId + " peer=" + peerId + " dbMs=" + targetAttemptDbMs);
                                }
                            }
                            if (!targetAttemptAllowed) continue;
                            byte[] peerData = buildAuthenticatedLanChatPacket(myId, peerId, messageId, message);
                            DatagramPacket p = new DatagramPacket(peerData, peerData.length,
                                    peer.getAddress(), peer.getPort());
                            hadUdpDispatchAttempt.set(true);
                            sendLanUdpPacket(socket, p, verifiedRoutePeerIds.contains(peerId)
                                    ? "CHAT_UNICAST_VERIFIED" : "CHAT_UNICAST_CACHED_ENDPOINT");
                            final String successfulEndpoint = peer.getAddress().getHostAddress() + ":" + peer.getPort();
                            final LanChatStore endpointStore = store;
                            try {
                                // markAttempt() already durably recorded this exact endpoint before
                                // UDP dispatch, so ACK validation is safe immediately. Updating the
                                // reusable last-successful endpoint is secondary bookkeeping and must
                                // not hold the FIFO sender lock after the packet has left.
                                lanChatPeerExecutor.execute(() -> {
                                    long endpointPersistStartedAt = android.os.SystemClock.elapsedRealtime();
                                    boolean endpointPersisted = endpointStore != null
                                            && endpointStore.recordSuccessfulSendEndpoint(
                                                    messageId, peerId, successfulEndpoint);
                                    long endpointPersistMs = android.os.SystemClock.elapsedRealtime()
                                            - endpointPersistStartedAt;
                                    if (!endpointPersisted || endpointPersistMs >= 50L) {
                                        FileLogger.log(this, "CHAT", "P2P async SQLite send-endpoint message="
                                                + messageId + " peer=" + peerId
                                                + " persisted=" + endpointPersisted
                                                + " dbMs=" + endpointPersistMs);
                                    }
                                });
                            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                                // last_attempt_endpoint remains durable and is sufficient for this
                                // message's authenticated ACK; the next route discovery can refresh
                                // the reusable endpoint if this secondary update is dropped.
                                FileLogger.logD("CHAT", "send endpoint bookkeeping deferred message=" + messageId);
                            }
                        } catch (Exception e) {
                            final String sendError = e.getClass().getSimpleName();
                            final LanChatStore errorStore = store;
                            try {
                                lanChatPeerExecutor.execute(() -> {
                                    if (errorStore != null) {
                                        errorStore.recordTargetSendError(messageId, peerId, sendError);
                                    }
                                });
                            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                                // Attempt count and exact endpoint were already committed pre-send.
                            }
                            FileLogger.log(this, "CHAT", "⚠️ P2P 单播失败: " + e.getMessage());
                        }
                    }

                }
            } else {
                FileLogger.log(this, "CHAT", "ℹ️ 固定目标当前均离线，保留逐设备待送达记录: " + messageId);
            }
            final java.util.Set<String> completionAwaitingPeerIds = awaitingPeerIds;
            final java.util.concurrent.CountDownLatch completionAck = ack;
            final int completionTotalPeerCount = totalPeerCount;
            final int completionReachablePeerCount = reachablePeerCount;
            final LanChatStore completionStore = store;
            final long completionStartedAt = ackLoopStartedAt;
            // Publish the grace state before scheduling: a fast scheduler must never
            // observe the task before the retry gate knows this message is still active.
            lanChatAckGraceInFlight.add(messageId);
            ackCleanupDeferred = true;
            boolean completionScheduled = scheduleLanChatTask("chat-ack-grace-" + messageId, () -> {
                Runnable completion = () -> completeLanChatAckWindow(messageId,
                        completionAwaitingPeerIds, completionAck,
                        completionTotalPeerCount, completionReachablePeerCount,
                        completionStore, completionStartedAt, hadUdpDispatchAttempt.get());
                try {
                    lanChatIoExecutor.execute(completion);
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    completion.run();
                }
            }, 250L);
            if (!completionScheduled) {
                lanChatAckGraceInFlight.remove(messageId);
                ackCleanupDeferred = false;
                completeLanChatAckWindow(messageId, completionAwaitingPeerIds,
                        completionAck, completionTotalPeerCount,
                        completionReachablePeerCount, completionStore, completionStartedAt,
                        hadUdpDispatchAttempt.get());
            }
        } finally {
            if (!ackCleanupDeferred) {
                pendingChatAcks.remove(messageId);
                pendingChatAckPeers.remove(messageId);
                pendingChatAckStartedAt.remove(messageId);
            }
        }
    }

    /**
     * Complete the short ACK observation window away from the FIFO sender.
     * UDP dispatch remains ordered, while ACK persistence continues on LanChat-IO.
     */
    private void completeLanChatAckWindow(String messageId,
            java.util.Set<String> awaitingPeerIds,
            java.util.concurrent.CountDownLatch ack,
            int totalPeerCount, int reachablePeerCount,
            LanChatStore store, long ackStartedAt, boolean hadUdpDispatchAttempt) {
        boolean requestRouteRefresh = false;
        try {
            long elapsed = android.os.SystemClock.elapsedRealtime() - ackStartedAt;
            LanChatStore.AckProgress durableProgress = store == null
                    ? null : store.getTargetAckProgress(messageId);
            String durableAckProgress = durableProgress == null ? "unavailable"
                    : durableProgress.ackedTargets + "/" + durableProgress.totalTargets;
            boolean persistedAllTargetsAcked = store != null && store.allTargetsAcked(messageId);
            if (totalPeerCount == 0) {
                if (ack.getCount() == 0) {
                    lastPeerDiscoveryAt = 0L;
                    FileLogger.log(this, "CHAT", "ℹ️ P2P 广播至少一个接收端已确认，但成员名单未建立；"
                            + "保留消息并等待逐设备目标建立: " + messageId);
                } else {
                    FileLogger.log(this, "CHAT", "⚠️ P2P 广播暂未收到 ACK，消息保留待确认队列: " + messageId);
                    // Empty-recipient first discovery has no per-peer attempt row. Re-send
                    // the same durable message at 250ms intervals for a short initial burst,
                    // then return to the normal persisted exponential retry schedule.
                    int discoveryAttempts = store == null ? -1
                            : store.getOutboxRetryAttemptCount(messageId);
                    if (hadUdpDispatchAttempt && discoveryAttempts > 0 && discoveryAttempts < 3) {
                        lastPeerDiscoveryAt = 0L;
                        requestRouteRefresh = true;
                    }
                }
            } else if (persistedAllTargetsAcked) {
                removePendingLanChat(messageId);
                FileLogger.log(this, "CHAT", "✅ P2P 固定目标全部持久化 ACK: "
                        + durableAckProgress + "，消息 " + messageId);
            } else {
                // A missing message ACK does not revoke a verified route, but it does
                // justify one bounded subnet discovery. This catches silent peer-IP changes.
                // Re-arm only if a packet was actually attempted (or no endpoint existed);
                // a retry rejected by attempt backoff must not create a tight wake loop.
                if (hadUdpDispatchAttempt || reachablePeerCount == 0) {
                    lastPeerDiscoveryAt = 0L;
                    requestRouteRefresh = true;
                }
                FileLogger.log(this, "CHAT", "⚠️ P2P 消息仍待确认并保留本地队列: "
                        + durableAckProgress + "，保留已验证路由并触发有界路由刷新，消息 " + messageId);
            }
            if (elapsed >= 400L) {
                FileLogger.logD("CHAT", "P2P ACK grace elapsedMs=" + elapsed
                        + " remaining=" + ack.getCount() + "/" + totalPeerCount
                        + " message=" + messageId);
            }
        } catch (Exception e) {
            FileLogger.log(this, "CHAT", "⚠️ LAN ACK 观察窗口收尾失败 message="
                    + messageId + " error=" + e.getClass().getSimpleName());
        } finally {
            pendingChatAcks.remove(messageId);
            pendingChatAckPeers.remove(messageId);
            pendingChatAckStartedAt.remove(messageId);
            lanChatAckGraceInFlight.remove(messageId);
            activeLanChatTransmissions.remove(messageId);
        }
        if (requestRouteRefresh && !serviceDestroyed) {
            lanChatRouteRefreshPending.set(true);
            retryPendingLanChatMessages(false);
        } else if (!serviceDestroyed) {
            // Arm the durable backoff only after the short ACK/fast-retry decision has
            // completed; this prevents an early timer from bypassing the intended 10s tier.
            requestLanChatRetryPlan();
        }
    }

    /**
     * DatagramSocket is thread-safe; do not serialize unrelated chat, ACK and peer sends
     * on the socket monitor. A concurrent close is surfaced as IOException and the durable
     * outbox retains the message for the next valid socket generation.
     */
    private void sendLanUdpPacket(DatagramSocket socket, DatagramPacket packet, String kind)
            throws java.io.IOException {
        if (socket == null || socket.isClosed()) {
            throw new java.net.SocketException("UDP socket closed before send");
        }
        long sendStarted = android.os.SystemClock.elapsedRealtime();
        socket.send(packet);
        long sendMs = android.os.SystemClock.elapsedRealtime() - sendStarted;
        if (sendMs >= 250L) {
            FileLogger.log(this, "UDP", "P2P UDP slow dispatch kind=" + kind
                    + " sendMs=" + sendMs + " bytes=" + packet.getLength());
        } else if (sendMs >= 50L) {
            FileLogger.logD("UDP", "slow send kind=" + kind
                    + " sendMs=" + sendMs + " bytes=" + packet.getLength());
        }
    }

    /** Broadcast one authenticated peer beacon when the listener starts or the LAN route changes. */
    private void broadcastLanPeerHello() {
        if (!hasUsableWifiLanPath()) return;
        DatagramSocket socket = rescueSocketRef;
        if (serviceDestroyed || socket == null || socket.isClosed()) return;
        String broadcast = getWifiBroadcastAddress();
        if (broadcast == null || broadcast.isEmpty()) broadcast = "255.255.255.255";
        sendPeerHello(socket, broadcast, new int[]{8888, 8887, 8889, 8890},
                false, null, null);
    }

    /** Send a one-way challenge to a peer that announced itself; it cannot trigger a challenge loop. */
    private void sendDirectedPeerChallenge(DatagramSocket socket, String expectedPeerId,
                                            java.net.InetAddress address, int port) {
        if (serviceDestroyed || socket == null || socket.isClosed() || address == null
                || expectedPeerId == null || expectedPeerId.isEmpty()
                || !(port == 8888 || port == 8887 || port == 8889 || port == 8890)) return;
        if (pendingPeerChallengeExpectedIds.containsValue(expectedPeerId)) return;
        sendPeerHello(socket, address.getHostAddress(), new int[]{port}, true,
                expectedPeerId, new java.net.InetSocketAddress(address, port));
    }

    private void clearPendingPeerChallenge(String nonce) {
        if (nonce == null || nonce.isEmpty()) return;
        pendingPeerChallenges.remove(nonce);
        pendingPeerChallengeExpectedIds.remove(nonce);
        pendingPeerChallengeExpectedEndpoints.remove(nonce);
    }

    private void sendPeerHello(DatagramSocket socket, String address, int[] ports, boolean challengeOnly) {
        sendPeerHello(socket, address, ports, challengeOnly, null, null);
    }

    private void sendPeerHello(DatagramSocket socket, String address, int[] ports, boolean challengeOnly,
                               String expectedPeerId,
                               java.net.InetSocketAddress expectedEndpoint) {
        String nonce = java.util.UUID.randomUUID().toString();
        java.util.concurrent.CountDownLatch challengeLatch =
                new java.util.concurrent.CountDownLatch(1);
        pendingPeerChallenges.put(nonce, challengeLatch);
        if (expectedPeerId != null && expectedEndpoint != null) {
            pendingPeerChallengeExpectedIds.put(nonce, expectedPeerId);
            pendingPeerChallengeExpectedEndpoints.put(nonce, expectedEndpoint);
        }
        try {
            JSONObject hello = LanP2pProtocol.create("peer.hello", getUniqueDeviceId(), "android", "android-main");
            hello.put("nonce", nonce);
            hello.put("listen_port", 8888);
            hello.put("challenge_only", challengeOnly);
            hello.put("network_scope", getLanProtocolNetworkScope());
            hello.put("capabilities", new JSONArray().put("chat.text").put("chat.ack"));
            byte[] bytes = LanP2pProtocol.encode(hello, RESCUE_SECRET);
            for (int port : ports) {
                try {
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length,
                            java.net.InetAddress.getByName(address), port);
                    sendLanUdpPacket(socket, packet, "PEER_BEACON_" + port);
                } catch (Exception e) {
                    FileLogger.logD("CHAT", "LAN peer beacon send failed port=" + port
                            + " error=" + e.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            clearPendingPeerChallenge(nonce);
            FileLogger.log(this, "CHAT", "LAN peer beacon creation failed: "
                    + e.getClass().getSimpleName() + " reason=" + e.getMessage());
            return;
        }
        scheduleLanChatTask("peer-challenge-timeout", () -> {
            if (pendingPeerChallenges.get(nonce) == challengeLatch) {
                clearPendingPeerChallenge(nonce);
            }
        }, LAN_PEER_CHALLENGE_TIMEOUT_MS);
    }

    /**
     * 实时 LAN 控制包快路径：握手和聊天 ACK 在普通控制指令分发之前完成。
     * 返回 true 表示该包已处理；不创建线程、不做磁盘 I/O、不执行 UI/业务命令。
     */
    private byte[] buildAuthenticatedLanChatPacket(String senderId, String targetPeerId,
                                                           String messageId, String body) throws Exception {
        JSONObject packet = LanP2pProtocol.create("chat.message", senderId, "android", "android-main");
        packet.put("message_id", messageId);
        packet.put("target_peer_id", targetPeerId);
        packet.put("created_at_ms", System.currentTimeMillis());
        packet.put("body", body);
        return LanP2pProtocol.encode(packet, RESCUE_SECRET);
    }

    private boolean handleAuthenticatedLanPacket(DatagramSocket socket, DatagramPacket packet,
                                                   JSONObject envelope, long packetReceivedAtElapsedMs) {
        try {
            String type = envelope.optString("type", "");
            String senderId = envelope.optString("sender_peer_id", "");
            String serviceId = envelope.optString("service_id", "");
            long now = System.currentTimeMillis();
            if ("peer.hello".equals(type)) {
                int sourcePort = packet.getPort();
                if (senderId.isEmpty() || senderId.equals(getUniqueDeviceId())
                        || envelope.optString("nonce", "").isEmpty()
                        || !(sourcePort == 8888 || sourcePort == 8887
                             || sourcePort == 8889 || sourcePort == 8890)) return true;
                final java.net.InetAddress peerAddress = packet.getAddress();
                JSONObject reply = LanP2pProtocol.create("peer.ack", getUniqueDeviceId(),
                        "android", "android-main");
                reply.put("reply_nonce", envelope.optString("nonce", ""));
                reply.put("listen_port", 8888);
                byte[] bytes = LanP2pProtocol.encode(reply, RESCUE_SECRET);
                DatagramPacket response = new DatagramPacket(bytes, bytes.length,
                        peerAddress, sourcePort);

                // A HELLO is authenticated presence, not yet a verified return route.
                // Commit it as suspect before replying; otherwise the other side could trust
                // a peer that failed to retain the route needed for its own retry path.
                LanChatStore store = lanChatStore;
                if (store == null) return true;
                final String peerType = envelope.optString("sender_peer_type", "unknown");
                final String peerNetwork = envelope.optString("network_scope", "");
                final String peerIp = peerAddress.getHostAddress();
                final boolean challengeOnly = envelope.optBoolean("challenge_only", false);
                try {
                    lanChatPeerExecutor.execute(() -> {
                        long peerQueueMs = android.os.SystemClock.elapsedRealtime()
                                - packetReceivedAtElapsedMs;
                        if (peerQueueMs >= 150L) {
                            FileLogger.log(this, "CHAT", "P2P peer.hello peer queueMs="
                                    + peerQueueMs + " peer=" + senderId);
                        }
                        try {
                            if (!store.observePeer(senderId, peerType, serviceId, peerNetwork,
                                    peerIp, sourcePort, now, now + 120_000L)) {
                                FileLogger.log(this, "CHAT", "⚠️ peer HELLO 持久化未确认，不发送 peer.ack");
                                return;
                            }
                            sendLanUdpPacket(socket, response, "PEER_ACK_V2");
                            // A normal beacon also challenges its sender once, allowing an
                            // existing sender to rediscover this device and resume its outbox.
                            // challenge_only prevents challenge ping-pong.
                            if (!challengeOnly) {
                                java.net.InetSocketAddress verified = verifiedChatPeers.get(senderId);
                                boolean sameRoute = verified != null
                                        && verified.getAddress().equals(peerAddress)
                                        && verified.getPort() == sourcePort;
                                if (!sameRoute) sendDirectedPeerChallenge(socket, senderId, peerAddress, sourcePort);
                            }
                        } catch (Exception persistOrSendError) {
                            FileLogger.log(this, "CHAT", "⚠️ peer HELLO 未能持久化/应答: "
                                    + persistOrSendError.getClass().getSimpleName());
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    FileLogger.log(this, "CHAT", "⚠️ peer HELLO 队列已满，暂不确认，等待对端重发");
                }
                return true;
            }
            if ("peer.ack".equals(type)) {
                String nonce = envelope.optString("reply_nonce", "");
                java.util.concurrent.CountDownLatch latch = pendingPeerChallenges.get(nonce);
                int port = packet.getPort();
                final java.net.InetAddress peerAddress = packet.getAddress();
                String expectedPeerId = pendingPeerChallengeExpectedIds.get(nonce);
                java.net.InetSocketAddress expectedEndpoint =
                        pendingPeerChallengeExpectedEndpoints.get(nonce);
                boolean validStandardPort = port == 8888 || port == 8887
                        || port == 8889 || port == 8890;
                boolean directedChallengeMatches = expectedPeerId == null
                        ? expectedEndpoint == null
                        : expectedPeerId.equals(senderId) && expectedEndpoint != null
                        && expectedEndpoint.getAddress().equals(peerAddress)
                        && expectedEndpoint.getPort() == port;
                if (latch != null && !senderId.isEmpty() && !senderId.equals(getUniqueDeviceId())
                        && validStandardPort && directedChallengeMatches) {
                    // Directed challenges are single-use; broadcast nonces remain open briefly
                    // so every peer in the bounded discovery window can be collected.
                    if (expectedPeerId != null) {
                        if (!pendingPeerChallenges.remove(nonce, latch)) return true;
                        pendingPeerChallengeExpectedIds.remove(nonce);
                        pendingPeerChallengeExpectedEndpoints.remove(nonce);
                    }
                    LanChatStore store = lanChatStore;
                    if (store == null) return true;
                    final String peerType = envelope.optString("sender_peer_type", "unknown");
                    final String peerNetwork = envelope.optString("network_scope", "");
                    try {
                        lanChatControlExecutor.execute(() -> {
                            long controlQueueMs = android.os.SystemClock.elapsedRealtime()
                                    - packetReceivedAtElapsedMs;
                            if (controlQueueMs >= 150L) {
                                FileLogger.log(this, "CHAT", "P2P peer.ack control queueMs="
                                        + controlQueueMs + " peer=" + senderId);
                            }
                            boolean persisted = store.upsertPeer(senderId, peerType, serviceId,
                                    peerNetwork, peerAddress.getHostAddress(), port, now, now + 120_000L);
                            if (!persisted) {
                                FileLogger.log(this, "CHAT", "⚠️ peer.ack 路由持久化失败，暂不启用单播");
                                return;
                            }
                            // upsertPeer() atomically commits this verified route and joins all
                            // still-open first-discovery target snapshots in the same transaction.
                            synchronized (verifiedChatPeerRouteVersions) {
                                verifiedChatPeerRouteVersions.put(senderId,
                                        verifiedChatPeerRouteVersionCounter.incrementAndGet());
                                verifiedChatPeers.put(senderId,
                                        new java.net.InetSocketAddress(peerAddress, port));
                                verifiedChatPeerSeen.put(senderId, System.currentTimeMillis());
                            }
                            latch.countDown();
                            scheduleLanChatTask("peer-verified-retry", () -> retryPendingLanChatMessages(true), 350L);
                        });
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        FileLogger.log(this, "CHAT", "⚠️ peer.ack 持久化队列已满，暂不启用单播");
                    }
                }
                return true;
            }
            if ("chat.ack".equals(type)) {
                String messageId = envelope.optString("message_id", "");
                String targetId = envelope.optString("target_peer_id", "");
                int sourcePort = packet.getPort();
                if (messageId.isEmpty() || !getUniqueDeviceId().equals(targetId)
                        || !"stored".equals(envelope.optString("status", ""))
                        || !(sourcePort == 8888 || sourcePort == 8887
                             || sourcePort == 8889 || sourcePort == 8890)) {
                    return true;
                }
                final java.net.InetAddress sourceAddress = packet.getAddress();
                final LanChatStore store = lanChatStore;
                try {
                    lanChatAckExecutor.execute(() -> processAuthenticatedLanChatAck(
                            messageId, senderId, sourceAddress, sourcePort, store,
                            packetReceivedAtElapsedMs, true));
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    FileLogger.log(this, "CHAT", "⚠️ LAN ACK IO 队列已满，保留发送端待确认状态 message="
                            + messageId + " peer=" + senderId);
                }
                return true;
            }
            if ("chat.message".equals(type)) {
                String messageId = envelope.optString("message_id", "");
                String targetId = envelope.optString("target_peer_id", "");
                String body = envelope.optString("body", "");
                if (senderId.isEmpty() || senderId.equals(getUniqueDeviceId())
                        || messageId.isEmpty()
                        || !(targetId.equals("*") || targetId.equals(getUniqueDeviceId()))) return true;
                String receiveKey = senderId + ":" + messageId;
                if (!pendingLanChatReceives.add(receiveKey)) {
                    // A copy of this message is already being durably committed. Do not
                    // enqueue duplicate work; the in-flight copy will ACK after persistence.
                    return true;
                }
                final java.net.InetAddress ackAddress = packet.getAddress();
                final int ackPort = packet.getPort();
                try {
                    lanChatIoExecutor.execute(() -> {
                        long dispatchQueueMs = android.os.SystemClock.elapsedRealtime()
                                - packetReceivedAtElapsedMs;
                        try {
                            boolean stored = persistIncomingLanChat(senderId, messageId, body,
                                    envelope.optString("display_name", senderId));
                            long receiveTotalMs = android.os.SystemClock.elapsedRealtime()
                                    - packetReceivedAtElapsedMs;
                            if (dispatchQueueMs >= 150L || receiveTotalMs >= 300L) {
                                FileLogger.log(this, "CHAT", "P2P 接收耗时 message=" + messageId
                                        + " queueMs=" + dispatchQueueMs
                                        + " durableTotalMs=" + receiveTotalMs
                                        + " stored=" + stored);
                            }
                            if (stored) {
                                boolean ackSent = sendAuthenticatedChatAck(
                                        socket, ackAddress, ackPort, senderId, messageId);
                                // The SQLite inbox commit is the delivery boundary. Project
                                // into SharedPreferences/UI only after the ACK has been dispatched.
                                scheduleLanChatInboxProjection();
                                FileLogger.log(this, "CHAT", "P2P RX durable message=" + messageId
                                        + " sender=" + senderId + " persisted=true ackSent=" + ackSent);
                            } else {
                                FileLogger.log(this, "CHAT", "P2P RX message=" + messageId
                                        + " sender=" + senderId + " persisted=false ackSent=false");
                            }
                        } finally {
                            pendingLanChatReceives.remove(receiveKey);
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    pendingLanChatReceives.remove(receiveKey);
                }
                return true;
            }
        } catch (Exception e) {
            FileLogger.logD("UDP", "authenticated LAN packet rejected: " + e.getClass().getSimpleName());
        }
        return true;
    }

    /**
     * Persist a chat ACK only after its sender endpoint is verified. During an open
     * first-discovery window, allow one short asynchronous recheck so a fast chat.ack
     * cannot lose a race against the peer.ack route-persistence task on the IO pool.
     * This grace never bypasses either routeMatches or persistedEndpointMatch.
     */
    private void processAuthenticatedLanChatAck(String messageId, String senderId,
            java.net.InetAddress sourceAddress, int sourcePort, LanChatStore store,
            long packetReceivedAtElapsedMs, boolean allowDiscoveryGrace) {
        if (store == null || sourceAddress == null) return;
        long ackQueueMs = android.os.SystemClock.elapsedRealtime() - packetReceivedAtElapsedMs;
        long ackPersistStarted = android.os.SystemClock.elapsedRealtime();
        java.net.InetSocketAddress verified = verifiedChatPeers.get(senderId);
        boolean routeMatches = verified != null
                && verified.getAddress().equals(sourceAddress)
                && verified.getPort() == sourcePort;
        // A live authenticated route already proves this exact sender endpoint. Avoid a
        // redundant SQLite endpoint lookup on the common ACK fast path; do not label the
        // skipped persisted check as a match in diagnostics.
        boolean persistedEndpointMatch = !routeMatches
                && store.matchesExpectedAckEndpoint(messageId, senderId,
                        sourceAddress.getHostAddress(), sourcePort);
        boolean discoveryAck = false;
        // A verified live route or the message's exact attempted/successful endpoint is
        // already sufficient proof. Only the unverified first-discovery grace needs this
        // additional SQLite state read.
        if (!routeMatches && !persistedEndpointMatch) {
            try {
                discoveryAck = store.isDiscoveryPending(messageId);
            } catch (Exception storageFailure) {
                FileLogger.log(this, "CHAT", "⚠️ LAN ACK 发现窗口读取失败，拒绝释放发现观察槽: "
                        + storageFailure.getClass().getSimpleName());
            }
        }
        if (!routeMatches && !persistedEndpointMatch) {
            if (allowDiscoveryGrace && discoveryAck) {
                String recheckKey = messageId + ":" + senderId + ":"
                        + sourceAddress.getHostAddress() + ":" + sourcePort;
                if (!pendingChatAckRouteRechecks.add(recheckKey)) return;
                boolean scheduled = scheduleLanChatTask("chat-ack-route-recheck", () -> {
                    pendingChatAckRouteRechecks.remove(recheckKey);
                    try {
                        lanChatAckExecutor.execute(() -> processAuthenticatedLanChatAck(
                                messageId, senderId, sourceAddress, sourcePort, store,
                                packetReceivedAtElapsedMs, false));
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        FileLogger.log(this, "CHAT", "⚠️ LAN ACK 路由复核队列已满，继续保留待确认 message="
                                + messageId + " peer=" + senderId);
                    }
                // Give the parallel peer.ack IO task one short turn to persist the verified route.
                }, 200L);
                if (scheduled) {
                    FileLogger.logD("CHAT", "P2P ACK 暂缓复核握手路由 message="
                            + messageId + " peer=" + senderId + " graceMs=200 queueMs=" + ackQueueMs);
                    return;
                }
                pendingChatAckRouteRechecks.remove(recheckKey);
            }
            long ackDecisionMs = android.os.SystemClock.elapsedRealtime() - packetReceivedAtElapsedMs;
            FileLogger.log(this, "CHAT", "P2P ACK rejected message=" + messageId
                    + " peer=" + senderId
                    + " source=" + sourceAddress.getHostAddress() + ":" + sourcePort
                    + " routeMatch=" + routeMatches
                    + " persistedSendEndpointMatch=" + persistedEndpointMatch
                    + " discoveryPending=" + discoveryAck
                    + " queueMs=" + ackQueueMs
                    + " totalMs=" + ackDecisionMs
                    + " reason=unverified_endpoint");
            return;
        }
        // Re-read the current latch/expected set after any asynchronous route grace.
        java.util.concurrent.CountDownLatch latch = pendingChatAcks.get(messageId);
        java.util.Set<String> expected = pendingChatAckPeers.get(messageId);
        boolean persistedAck = store.acknowledge(messageId, senderId);
        if (persistedAck) {
            boolean wasExpected = expected != null && expected.remove(senderId);
            if (routeMatches) {
                synchronized (verifiedChatPeerRouteVersions) {
                    if (verifiedChatPeers.get(senderId) == verified) {
                        verifiedChatPeerRouteVersions.put(senderId,
                                verifiedChatPeerRouteVersionCounter.incrementAndGet());
                        verifiedChatPeerSeen.put(senderId, System.currentTimeMillis());
                    }
                }
            }
            boolean discoveryBroadcastAck = discoveryAck && expected != null
                    && expected.isEmpty() && latch != null && latch.getCount() > 0;
            if (latch != null && (wasExpected || discoveryBroadcastAck)) latch.countDown();
            if (store.allTargetsAcked(messageId)) {
                scheduleLanChatMirrorCleanup(messageId);
                FileLogger.log(this, "CHAT", "P2P 最终 ACK 已落账；兼容队列清理已异步排队 message="
                        + messageId + " peer=" + senderId);
            }
        } else {
            FileLogger.log(this, "CHAT", "⚠️ v2 ACK 账本写入失败，等待重试: "
                    + messageId + " peer=" + senderId);
        }
        long ackTotalMs = android.os.SystemClock.elapsedRealtime() - packetReceivedAtElapsedMs;
        if (ackQueueMs >= 100L || ackTotalMs >= 200L) {
            FileLogger.log(this, "CHAT", "P2P ACK 处理耗时 message=" + messageId
                    + " queueMs=" + ackQueueMs
                    + " dbMs=" + (android.os.SystemClock.elapsedRealtime() - ackPersistStarted)
                    + " totalMs=" + ackTotalMs
                    + " persisted=" + persistedAck
                    + " endpointMatch=" + persistedEndpointMatch);
        }
    }

    private boolean sendAuthenticatedChatAck(DatagramSocket socket, java.net.InetAddress address,
                                             int port, String targetPeerId, String messageId) {
        try {
            if (socket == null || socket.isClosed() || address == null) return false;
            JSONObject ack = LanP2pProtocol.create("chat.ack", getUniqueDeviceId(), "android", "android-main");
            ack.put("target_peer_id", targetPeerId);
            ack.put("message_id", messageId);
            ack.put("status", "stored");
            byte[] bytes = LanP2pProtocol.encode(ack, RESCUE_SECRET);
            DatagramPacket response = new DatagramPacket(bytes, bytes.length, address, port);
            sendLanUdpPacket(socket, response, "CHAT_ACK_V2");
            return !socket.isClosed();
        } catch (Exception e) {
            FileLogger.log(this, "CHAT", "⚠️ v2 ACK 发送失败 message=" + messageId
                    + " peer=" + targetPeerId + " error=" + e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean handleLanRealtimePacket(DatagramSocket socket, DatagramPacket packet,
                                              String message, long packetReceivedAtElapsedMs) {
        try {
            if (message.startsWith("{")) {
                try {
                    JSONObject envelope = LanP2pProtocol.decode(message.getBytes("UTF-8"), RESCUE_SECRET);
                    return handleAuthenticatedLanPacket(socket, packet, envelope, packetReceivedAtElapsedMs);
                } catch (Exception invalid) {
                    FileLogger.logD("UDP", "invalid authenticated LAN JSON frame: "
                            + invalid.getClass().getSimpleName());
                    return true;
                }
            }
        } catch (Exception e) {
            FileLogger.logD("UDP", "LAN realtime packet handling failed: "
                    + e.getClass().getSimpleName());
            return true;
        }
        return false;
    }

    private static final int RECENT_EXECUTIONS_MAX = 200;

    /**
     * ★ 强力补丁 3：重构去重机制（利用 putIfAbsent 的原子性阻断高并发裂缝）。
     *
     * putIfAbsent 是硬件级原子操作：
     *   - 如果 key 已存在，返回旧值（非 null），说明可能重复；
     *   - 不存在，写入并返回 null，说明是首次。
     * 在 60 秒窗口内命中旧值 → 判重返回 true；
     * 命中但已过期 → 用当前时间刷新该 key，返回 false；
     * 未命中 → 惰性清理超阈值项后返回 false。
     */
    private boolean isRecentlyExecuted(String dedupKey) {
        long now = System.currentTimeMillis();
        Long previous = recentExecutions.putIfAbsent(dedupKey, now);

        if (previous != null) {
            if (now - previous < CMD_TIME_WINDOW_MS) {
                return true;
            }
            // ★ 2026-11-07 修复：原 put 非原子，两个线程同时命中"已过期"分支
            //   都会认为自己是首次，导致同一指令双执行（聊天消息重复）。
            //   改用 replace(key, old, new)，只有真正把旧值换掉的线程返回 true，
            //   竞争失败的一方说明已被他人认领，直接拦截。
            if (!recentExecutions.replace(dedupKey, previous, now)) {
                return true;
            }
        }

        if (recentExecutions.size() > RECENT_EXECUTIONS_MAX) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                    recentExecutions.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, Long> entry = it.next();
                if (now - entry.getValue() > CMD_TIME_WINDOW_MS) it.remove();
            }
        }
        return false;
    }

    /**
     * ★ 强力补丁 4：重写生成去重 Key（区分 HTTP 的 cmdId 和 UDP 的内容）。
     * - custom_msg / chat_msg：按内容去重，覆盖 HTTP/UDP 双通道；
     * - 其它指令：HTTP 携带 cmdId 走 HTTP 命名空间，UDP 走 UDP 命名空间；
     * - 无 cmdId 时回退 cmdType|UDP。
     */
    // ★ 补齐 2 参数重载，兼容 UDP 监听里的老调用，绝不报方法找不到
    private String buildDedupKey(String cmdType, String payload) {
        return buildDedupKey(cmdType, payload, null);
    }

    // 核心 3 参数收网函数
    private String buildDedupKey(String cmdType, String payload, String cmdId) {
        // 强力防呆：如果 cmdType 意外为空，返回唯一随机 Key 直接放行，绝不抛 NPE
        if (cmdType == null) {
            return "UNKNOWN|" + System.nanoTime();
        }

        if ("custom_msg".equals(cmdType) || "chat_msg".equals(cmdType)) {
            return cmdType + "|" + (payload == null ? "" : payload);
        }
        // ★ 2026-11-07 [P1-2]
        if ("force_report_temp".equals(cmdType)) {
            return cmdType + "|" + (payload == null ? "" : payload);
        }

        // 如果有 cmdId，说明是 HTTP 下发的（用 cmdId 精确隔离，防误杀）
        if (cmdId != null && !cmdId.isEmpty()) {
            return cmdType + "|HTTP|" + cmdId;
        }

        // 如果没有 cmdId，说明是 UDP 广播（用类型隔离）
        return cmdType + "|UDP";
    }

    /**
     * Retained legacy local chat presentation helper.
     * The v2 LAN transport uses persistIncomingLanChat() so message_id, Inbox and
     * history commit are coordinated before the stored ACK is sent.
     */
    private void handleIncomingChat(String senderId, String msgContent) {
        // 自己发的忽略
        if (senderId.equals(getUniqueDeviceId())) return;

        // 3 秒内同内容去重（子网+全局双发会收到 2 个包）
        String dedupKey = senderId + ":" + msgContent;
        long now = System.currentTimeMillis();
        if (dedupKey.equals(lastReceivedMsg) && (now - lastReceivedMsgTime < 3000)) return;
        lastReceivedMsg = dedupKey;
        lastReceivedMsgTime = now;

        // 短名称：去掉 device_id 末尾的 _xxxx 后缀
        String shortName = senderId;
        if (shortName.contains("_")) shortName = shortName.substring(0, shortName.lastIndexOf("_"));

        saveChatToHistory("[" + shortName + "] " + msgContent);
        FileLogger.log(this, "CHAT", "收到来自 " + shortName + " 的 LAN 聊天消息（正文不写入日志）");
        notifyChatUi();
    }

    /**
     * Refresh an already-persisted chat history without adding a duplicate record.
     * Used when a retransmission recovers a crash between history commit and UI notification.
     */
    private void scheduleLanChatUiRefresh() {
        handler.post(() -> {
            if (!serviceDestroyed) notifyChatUi();
        });
    }

    /**
     * Compatibility overload for callers without a display name.
     * A successful SQLite inbox commit is the durable delivery boundary; UI history
     * is projected asynchronously and can be replayed after process recovery.
     */
    private boolean persistIncomingLanChat(String senderId, String messageId, String msgContent) {
        return persistIncomingLanChat(senderId, messageId, msgContent, senderId);
    }

    private boolean persistIncomingLanChat(String senderId, String messageId, String msgContent,
                                           String displayName) {
        if (senderId == null || senderId.isEmpty() || messageId == null || messageId.isEmpty()) return false;
        LanChatStore store = lanChatStore;
        if (store == null) return false;
        long inboxNow = System.currentTimeMillis();
        String displayTime = new java.text.SimpleDateFormat(
                "HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(inboxNow));
        long persistStarted = android.os.SystemClock.elapsedRealtime();
        boolean stored = store.storeIncoming("android:" + getUniqueDeviceId(),
                senderId, messageId, msgContent, displayName, inboxNow, displayTime);
        long persistMs = android.os.SystemClock.elapsedRealtime() - persistStarted;
        if (persistMs >= 50L || !stored) {
            FileLogger.log(this, "CHAT", "P2P inbox SQLite commit message=" + messageId
                    + " dbMs=" + persistMs + " stored=" + stored);
        }
        return stored;
    }

    /**
     * Project durable inbox rows to the existing chat-history preference asynchronously.
     * If the process dies after ACK but before this projection, displayed=0 survives and
     * the next service start replays it. The stable ID list makes the projection idempotent.
     */
    private void scheduleLanChatInboxProjection() {
        if (serviceDestroyed || lanChatStore == null) return;
        // A request coalesced while the worker is active must be observed after its
        // current snapshot; otherwise a just-committed inbox row can remain undisplayed.
        lanChatInboxProjectionRequested.set(true);
        if (!lanChatInboxProjectionInFlight.compareAndSet(false, true)) return;
        try {
            lanChatIoExecutor.execute(() -> {
                boolean morePending = false;
                try {
                    lanChatInboxProjectionRequested.set(false);
                    morePending = projectUndisplayedLanChatInbox();
                } catch (Exception e) {
                    morePending = true;
                    FileLogger.log(this, "CHAT", "LAN inbox UI projection deferred: "
                            + e.getClass().getSimpleName());
                } finally {
                    lanChatInboxProjectionInFlight.set(false);
                }
                if (serviceDestroyed) return;
                if (morePending) {
                    lanChatInboxProjectionRequested.set(true);
                    scheduleLanChatTask("inbox-ui-projection",
                            this::scheduleLanChatInboxProjection, 500L);
                } else if (lanChatInboxProjectionRequested.getAndSet(false)) {
                    // A new durable row arrived after the current worker loaded its snapshot.
                    scheduleLanChatInboxProjection();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            lanChatInboxProjectionInFlight.set(false);
            if (!serviceDestroyed) {
                scheduleLanChatTask("inbox-ui-projection-retry",
                        this::scheduleLanChatInboxProjection, 500L);
            }
        }
    }

    /**
     * Returns true when another batch may remain. The history commit precedes displayed=1;
     * a crash between them is safe because the persisted stable ID prevents duplicate append.
     */
    private boolean projectUndisplayedLanChatInbox() {
        LanChatStore store = lanChatStore;
        if (store == null || serviceDestroyed) return false;
        final String receiverScope = "android:" + getUniqueDeviceId();
        synchronized (lanChatReceiveLock) {
            List<LanChatStore.IncomingMessage> pending =
                    store.loadUndisplayedIncoming(receiverScope, 100);
            if (pending.isEmpty()) return false;
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            org.json.JSONArray ids;
            try {
                ids = new org.json.JSONArray(sp.getString(PREF_LAN_CHAT_RECEIVED_IDS, "[]"));
            } catch (org.json.JSONException malformedIds) {
                // SharedPreferences commit stores history and stable IDs together. If the
                // ID index is corrupt, rebuild it from the still-durable undisplayed inbox
                // rows rather than crashing the projection worker or withholding future UI.
                FileLogger.log(this, "CHAT", "LAN inbox stable-ID index malformed; rebuilding from durable inbox");
                ids = new org.json.JSONArray();
            }
            java.util.HashSet<String> knownIds = new java.util.HashSet<>();
            for (int i = 0; i < ids.length(); i++) knownIds.add(ids.optString(i, ""));
            String history = sp.getString("chat_history", "");
            StringBuilder updated = new StringBuilder(history);
            boolean historyChanged = false;
            for (LanChatStore.IncomingMessage item : pending) {
                if (item.senderPeerId == null || item.messageId == null) continue;
                String stableId = item.senderPeerId + ":" + item.messageId;
                if (knownIds.add(stableId)) {
                    String name = item.displayName == null || item.displayName.isEmpty()
                            ? item.senderPeerId : item.displayName;
                    if ((item.displayName == null || item.displayName.isEmpty()) && name.contains("_")) {
                        name = name.substring(0, name.lastIndexOf("_"));
                    }
                    String time = item.displayTime == null || item.displayTime.isEmpty()
                            ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(new java.util.Date())
                            : item.displayTime;
                    if (updated.length() > 0) updated.append("\n");
                    updated.append(time).append(" | [").append(name).append("] ").append(item.body);
                    ids.put(stableId);
                    historyChanged = true;
                }
            }
            while (ids.length() > 500) {
                org.json.JSONArray trimmed = new org.json.JSONArray();
                for (int i = ids.length() - 500; i < ids.length(); i++) {
                    trimmed.put(ids.optString(i, ""));
                }
                ids = trimmed;
            }
            String updatedHistory = updated.toString();
            String[] records = updatedHistory.split("\n");
            if (records.length > 100) {
                StringBuilder trimmedHistory = new StringBuilder();
                for (int i = records.length - 100; i < records.length; i++) {
                    if (trimmedHistory.length() > 0) trimmedHistory.append("\n");
                    trimmedHistory.append(records[i]);
                }
                updatedHistory = trimmedHistory.toString();
                historyChanged = true;
            }
            SharedPreferences.Editor editor = sp.edit().putString(PREF_LAN_CHAT_RECEIVED_IDS, ids.toString());
            if (historyChanged) editor.putString("chat_history", updatedHistory);
            if (!editor.commit()) {
                FileLogger.log(this, "CHAT", "LAN inbox UI projection commit failed; rows remain replayable");
                return true;
            }
            for (LanChatStore.IncomingMessage item : pending) {
                if (item.senderPeerId == null || item.messageId == null) continue;
                if (!store.markIncomingDisplayed(receiverScope, item.senderPeerId, item.messageId)) {
                    FileLogger.log(this, "CHAT", "LAN inbox displayed marker deferred message=" + item.messageId);
                    return true;
                }
            }
            if (historyChanged) scheduleLanChatUiRefresh();
            return pending.size() >= 100;
        }
    }

    private void startRescueListener() {
        final long listenerGeneration = lanSocketGeneration.get();
        synchronized (rescueLock) {
            if (isListeningForRescue) {
                FileLogger.log(this, "UDP", "⚠️ 监听已在运行，跳过重复启动");
                return;
            }
            isListeningForRescue = true;
        }

        if (multicastLock != null && !multicastLock.isHeld()) {
            try {
                multicastLock.acquire();
                FileLogger.log(this, "UDP", "🔓 MulticastLock 已获取");
            } catch (Exception e) {
                FileLogger.log(this, "UDP", "⚠️ MulticastLock 获取失败: " + e.getMessage());
            }
        }

        new Thread(() -> {
            DatagramSocket rescueSocket = null;
            try {
                rescueSocket = new DatagramSocket(null);
                rescueSocket.setReuseAddress(true);
                rescueSocket.setBroadcast(true);
                Network boundWifiNetwork = bindSocketToWifi(rescueSocket);
                rescueSocket.bind(new InetSocketAddress(8888));
                rescueSocket.setSoTimeout(3000);
                synchronized (rescueLock) {
                    if (serviceDestroyed || listenerGeneration != lanSocketGeneration.get()) {
                        throw new java.net.SocketException("Wi-Fi LAN generation changed during UDP bind");
                    }
                    // Publish the route before the volatile socket reference; readers that
                    // observe this socket must also observe its exact bound Network.
                    lanChatBoundWifiNetwork = boundWifiNetwork;
                    rescueSocketRef = rescueSocket;
                }
                udpBindFailCount = 0;
                FileLogger.log(this, "UDP", "✅ 监听已绑定 8888");
                // 启动/重绑定后主动通告一次本机 LAN peer，唤醒其他节点的离线消息补发。
                scheduleLanChatTask("listener-peer-beacon", this::broadcastLanPeerHello, 300L);
                // 初次启动及网络切换重绑定后统一恢复待确认队列；此时 socket 已可用。
                scheduleLanChatTask("listener-outbox-recovery", () -> retryPendingLanChatMessages(true), 1200L);

                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                java.util.Map<String, Long> udpCmdDedup = new java.util.HashMap<>();

                while (!serviceDestroyed) {
                    try {
                        packet.setLength(buffer.length);
                        rescueSocket.receive(packet);
                    } catch (java.net.SocketTimeoutException te) {
                        continue;
                    }
                    long udpReceiveElapsedMs = android.os.SystemClock.elapsedRealtime();
                    String message = new String(packet.getData(), 0, packet.getLength());
                    // ACK/握手优先快路径：不经过普通命令分发和完整包日志，尽快释放发送端等待。
                    if (handleLanRealtimePacket(rescueSocket, packet, message, udpReceiveElapsedMs)) {
                        continue;
                    }
                    // 只记录命令前缀与来源，不记录认证字段、聊天正文或完整协议包。
                    int typeEnd = message.indexOf(':');
                    String packetType = typeEnd > 0 ? message.substring(0, typeEnd) : "UNKNOWN";
                    // 不逐包写 logcat；仅在下方达到慢处理阈值时记录安全的命令类型。

                    if (message.startsWith("FIND_ME_ALARM:")
                            || message.startsWith("FIND_ME_TEST_LOW_BATTERY:")
                            ) {
                        String dedupKey = message.length() > 64 ? message.substring(0, 64) : message;
                        long nowMs = System.currentTimeMillis();
                        Long last = udpCmdDedup.get(dedupKey);
                        if (last != null && nowMs - last < 3000) {
                            continue;
                        }
                        udpCmdDedup.put(dedupKey, nowMs);
                        if (udpCmdDedup.size() > 50) {
                            long cutoff = nowMs - 30000;
                            java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                                    udpCmdDedup.entrySet().iterator();
                            while (it.hasNext()) {
                                if (it.next().getValue() < cutoff) it.remove();
                            }
                        }
                    }

                    if (message.startsWith("FIND_ME_SERVER:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            // ★ 改动2：先判重后执行，防路由器/多网卡抖动导致连环 change_server + break
                            String newUrl = parts[2].trim();
                            String dk = buildDedupKey("change_server", newUrl, null);
                            if (!isRecentlyExecuted(dk)) {
                                saveNewServerUrl(newUrl);
                                consecutiveFailures = 0;
                                sendReport(true, true);
                                break; // 首次执行才 break，触发 socket 重连
                            }
                            // 重复包：不 break，保持当前 socket 连接不抖动
                        }
                    } else if (message.startsWith("FIND_ME_CONFIG:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) handleUpdateConfigUrl(parts[2].trim());
                    } else if (message.startsWith("FIND_ME_REPORT:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            // ★ 改动2：判重拦截，同一个包送 3 次也不会连环强报
                            String dk = buildDedupKey("force_report", null, null);
                            if (!isRecentlyExecuted(dk)) {
                                sendReport(true, true);
                            }
                        }
                    } else if (message.startsWith("FIND_ME_REPORT_TEMP:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            String tempUrl = parts[2].trim();
                            // ★ 改动2：tempUrl 参与 dedup，不同临时 URL 可独立执行，同 URL 重复包被拦截
                            String dk = buildDedupKey("force_report_temp", tempUrl, null);
                            if (!isRecentlyExecuted(dk)) {
                                if (isValidUrl(tempUrl)) {
                                    sendReport(true, true, tempUrl);
                                } else {
                                    FileLogger.log(this, "REPORT", "⚠️ 忽略非法 FIND_ME_REPORT_TEMP URL: [" + tempUrl + "]");
                                }
                            }
                        }
                    // ★ 插件旁路挂载：远程陪伴
                    } else if (message.startsWith("FIND_ME_PUSH_") || message.startsWith("FIND_ME_PLUGIN:companion:")) {
                        final String companionMessage = message;
                        handler.post(() -> {
                            if (!serviceDestroyed) CompanionModule.handleUdp(TrackingService.this, companionMessage);
                        });
                    } else if (message.startsWith("FIND_ME_ALARM:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            // ★ 改动2：判重拦截，多包不会连环响铃
                            String dk = buildDedupKey("play_alarm", null, null);
                            if (!isRecentlyExecuted(dk)) {
                                // 音频系统调用不占用 UDP 接收线程。
                                handler.post(() -> { if (!serviceDestroyed) playAlarm(); });
                            }
                        }
                    }
                    // ===== [P0-1] 悬浮文字：v2 新前缀 =====
                    else if (message.startsWith(FIND_ME_FLOAT_MSG + ":")) {
                        String[] floatParts = message.split(":", 3);
                        if (floatParts.length == 3 && RESCUE_SECRET.equals(floatParts[1])) {
                            String content = floatParts[2].trim();
                            String dk = buildDedupKey("custom_msg", content, null);
                            if (!isRecentlyExecuted(dk)) {
                                showFloatingMessage(content);
                                // ★ 改动1：删除冗余 recentExecutions.put，isRecentlyExecuted 内 putIfAbsent 已写
                            }
                        }
                    }
                    else if (message.startsWith("FIND_ME_CLEAR_HISTORY:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            clearMessageHistory();
                            recentExecutions.put("clear_history", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_CLEAR_CHAT:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putString("chat_history", "").apply();
                            notifyChatUi();
                            FileLogger.log(TrackingService.this, "CHAT", "🧹 收到云端指令，已清空本地聊天记录");
                            recentExecutions.put("clear_chat", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_SET_SMS_NUM:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            String newNum = parts[2].trim();
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putString("emergency_sms_number", newNum).apply();
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "紧急号码已更新: " + newNum, android.widget.Toast.LENGTH_LONG).show());
                            recentExecutions.put("set_sms_num", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_CLOUD_LOG:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length == 3 && RESCUE_SECRET.equals(parts[1])) {
                            boolean enable = "1".equals(parts[2].trim());
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit().putBoolean("enable_cloud_log", enable).apply();
                            String stateMsg = enable ? "☁️ 云端日志已开启" : "☁️ 云端日志已关闭";
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, stateMsg, android.widget.Toast.LENGTH_SHORT).show());
                            recentExecutions.put("set_cloud_log", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_CHAT_TOGGLE:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            boolean currentLocked = isChatLocked(); setChatLocked(!currentLocked);
                            String stateMsg = !currentLocked ? "🔒 聊天功能已锁定" : "🔓 聊天功能已解锁";
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, stateMsg, android.widget.Toast.LENGTH_SHORT).show());
                        }
                    } else if (message.startsWith("FIND_ME_CHAT_UNLOCK:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            setChatLocked(false);
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "🔓 聊天功能已解锁", android.widget.Toast.LENGTH_SHORT).show());
                            recentExecutions.put("unlock_chat", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_CHAT_LOCK:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            setChatLocked(true);
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this, "🔒 聊天功能已锁定", android.widget.Toast.LENGTH_SHORT).show());
                            recentExecutions.put("lock_chat", System.currentTimeMillis());
                        }
                    } else if (message.startsWith("FIND_ME_ELDER_MODE:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length >= 2 && RESCUE_SECRET.equals(parts[1])) {
                            boolean target;
                            if (parts.length >= 3) {
                                String v = parts[2].trim();
                                target = "1".equals(v) || "true".equalsIgnoreCase(v);
                            } else target = !isElderMode();
                            setElderMode(target);
                            handler.post(() -> android.widget.Toast.makeText(TrackingService.this,
                                    target ? "👴 老人模式已开启" : "🕶️ 无痕模式已开启",
                                    android.widget.Toast.LENGTH_SHORT).show());
                        }
                    } else if (message.startsWith("FIND_ME_UPLOAD_LOG:")) {
                        String rest = message.substring("FIND_ME_UPLOAD_LOG:".length());
                        int secretEnd = rest.indexOf(":");
                        String secret = secretEnd < 0 ? rest : rest.substring(0, secretEnd);
                        String payload = secretEnd < 0 ? "" : rest.substring(secretEnd + 1).trim();
                        if (RESCUE_SECRET.equals(secret)) {
                            boolean clearAfter = payload.endsWith(":clear") || "clear".equals(payload);
                            String urlPart = payload;
                            if (urlPart.endsWith(":clear")) urlPart = urlPart.substring(0, urlPart.length() - 6);
                            if ("clear".equals(urlPart)) urlPart = "";
                            String tempUrl = null;
                            if (!urlPart.isEmpty()) {
                                if (isValidUrl(urlPart)) tempUrl = urlPart;
                                else FileLogger.log(this, "UPLOAD_LOG", "⚠️ 忽略非法 tempUrl: [" + urlPart + "]（不含 :// 或过短），使用主服务器");
                            }
                            // ★ 2026-11-07 [P1-1]
                            String dkUp = buildDedupKey("upload_log",
                                    (tempUrl == null ? "" : tempUrl) + (clearAfter ? ":clear" : ""), null);
                            if (!isRecentlyExecuted(dkUp)) {
                                uploadLog(tempUrl, clearAfter);
                            }
                        }
                    } else if (message.startsWith("FIND_ME_LOCATE_ONCE:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            // ★ 2026-11-07 [P1-1]
                            String dkLoc = buildDedupKey("locate_once", null, null);
                            if (!isRecentlyExecuted(dkLoc)) {
                                requestSingleLocationAndReport();
                            }
                        }
                    } else if (message.startsWith("FIND_ME_SCAN_WIFI:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            // ★ 2026-11-07 [P1-1]
                            String dkScan = buildDedupKey("scan_wifi", null, null);
                            if (!isRecentlyExecuted(dkScan)) {
                                new Thread(() -> {
                                    List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(this);
                                    FileLogger.log(this, "WIFI", "📡 UDP 触发扫描: " + nearby.size() + " 个热点");
                                }).start();
                            }
                        }
                    } else if (message.startsWith("FIND_ME_RESTART_UDP:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            recentExecutions.put("restart_udp", System.currentTimeMillis());
                            break;
                        }
                    } else if (message.startsWith("FIND_ME_TEST_LOW_BATTERY:")) {
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2 && RESCUE_SECRET.equals(parts[1])) {
                            // ★ 2026-11-07 [P0-1]
                            String dkLow = buildDedupKey("test_low_battery", null, null);
                            if (!isRecentlyExecuted(dkLow)) {
                                FileLogger.log(this, "BATTERY", "🧪 UDP 触发模拟低电量告警");
                                sendReport(true, true);
                                sendLowBatterySms(15);
                            }
                        }
                    }
                    long udpDispatchCostMs = android.os.SystemClock.elapsedRealtime() - udpReceiveElapsedMs;
                    if (udpDispatchCostMs >= 200L) {
                        FileLogger.log(this, "UDP", "⚠️ UDP 接收分支处理偏慢: " + udpDispatchCostMs
                                + "ms, type=" + packetType);
                    }
                }
            } catch (java.net.BindException be) {
                udpBindFailCount++;
                FileLogger.log(this, "UDP", "❌ bind 失败 (第 " + udpBindFailCount + " 次): " + be.getMessage());
            } catch (Exception e) {
                Log.e("TrackingService", "UDP 监听异常: " + e.toString());
                FileLogger.log(this, "UDP", "❌ 监听异常: " + e.getMessage());
            } finally {
                if (rescueSocket != null && !rescueSocket.isClosed()) rescueSocket.close();
                final boolean networkGenerationChanged;
                synchronized (rescueLock) {
                    if (rescueSocketRef == rescueSocket) {
                        rescueSocketRef = null;
                        // The published Network is valid only while its owning listener socket is current.
                        lanChatBoundWifiNetwork = null;
                    }
                    isListeningForRescue = false;
                    // A deliberate Wi-Fi path replacement is not a socket failure. Its old
                    // listener must hand off immediately instead of entering the 5s+ backoff.
                    networkGenerationChanged = listenerGeneration != lanSocketGeneration.get();
                }

                if (multicastLock != null && multicastLock.isHeld()) {
                    try { multicastLock.release(); } catch (Exception ignored) {}
                }

                if (!serviceDestroyed && networkGenerationChanged) {
                    FileLogger.log(this, "NETWORK", "旧 UDP listener 代际已失效，立即重建 Wi-Fi LAN socket oldGeneration="
                            + listenerGeneration + " newGeneration=" + lanSocketGeneration.get());
                    triggerRescueListener();
                } else if (!serviceDestroyed) {
                    // Genuine bind/receive failures retain exponential backoff; intentional
                    // network-generation handoff above never waits on this failure policy.
                    long retryDelayMs = (long) Math.min(300000, 5000 * Math.pow(3, Math.min(udpBindFailCount, 4)));
                    FileLogger.log(this, "UDP", "监听断开，将在 " + (retryDelayMs / 1000) + " 秒后尝试第 " + (udpBindFailCount + 1) + " 次重连...");

                    handler.postDelayed(() -> {
                        boolean needRestart;
                        synchronized (rescueLock) {
                            needRestart = !isListeningForRescue && !serviceDestroyed;
                        }
                        if (needRestart) {
                            triggerRescueListener();
                        }
                    }, retryDelayMs);
                }
            }
        }).start();
    }

    private void parseResponse(JSONObject res) {
        JSONObject cfg = res.optJSONObject("config");
        if (cfg != null) {
            long newInterval = reportIntervalMs;
            if (cfg.has("report_interval_seconds")) {
                long intervalSeconds = cfg.optLong("report_interval_seconds", 1800L);
                intervalSeconds = Math.max(60L, Math.min(7L * 24L * 60L * 60L, intervalSeconds));
                newInterval = TimeUnit.SECONDS.toMillis(intervalSeconds);
            }
            String newAccuracy = cfg.optString("accuracy_mode", accuracyMode);
            boolean newEnableWifiLock = cfg.optBoolean("enable_wifi_lock", enableWifiLock);
            boolean newEnableMotionTrigger = cfg.optBoolean("enable_motion_trigger", enableMotionTrigger);
            if (newEnableWifiLock != enableWifiLock || newEnableMotionTrigger != enableMotionTrigger) {
                enableWifiLock = newEnableWifiLock;
                enableMotionTrigger = newEnableMotionTrigger;
                toggleMotionSensor(enableMotionTrigger);
            }
            if (newInterval != reportIntervalMs || !newAccuracy.equals(accuracyMode)) {
                // 远端配置同样执行边界校验，避免负数/超大值及乘法溢出。
                newInterval = Math.max(60_000L, Math.min(7L * 24L * 60L * 60L * 1000L, newInterval));
                reportIntervalMs = newInterval;
                accuracyMode = newAccuracy;
                handler.post(() -> { startLocationUpdates(); if (reportCount.get() >= 3) scheduleNextReport(); });
            }
            String nextServerUrl = cfg.optString("next_server_url", "");
            if (!nextServerUrl.isEmpty() && !nextServerUrl.equals(getCurrentServerUrl())) { saveNewServerUrl(nextServerUrl); sendReport(true, true); }
            String nextConfigUrl = cfg.optString("next_config_url", "");
            if (!nextConfigUrl.isEmpty() && !nextConfigUrl.equals(getRemoteConfigUrl())) handleUpdateConfigUrl(nextConfigUrl);
            handleIconVisibility(cfg.optBoolean("hide_icon", true));
        }

        try {
            JSONArray pending = res.optJSONArray("pending_commands");
            if (pending != null && pending.length() > 0) {
                java.util.Set<String> executedIds = loadExecutedCmdIds();
                long now = System.currentTimeMillis();
                int executedCount = 0;

                for (int i = 0; i < pending.length(); i++) {
                    try {
                        JSONObject cmd = pending.getJSONObject(i);
                        String cmdId = cmd.optString("id", "");
                        String cmdType = cmd.optString("type", "");
                        String cmdPayload = cmd.optString("payload", "");

                        if (cmdId.isEmpty() || cmdType.isEmpty()) continue;

                        if (executedIds.contains(cmdId)) continue;
                        // 并发的普通上报与离线补发可能同时解析同一队列；同一进程内只允许一个执行入口。
                        if (commandsInFlight.contains(cmdId)) continue;

                        // chat_msg has a stable Inbox message_id and is safe to replay after
                        // process interruption. Other side-effecting commands keep the no-replay rule.
                        String priorFeedback = getCommandFeedbackStatus(cmdId);
                        boolean retryableChat = "chat_msg".equals(cmdType);
                        if ("accepted".equals(priorFeedback) || "succeeded".equals(priorFeedback)
                                || (!retryableChat && ("uncertain".equals(priorFeedback)
                                || "failed".equals(priorFeedback)))) {
                            executedIds.add(cmdId);
                            continue;
                        }
                        if ("executing".equals(priorFeedback) && !retryableChat) {
                            if (commandsInFlight.contains(cmdId)) continue;
                            recordCommandFeedback(cmdId, cmdType, "uncertain", "进程在执行阶段中断，结果无法确认；为避免重复执行，不自动重放");
                            executedIds.add(cmdId);
                            continue;
                        }
                        recordCommandFeedback(cmdId, cmdType, "received", "设备已收到服务端下发指令");

                        // [P0-2] 用 buildDedupKey 生成 Key，同类型不同 payload 不再互相误杀
                        String dk = buildDedupKey(cmdType, cmdPayload, cmdId);
                        if (isRecentlyExecuted(dk)) {
                            executedIds.add(cmdId);
                            recordCommandFeedback(cmdId, cmdType, "accepted", "检测到同一指令已通过 UDP 通道处理，跳过重复执行");
                            FileLogger.log(TrackingService.this, "HTTP_CMD",
                                    "⏭ 跳过 " + cmdType + "(" + cmdId + ")（60秒内已通过UDP执行, key=" + dk + "）");
                            continue;
                        }

                        commandsInFlight.add(cmdId);
                        recordCommandFeedback(cmdId, cmdType, "executing", "正在调用设备端指令处理入口");
                        boolean commandAccepted;
                        try { commandAccepted = dispatchHttpCommand(cmdType, cmdPayload); }
                        finally { commandsInFlight.remove(cmdId); }
                        if (commandAccepted) {
                            recentExecutions.put(dk, now);
                            executedIds.add(cmdId);
                            executedCount++;
                            recordCommandFeedback(cmdId, cmdType, "accepted", "设备端处理入口已接受；异步任务最终结果如有回调将另行更新");
                            FileLogger.log(TrackingService.this, "HTTP_CMD",
                                    "✅ 执行 HTTP 指令: " + cmdType + "(" + cmdId + ")");
                        } else {
                            recordCommandFeedback(cmdId, cmdType, "retry_wait", "本次处理入口未接受，保留队列等待后续重试");
                            FileLogger.log(TrackingService.this, "HTTP_CMD",
                                    "⚠️ 指令执行未确认成功，保留待重试: " + cmdType + "(" + cmdId + ")");
                        }
                    } catch (Exception e) {
                        FileLogger.log(TrackingService.this, "HTTP_CMD", "⚠️ 解析指令失败: " + e.getMessage());
                    }
                }

                saveExecutedCmdIds(executedIds);

                if (executedCount > 0) {
                    FileLogger.log(TrackingService.this, "HTTP_CMD",
                            "📊 本轮处理 " + pending.length() + " 条指令，实际执行 " + executedCount + " 条");
                }
            }

            int nextPollSeconds = res.optInt("next_poll_seconds", 0);
            if (nextPollSeconds > 0) {
                scheduleFastPoll(nextPollSeconds);
            } else {
                cancelFastPoll();
            }
        } catch (Exception e) {
            FileLogger.log(TrackingService.this, "HTTP_CMD", "❌ 处理 pending_commands 异常: " + e.getMessage());
        }
    }

    private java.util.Set<String> loadExecutedCmdIds() {
        // ★ 2026-11-20 [低危1] 加锁：与 removeExecutedCmdIds 的「读-改-写」互斥，防丢更新
        synchronized (executedCmdIdsLock) {
            java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
            try {
                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                String json = sp.getString(PREF_EXECUTED_CMD_IDS, "");
                if (json != null && !json.isEmpty()) {
                    JSONArray arr = new JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        String id = arr.optString(i, "");
                        if (!id.isEmpty()) result.add(id);
                    }
                }
            } catch (Exception e) {
                FileLogger.log(this, "HTTP_CMD", "⚠️ 加载 executedCmdIds 失败: " + e.getMessage());
            }
            return result;
        }
    }

    private void saveExecutedCmdIds(java.util.Set<String> ids) {
        // ★ 2026-11-20 [低危1] 加锁：与 loadExecutedCmdIds / removeExecutedCmdIds 同锁（可重入）
        synchronized (executedCmdIdsLock) {
            try {
                java.util.LinkedHashSet<String> trimmed = new java.util.LinkedHashSet<>();
                int skip = Math.max(0, ids.size() - EXECUTED_CMD_IDS_MAX);
                int i = 0;
                for (String id : ids) {
                    if (i++ >= skip) trimmed.add(id);
                }
                JSONArray arr = new JSONArray();
                for (String id : trimmed) arr.put(id);
                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                sp.edit().putString(PREF_EXECUTED_CMD_IDS, arr.toString()).apply();
            } catch (Exception e) {
                FileLogger.log(this, "HTTP_CMD", "⚠️ 保存 executedCmdIds 失败: " + e.getMessage());
            }
        }
    }

    /**
     * ★ 2026-11-20 [低危1] 新增：回收服务端已确认（ack）的指令回执 id。
     * 服务端收到 last_executed_cmd_ids 后会从下行队列删除这些指令，本地若不清理则集合只增不减，
     * 每条上报永久多带最多 EXECUTED_CMD_IDS_MAX 个历史 id。与 load/save 共用 executedCmdIdsLock
     * （synchronized 可重入，本方法内部调用二者不会自锁）。
     */
    private void removeExecutedCmdIds(java.util.Collection<String> ackedIds) {
        if (ackedIds == null || ackedIds.isEmpty()) return;
        synchronized (executedCmdIdsLock) {
            try {
                java.util.Set<String> ids = loadExecutedCmdIds();
                int before = ids.size();
                ids.removeAll(ackedIds);
                if (ids.size() == before) return;   // 无交集，省一次磁盘写
                saveExecutedCmdIds(ids);
                FileLogger.log(this, "HTTP_CMD", "🧹 回收已确认指令回执 " + (before - ids.size())
                        + " 条，本地剩余 " + ids.size() + " 条");
            } catch (Exception e) {
                FileLogger.log(this, "HTTP_CMD", "⚠️ 回收 executedCmdIds 失败: " + e.getMessage());
            }
        }
    }

    /** 服务端明确确认已写入 command_feedback 后，才回收本次上报的反馈。 */
    private void ackCommandFeedbackIfConfirmed(JSONObject sentReport, JSONObject response) {
        if (sentReport == null || response == null) return;
        JSONArray sent = sentReport.optJSONArray("command_feedback");
        if (sent == null || sent.length() == 0) return;
        if (response.optInt("feedback_accepted", 0) < sent.length()) {
            FileLogger.log(this, "HTTP_CMD", "ℹ️ 服务端未明确确认全部 command_feedback，保留本地反馈待下次补交");
            return;
        }
        synchronized (COMMAND_FEEDBACK_LOCK) {
            try {
                JSONArray current = loadCommandFeedback();
                JSONArray kept = new JSONArray();
                for (int i = 0; i < current.length(); i++) {
                    JSONObject item = current.optJSONObject(i);
                    if (item == null) continue;
                    boolean acknowledged = false;
                    String id = item.optString("command_id", "");
                    String status = item.optString("status", "");
                    long updatedAt = item.optLong("updated_at", 0L);
                    for (int j = 0; j < sent.length(); j++) {
                        JSONObject ack = sent.optJSONObject(j);
                        if (ack != null && id.equals(ack.optString("command_id", ""))
                                && status.equals(ack.optString("status", ""))
                                && updatedAt == ack.optLong("updated_at", 0L)) {
                            acknowledged = true;
                            break;
                        }
                    }
                    if (!acknowledged) kept.put(item);
                }
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                        .putString(PREF_COMMAND_FEEDBACK, kept.toString()).commit();
            } catch (Exception e) {
                FileLogger.log(this, "HTTP_CMD", "⚠️ 清理已确认 command_feedback 失败: " + e.getMessage());
            }
        }
    }

    private JSONArray loadCommandFeedback() {
        synchronized (COMMAND_FEEDBACK_LOCK) {
            try {
                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                String raw = sp.getString(PREF_COMMAND_FEEDBACK, "[]");
                return new JSONArray(raw != null && !raw.isEmpty() ? raw : "[]");
            } catch (Exception e) {
                FileLogger.log(this, "HTTP_CMD", "⚠️ 读取 command_feedback 失败: " + e.getMessage());
                return new JSONArray();
            }
        }
    }

    private String getCommandFeedbackStatus(String commandId) {
        if (commandId == null || commandId.isEmpty()) return "";
        synchronized (COMMAND_FEEDBACK_LOCK) {
            JSONArray old = loadCommandFeedback();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && commandId.equals(item.optString("command_id", "")))
                    return item.optString("status", "");
            }
        }
        return "";
    }

    /** 同一 command_id 只保留最新反馈；commit 确保断网/进程重启后仍可补交。 */
    private void recordCommandFeedback(String commandId, String action, String status, String detail) {
        if (commandId == null || commandId.isEmpty()) return;
        synchronized (COMMAND_FEEDBACK_LOCK) {
            try {
                JSONArray old = loadCommandFeedback();
                JSONArray next = new JSONArray();
                boolean replaced = false;
                JSONObject entry = new JSONObject();
                entry.put("command_id", commandId);
                entry.put("action", action != null ? action : "");
                entry.put("status", status != null ? status : "received");
                entry.put("detail", detail != null ? detail : "");
                entry.put("updated_at", System.currentTimeMillis());
                for (int i = 0; i < old.length(); i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item == null) continue;
                    if (commandId.equals(item.optString("command_id", ""))) {
                        if (!replaced) {
                            String oldStatus = item.optString("status", "");
                            boolean retryableChat = "chat_msg".equals(action);
                            boolean oldFinal = ("succeeded".equals(oldStatus) || "failed".equals(oldStatus))
                                    && !("failed".equals(oldStatus) && retryableChat);
                            boolean oldTerminalDelivery = ("accepted".equals(oldStatus)
                                    || "uncertain".equals(oldStatus))
                                    && !("uncertain".equals(oldStatus) && retryableChat);
                            boolean newEarly = "received".equals(status) || "executing".equals(status) || "retry_wait".equals(status);
                            // 迟到的早期状态不得覆盖 accepted/uncertain 或最终 succeeded/failed。
                            next.put((oldFinal || (oldTerminalDelivery && newEarly)) ? item : entry);
                            replaced = true;
                        }
                    } else next.put(item);
                }
                if (!replaced) next.put(entry);
                JSONArray trimmed = new JSONArray();
                int start = Math.max(0, next.length() - COMMAND_FEEDBACK_MAX);
                for (int i = start; i < next.length(); i++) trimmed.put(next.get(i));
                boolean ok = getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                        .putString(PREF_COMMAND_FEEDBACK, trimmed.toString()).commit();
                if (!ok) FileLogger.log(this, "HTTP_CMD", "⚠️ command_feedback 本地持久化未确认: " + commandId);
            } catch (Exception e) {
                FileLogger.log(this, "HTTP_CMD", "❌ 保存 command_feedback 失败: " + e.getMessage());
            }
        }
    }

    private boolean dispatchHttpCommand(String type, String payload) {
        if (type == null || type.isEmpty()) return false;
        try {
            switch (type) {
                case "force_report":
                    sendReport(true, true);
                    return true;
                case "play_alarm":
                    playAlarm();
                    return true;
                case "custom_msg":
                    if (payload != null && !payload.isEmpty()) showFloatingMessage(payload);
                    return true;
                case "chat_msg": {
                    if (payload == null || payload.isEmpty()) return true;
                    String trimmedChatPayload = payload.trim();
                    if (trimmedChatPayload.startsWith("{")) {
                        try {
                            JSONObject fallback = new JSONObject(trimmedChatPayload);
                            if ("lan-p2p-v2".equals(fallback.optString("transport", ""))) {
                                String messageId = fallback.optString("message_id", "");
                                String senderPeerId = fallback.optString("sender_peer_id", "");
                                String displayName = fallback.optString("display_name", "Web_Console");
                                String body = fallback.optString("body", "");
                                if (messageId.isEmpty() || senderPeerId.isEmpty()) {
                                    FileLogger.log(this, "CHAT", "⚠️ HTTP chat fallback envelope incomplete; keep queued");
                                    return false;
                                }
                                // HTTP and LAN v2 converge on one durable Inbox key. Whichever
                                // arrives second is an acknowledged duplicate, never a second display.
                                return persistIncomingLanChat(senderPeerId, messageId, body, displayName);
                            }
                        } catch (Exception malformed) {
                            if (trimmedChatPayload.contains("lan-p2p-v2")) {
                                FileLogger.log(this, "CHAT", "⚠️ HTTP chat fallback envelope malformed; keep queued");
                                return false;
                            }
                        }
                    }
                    // Legacy queued plain-text chat remains readable and is not reinterpreted as v2.
                    saveChatToHistory("[Web_Console] " + payload);
                    notifyChatUi();
                    FileLogger.log(this, "CHAT", "收到 HTTP 聊天");
                    return true;
                }
                case "change_server":
                    if (!isValidUrl(payload)) return false;
                    saveNewServerUrl(payload);
                    consecutiveFailures = 0;
                    sendReport(true, true);
                    return true;
                // ★ 2026-11-07 [P1-3]
                case "change_config":
                    if (!isValidUrl(payload)) return false;
                    handleUpdateConfigUrl(payload);
                    return true;
                case "clear_history":
                    clearMessageHistory();
                    return true;
                case "clear_chat":
                    getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                            .edit().putString("chat_history", "").apply();
                    notifyChatUi();
                    return true;
                case "lock_chat":
                    setChatLocked(true);
                    return true;
                case "unlock_chat":
                    setChatLocked(false);
                    return true;
                case "set_sms_num":
                    if (payload != null && !payload.isEmpty()) {
                        getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                                .edit().putString("emergency_sms_number", payload).apply();
                    }
                    return true;
                case "set_cloud_log":
                    getSharedPreferences("device_finder_prefs", MODE_PRIVATE)
                            .edit().putBoolean("enable_cloud_log", "1".equals(payload)).apply();
                    return true;
                case "upload_log":
                    uploadLog(null, false);
                    return true;
                case "locate_once":
                    requestSingleLocationAndReport();
                    return true;
                case "scan_wifi":
                    new Thread(() -> {
                        try {
                            List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this);
                            FileLogger.log(this, "WIFI", "📡 HTTP 触发扫描: " + nearby.size() + " 个热点");
                        } catch (Exception e) {
                            FileLogger.log(this, "WIFI", "❌ HTTP 触发扫描失败: " + e.getMessage());
                        }
                    }).start();
                    return true;
                case "restart_udp":
                    if (rescueSocketRef != null) {
                        try { rescueSocketRef.close(); } catch (Exception ignored) {}
                    }
                    return true;
                case "test_low_battery":
                    sendReport(true, true);
                    sendLowBatterySms(15);
                    return true;
                // ★ 2026-11-07 [P2]
                case "elder_mode":
                    if ("1".equals(payload) || "true".equalsIgnoreCase(payload)) setElderMode(true);
                    else setElderMode(false);
                    return true;
                // ★ 2026-11-13 插件挂载：远程陪伴（HTTP 通道 → 复用 CompanionModule 统一处理）
                case "companion_on":
                    CompanionModule.handleUdp(this,
                            "FIND_ME_PLUGIN:companion:" + RESCUE_SECRET + ":on");
                    return true;
                case "companion_off":
                    CompanionModule.handleUdp(this,
                            "FIND_ME_PLUGIN:companion:" + RESCUE_SECRET + ":off");
                    return true;
                case "push_photo":
                    if (payload != null && !payload.isEmpty()) {
                        CompanionModule.handleUdp(this,
                                "FIND_ME_PUSH_PHOTO:" + RESCUE_SECRET + ":" + payload);
                    }
                    return true;
                case "push_video":
                    if (payload != null && !payload.isEmpty()) {
                        CompanionModule.handleUdp(this,
                                "FIND_ME_PUSH_VIDEO:" + RESCUE_SECRET + ":" + payload);
                    }
                    return true;
                // ★  2026-11-18 最小修复：增加无伤挂载音频流与交互式网页直推通道
                case "push_audio":
                    if (payload != null && !payload.isEmpty()) {
                        CompanionModule.handleUdp(this,
                                "FIND_ME_PUSH_AUDIO:" + RESCUE_SECRET + ":" + payload);
                    }
                    return true;
                    
                case "push_web":
                    if (payload != null && !payload.isEmpty()) {
                        CompanionModule.handleUdp(this,
                                "FIND_ME_PUSH_WEB:" + RESCUE_SECRET + ":" + payload);
                    }
                    return true;
                default:
                    FileLogger.log(this, "HTTP_CMD", "⚠️ 未知 HTTP 指令: " + type);
                    return false;
            }
        } catch (Exception e) {
            FileLogger.log(this, "HTTP_CMD", "❌ 执行 " + type + " 异常: " + e.getMessage());
            return false;
        }
    }

    private void scheduleFastPoll(int seconds) {
        if (seconds <= 0) return;
        if (seconds > 60) seconds = 60;
        synchronized (fastPollLock) {
            if (isFastPolling) return;
            isFastPolling = true;
            fastPollConsecutiveCount = 0;
        }
        FileLogger.log(this, "FAST_POLL", "▶ 启动快速轮询（间隔 " + seconds + " 秒）");
        handler.postDelayed(fastPollRunnable, seconds * 1000L);
    }

    private void cancelFastPoll() {
        boolean wasRunning;
        synchronized (fastPollLock) {
            wasRunning = isFastPolling;
            if (!isFastPolling) return;
            isFastPolling = false;
            fastPollConsecutiveCount = 0;
        }
        handler.removeCallbacks(fastPollRunnable);
        if (wasRunning) FileLogger.log(this, "FAST_POLL", "⏹ 队列已空，停止快速轮询");
    }

    private final Runnable fastPollRunnable = this::doFastPoll;

    private void doFastPoll() {
        int currentCount;
        long nextInterval;
        synchronized (fastPollLock) {
            if (!isFastPolling) return;
            fastPollConsecutiveCount++;
            currentCount = fastPollConsecutiveCount;

            if (currentCount > FAST_POLL_MAX_CONSECUTIVE) {
                isFastPolling = false;
                fastPollConsecutiveCount = 0;
                FileLogger.log(this, "FAST_POLL",
                        "⚠️ 连续快速轮询达上限 " + FAST_POLL_MAX_CONSECUTIVE + " 次，强制停止");
                return;
            }

            int[] battery = DeviceInfoUtils.getBatteryInfo(this);
            int level = battery[0];
            boolean lowBattery = (level >= 0 && level < FAST_POLL_BATTERY_THRESHOLD);
            nextInterval = lowBattery ? 5 * 60 * 1000L : 30 * 1000L;

            FileLogger.log(this, "FAST_POLL",
                    "⚡ 第 " + currentCount + "/" + FAST_POLL_MAX_CONSECUTIVE + " 次"
                            + (lowBattery ? "（🔋 低电量 " + level + "%，降级 5 分钟）" : ""));
        }

        sendReport(false, true);

        synchronized (fastPollLock) {
            if (isFastPolling && fastPollConsecutiveCount < FAST_POLL_MAX_CONSECUTIVE) {
                handler.postDelayed(fastPollRunnable, nextInterval);
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        FileLogger.log(this, "SERVICE", "⚠️ onLowMemory: 系统内存压力");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // v2.0.1 补丁：60 秒时间窗，超过 1 分钟才允许记录一次文件，防高频 IO
            long now = System.currentTimeMillis();
            if (now - lastTrimMemoryLogTime < 60 * 1000L) return;
            lastTrimMemoryLogTime = now;

            try {
                ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
                ActivityManager.getMyMemoryState(info);
                FileLogger.log(this, "SERVICE", "⚠️ onTrimMemory level=" + level + ", importance=" + importanceStr(info.importance));
            } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "⚠️ onTrimMemory level=" + level);
            }
        }
    }

    private String importanceStr(int imp) {
        switch (imp) {
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND: return "FOREGROUND";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE: return "FOREGROUND_SERVICE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE: return "VISIBLE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE: return "PERCEPTIBLE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE: return "SERVICE";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED: return "CACHED";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY: return "EMPTY";
            default: return String.valueOf(imp);
        }
    }

    /**
     * SQLite must outlive every in-flight LAN task. Executor shutdown is asynchronous,
     * so close the shared helper only after all producers/consumers have drained.
     */
    private void closeLanChatStoreAfterExecutors(final LanChatStore storeToClose) {
        if (storeToClose == null) return;
        Thread closer = new Thread(() -> {
            awaitLanExecutorTermination(lanChatScheduler);
            awaitLanExecutorTermination(lanChatImmediateExecutor);
            awaitLanExecutorTermination(lanChatSendExecutor);
            awaitLanExecutorTermination(lanChatControlExecutor);
            awaitLanExecutorTermination(lanChatAckExecutor);
            awaitLanExecutorTermination(lanChatPeerExecutor);
            awaitLanExecutorTermination(lanChatProbeExecutor);
            awaitLanExecutorTermination(lanChatIoExecutor);
            awaitLanExecutorTermination(maintenanceExecutor);
            try {
                storeToClose.closeStore();
                FileLogger.log(this, "SERVICE", "LAN SQLite store closed after communication queues drained");
            } catch (Exception e) {
                FileLogger.log(this, "SERVICE", "⚠️ 延后关闭 LAN SQLite 失败: "
                        + e.getClass().getSimpleName());
            }
        }, "LanChat-Store-Close");
        closer.setDaemon(true);
        closer.start();
    }

    private void awaitLanExecutorTermination(java.util.concurrent.ExecutorService executor) {
        if (executor == null) return;
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logLifecycle("SERVICE_DESTROY", "isForegroundStarted=" + isForegroundStarted);
        FileLogger.log(this, "SERVICE", "🛑 服务销毁");
        serviceDestroyed = true;

        // ★ 强力补丁 6：服务销毁时，拔掉所有定时和自愈消息，防幽灵唤醒
        handler.removeMessages(MSG_RETRY_RESCUE_LISTENER);
        handler.removeCallbacksAndMessages(null);

        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }

        cancelFastPoll();

        if (reportExecutor != null) reportExecutor.shutdown();
        if (lanChatImmediateExecutor != null) lanChatImmediateExecutor.shutdown();
        if (lanChatSendExecutor != null) lanChatSendExecutor.shutdown();
        if (lanChatControlExecutor != null) lanChatControlExecutor.shutdown();
        if (lanChatAckExecutor != null) lanChatAckExecutor.shutdown();
        if (lanChatPeerExecutor != null) lanChatPeerExecutor.shutdown();
        if (lanChatProbeExecutor != null) lanChatProbeExecutor.shutdown();
        if (lanChatIoExecutor != null) lanChatIoExecutor.shutdown();
        if (lanChatScheduler != null) lanChatScheduler.shutdownNow();
        if (maintenanceExecutor != null) maintenanceExecutor.shutdownNow();
        LanChatStore storeToClose = lanChatStore;
        lanChatStore = null;
        closeLanChatStoreAfterExecutors(storeToClose);
        if (rescueSocketRef != null) {
            try { rescueSocketRef.close(); } catch (Exception e) { FileLogger.log(this, "SERVICE", "⚠️ close rescueSocket 失败: " + e.getMessage()); }
            rescueSocketRef = null;
        }
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception e) { FileLogger.log(this, "SERVICE", "⚠️ removeUpdates 失败: " + e.getMessage()); }
        }
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) for (android.view.View v : new ArrayList<>(activeBubbles)) {
                try { wm.removeView(v); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        activeBubbles.clear();

        if (batteryMonitor != null) { try { unregisterReceiver(batteryMonitor); } catch (Exception ignored) {} }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorManager != null && motionListener != null) sensorManager.cancelTriggerSensor(motionListener, motionSensor);
        handler.removeCallbacks(networkRefreshRunnable);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.unregisterNetworkCallback(networkCallback);
            if (cm != null && wifiLanNetworkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.unregisterNetworkCallback(wifiLanNetworkCallback);
            }
        } catch (Exception ignored) {}
    }

    // ★ 补丁3：无痕极速复活（划掉后台后 2 秒内由 AlarmManager + JobScheduler 双路拉活）
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        logLifecycle("TASK_REMOVED", "rootAction="
                + (rootIntent != null ? rootIntent.getAction() : "<null>"));
        long now = System.currentTimeMillis();
        if (now - lastOnTaskRemovedTime < ON_TASK_REMOVED_THROTTLE_MS) {
            return; // 10分钟内防抖
        }
        lastOnTaskRemovedTime = now;
        FileLogger.log(this, "SERVICE", "⚠️ 任务栈已被划掉，触发无痕防杀自救...");

        Intent restartServiceIntent = new Intent(getApplicationContext(), TrackingService.class);
        restartServiceIntent.setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_ONE_SHOT;
        PendingIntent restartPi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            restartPi = PendingIntent.getForegroundService(getApplicationContext(), 1, restartServiceIntent, flags);
        } else {
            restartPi = PendingIntent.getService(getApplicationContext(), 1, restartServiceIntent, flags);
        }

        // 策略A：无痕精准闹钟
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            long triggerAt = System.currentTimeMillis() + taskRemovedRescueDelayMs;
            try {
                am.cancel(restartPi);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, restartPi);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, restartPi);
                }
            } catch (Exception e) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, restartPi);
            }
        }

        // 策略B：JobScheduler 极速兜底（ID: 10087）
        try {
            JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) {
                JobInfo.Builder builder = new JobInfo.Builder(10087, new ComponentName(this, KeepAliveJobService.class));
                builder.setMinimumLatency(taskRemovedRescueDelayMs);
                js.schedule(builder.build());
            }
        } catch (Exception ignored) {}
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
