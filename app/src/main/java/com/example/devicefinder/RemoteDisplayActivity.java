package com.example.devicefinder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * RemoteDisplayActivity — 远程陪伴五模全自动多媒体容器（精准对齐版）
 * 核心设计：100% 保留最初版本测试通过的图片mark/reset缓冲区与降采样防爆装甲，追加纯音频、全网页和状态自动回传。
 *
 * ============================================================
 * 2026-11-18 修复批次 fix-20261118-A（问题：推送图片手机端不显示）
 *  [R1] 根因修复：handleIntent 分流表补齐 photo 通路。
 *       旧版只认 "video"/"push_audio"/"push_web"，type="push_photo" 落入
 *       末尾“盲发纠偏线”，而纠偏线里 `else if (url.startsWith("http")) initWebView(url)`
 *       排在 initImage 之前 —— CompanionModule 又保证 url 必是 http(s)，
 *       导致 initImage() 永不可达，图片一律被 WebView 当网页渲染（黑屏/空白/直接 finish）。
 *  [R2] initImage 诊断增强：打印 HTTP 状态码 / Content-Type / 体长度，
 *       并显式识别“返回的是 HTML（404 页或目录页）”这类服务端问题。
 *  [R3] initImage 健壮性：跟随重定向、UA、12MB 体积上限、越界尺寸 OOM 保护、
 *       mark/reset 失败后重新包 BufferedInputStream（旧版裸流二次解码不稳）。
 *  [R4] 锁屏可见：FLAG_SHOW_WHEN_LOCKED / TURN_SCREEN_ON，解决息屏推送“其实已显示但看不到”。
 *  [R5] 注册 ACTION_COMPANION_SHUTDOWN 广播：旧版 cleanup() 发的强下线广播没人接收，
 *       关闭陪伴模式后全屏页会一直挂着。
 *  [R6] 2026-11-18 最终版追加：断路器超时按通路分级。旧版 15 秒无条件 finish()，
 *       4G + CDN 大图/长图（本文件 initImage 自身给了 6s 连接 + 8s 读 + 12MB 下载量）
 *       很容易“屏幕闪一下就回桌面”，观感等于不显示。图片/网页放宽到 30 秒，
 *       视频/音频仍保持 15 秒（拉流失败早退更早，且这两条通路历来正常）。
 * ------------------------------------------------------------
 * 2026-11-18 合并批次 fix-20261118-B（588 行版只取纯收益，R1~R6 一项不动）
 *  [M1] 诊断日志零删减：HTTP 三要素 / 外廓+sample+字节 / 前 16 字节魔数全部保留，
 *       成功与失败路径都可观测（对齐 CompanionModule 精简版 M2「静默黑洞补日志」原则）。
 *  [M2] BufferedInputStream 预分配 5MB → 64KB：readAll 本就把全字节收进
 *       ByteArrayOutputStream，5MB 缓冲是每次推图白烧的堆。
 *  [M3] 新增 EXIF 方向矫正（仅 JPEG 魔数命中 + API 24+ + ROTATE_MAX_PIXELS 闸门），
 *       治手机竖拍横存的 JPEG 推上去躺倒；PNG/WebP 不再白烧一次 ExifInterface 异常栈。
 *  [M4] 下载/解码/挂载全程 isFinishing 守卫：用户中途退出不再白解大图；
 *       且失败原因先无条件落日志、再判存活决定是否 toast+finish（守卫不吞日志）。
 *  未采纳（有意保持 538 行版原样）：static 内部类 + WeakReference（timeoutRunnable 是实例
 *       lambda，仍强持 Activity，该改造达不到它宣称的效果）、UA 2.3→2.5、异常文案缩短、
 *       RECEIVER_EXPORTED→NOT_EXPORTED（安全收紧，待你确认后单独改）。
 * ============================================================
 */
public class RemoteDisplayActivity extends Activity {

    private boolean isLocked = true; // 3 秒防误触锁
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView videoView;
    private ImageView imageView;
    private WebView webView;
    private MediaPlayer backgroundAudioPlayer;

    private FrameLayout rootLayout;
    private final int LOADING_TIMEOUT_MS = 15000;          // 视频/音频：15s 足够
    private final int SLOW_DISPLAY_TIMEOUT_MS = 30000;     // [R6] 图片/网页：容忍 4G + CDN 大图
    private String currentUrl = "";

    // [R2] 图片下载硬上限：超过直接放弃，避免 OOM 与长时间白屏
    private static final int IMAGE_MAX_BYTES = 12 * 1024 * 1024;

    // [M3] EXIF 旋转闸门：Bitmap.createBitmap(src, ..., matrix, true) 会瞬时产生第二张同尺寸位图，
    //      旧图 recycle() 发生在新图分配之后，救不了峰值。RGB_565 下 4M 像素 ≈ 8MB，旋转峰值 ≈ 16MB。
    //      而降采样那段用的是 && （宽高都超 1920 才加档），1080x8000 这类长截图 inSampleSize 会停在 1，
    //      所以旋转前必须再设一道像素总量闸门：越界只跳过矫正、照常出图，不把"躺倒"升级成"OOM 加载失败"。
    private static final long ROTATE_MAX_PIXELS = 4_000_000L;

    // [R5] 陪伴关闭 / 强下线广播接收器
    private final BroadcastReceiver shutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            FileLogger.log(RemoteDisplayActivity.this, "COMPANION", "🕶️ 收到强下线广播，主动关闭全屏容器");
            try { finish(); } catch (Exception ignored) {}
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 保持亮屏 & 沉浸式全屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // [R4] 锁屏 / 息屏状态下也要能把照片顶出来
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            //noinspection WrongConstant
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // 2. 纯动态内存创建视图树，零资源文件依赖
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(rootLayout);

        // 3秒防误触锁倒计时
        handler.postDelayed(() -> isLocked = false, 3000);

        // [R5] 接收强下线广播
        try {
            IntentFilter filter = new IntentFilter(CompanionModule.ACTION_COMPANION_SHUTDOWN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(shutdownReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(shutdownReceiver, filter);
            }
        } catch (Exception ignored) {}

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(android.content.Intent intent) {
        if (intent == null) return;
        String type = intent.getStringExtra("type");
        String url = intent.getStringExtra("url");

        if (url == null || url.trim().isEmpty()) {
            FileLogger.log(this, "COMPANION", "⚠️ intent 未携带 url，容器自毁");
            finish();
            return;
        }
        url = url.trim();
        currentUrl = url;

        // 重置状态
        handler.removeCallbacks(timeoutRunnable);
        handler.postDelayed(timeoutRunnable, LOADING_TIMEOUT_MS);
        resetAllViews();

        String t = (type == null) ? "" : type.trim().toLowerCase();
        String lower = url.toLowerCase();

        // ============================================================
        // [R1] 第一优先：服从显式 type。photo/video/audio/web 四种值全部有归属，
        //      绝不再让图片掉进“按 http 前缀判网页”的坑。
        //      同时向下兼容旧版 APK 可能收到的 "push_photo"/"push_audio" 长名。
        // ============================================================
        if ("video".equals(t))                                   { routeLog(t, "视频"); initVideo(url); return; }
        if ("audio".equals(t) || "push_audio".equals(t))         { routeLog(t, "音频"); initAudio(url); return; }
        if ("web".equals(t) || "push_web".equals(t))             { routeLog(t, "网页"); initWebView(url); return; }
        if ("photo".equals(t) || "image".equals(t) || "push_photo".equals(t)) {
            routeLog(t, "图片"); initImage(url); return;
        }

        // ============================================================
        // 第二优先：type 缺失 / 未知（例如旧版服务端信令）→ 按 URL 特征兜底判型
        // 判型顺序固定为 图 > 影 > 音 > 网页，图片永远排在网页劫持之前。
        // ============================================================
        if (looksImage(lower))      { routeLog(t, "图片(自动判型)"); initImage(url); }
        else if (looksVideo(lower)) { routeLog(t, "视频(自动判型)"); initVideo(url); }
        else if (looksAudio(lower)) { routeLog(t, "音频(自动判型)"); initAudio(url); }
        else if (looksWebPage(lower)) { routeLog(t, "网页(自动判型)"); initWebView(url); }
        else                        { routeLog(t, "图片(默认兜底)"); initImage(url); }
    }

    private void routeLog(String type, String target) {
        FileLogger.log(this, "COMPANION", "🧭 分流决策 type=[" + type + "] -> " + target + " | " + currentUrl);
    }

    private static boolean looksImage(String u) {
        return endsWithAny(u, ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
                || u.contains("/uploads/media/");
    }
    private static boolean looksVideo(String u) {
        return endsWithAny(u, ".mp4", ".webm", ".mkv", ".mov", ".avi", ".m3u8")
                || u.contains("live") || u.contains("stream");
    }
    private static boolean looksAudio(String u) {
        return endsWithAny(u, ".mp3", ".aac", ".m4a", ".wav", ".ogg", ".wma")
                || u.contains("audio") || u.contains("radio") || u.contains("fm");
    }
    private static boolean looksWebPage(String u) {
        return endsWithAny(u, ".html", ".htm", ".h5") || u.contains("/index");
    }
    private static boolean endsWithAny(String s, String... tails) {
        for (String t : tails) if (s.endsWith(t)) return true;
        return false;
    }

    /**
     * 1. 🌐 全功能网页加载引擎
     */
    private void initWebView(final String url) {
        FileLogger.log(this, "WEB_CORE", "🌐 全屏纯净 H5 容器挂载启动: " + url);
        // [R6] 网页需要 JS/DomStorage/宽视口初始化，比 ImageView 慢，同样放宽到 30s
        handler.removeCallbacks(timeoutRunnable);
        handler.postDelayed(timeoutRunnable, SLOW_DISPLAY_TIMEOUT_MS);
        try {
            webView = new WebView(this);
            rootLayout.addView(webView, new FrameLayout.LayoutParams(-1, -1));

            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);

            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String loadedUrl) {
                    handler.removeCallbacks(timeoutRunnable);
                    getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                        .putString("current_push_web_url", loadedUrl)
                        .putString("current_push_photo_url", "")
                        .putString("current_push_video_url", "")
                        .putString("current_push_audio_url", "").commit();
                }
                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    handler.removeCallbacks(timeoutRunnable);
                    FileLogger.log(RemoteDisplayActivity.this, "WEB_CORE",
                            "❌ 网页加载失败 code=" + errorCode + " desc=" + description + " url=" + failingUrl);
                    finish();
                }
            });

            webView.loadUrl(url);
        } catch (Exception e) {
            handler.removeCallbacks(timeoutRunnable);
            FileLogger.log(this, "WEB_CORE", "❌ WebView 初始化异常（该系统可能未安装 WebView 组件）: " + e.getMessage());
            finish();
        }
    }
    /**
     * 2. 🎬 视频/直播流渲染管道（M3U8 直源原地自愈断连）
     */
    private void initVideo(final String url) {
        try {
            videoView = new VideoView(this);
            rootLayout.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
            videoView.setVideoURI(Uri.parse(url));

            videoView.setOnPreparedListener(mp -> {
                handler.removeCallbacks(timeoutRunnable);
                if (url.toLowerCase().indexOf(".m3u8") == -1) {
                    mp.setLooping(true); // 标准点播文件开启无限循环
                }
                videoView.start();

                // 🌟 状态同步：持久化记录当前全屏播放的视频或直播源，以便主引擎打包回传
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                    .putString("current_push_video_url", url)
                    .putString("current_push_photo_url", "")
                    .putString("current_push_audio_url", "")
                    .putString("current_push_web_url", "").commit();
                FileLogger.log(this, "COMPANION", "▶ 视频/直播流加载完毕，起播成功");
            });

            videoView.setOnErrorListener((mp, what, extra) -> {
                handler.removeCallbacks(timeoutRunnable);
                FileLogger.log(this, "COMPANION", "❌ 视频异常 what=" + what + ", extra=" + extra);

                // 直播网络层闪断自愈（错误代码 -1004 代表连接丢失）。启动原地2秒无痕重连，不跳出界面
                if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED || extra == -1004) {
                    FileLogger.log(this, "COMPANION", "🔄 网络流闪断，正在尝试原地重新拉流...");
                    handler.postDelayed(() -> {
                        try { if (videoView != null) videoView.setVideoURI(Uri.parse(url)); } catch (Exception ignored) {}
                    }, 2000);
                    return true;
                }
                Toast.makeText(this, "多媒体流加载失败", Toast.LENGTH_SHORT).show();
                finish();
                return true;
            });
        } catch (Exception e) {
            handler.removeCallbacks(timeoutRunnable);
            finish();
        }
    }

    /**
     * 3. 🎵 纯音频流渲染管道（网络电台/戏曲，支持息屏收听，极度省电）
     */
    private void initAudio(final String url) {
        FileLogger.log(this, "AUDIO_CORE", "📻 后台音频流硬解起动: " + url);
        try {
            backgroundAudioPlayer = new MediaPlayer();
            backgroundAudioPlayer.setDataSource(this, Uri.parse(url));
            backgroundAudioPlayer.setOnPreparedListener(mp -> {
                handler.removeCallbacks(timeoutRunnable);
                mp.setLooping(true);
                mp.start();

                // 🌟 状态同步：持久化记录当前后台正在收听的网络电台戏曲，以便主引擎打包回传
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                    .putString("current_push_audio_url", url)
                    .putString("current_push_photo_url", "")
                    .putString("current_push_video_url", "")
                    .putString("current_push_web_url", "").commit();
                Toast.makeText(RemoteDisplayActivity.this, "📻 局域网/云端广播已起播", Toast.LENGTH_SHORT).show();
            });
            backgroundAudioPlayer.setOnErrorListener((mp, what, extra) -> {
                handler.removeCallbacks(timeoutRunnable);
                FileLogger.log(this, "AUDIO_CORE", "❌ 音频异常 what=" + what + " extra=" + extra);
                finish();
                return true;
            });
            backgroundAudioPlayer.prepareAsync();
        } catch (Exception e) {
            handler.removeCallbacks(timeoutRunnable);
            finish();
        }
    }

    /**
     * 4. 🖼️ 图片解析管道（mark/reset 双全缓冲 + OOM 降采样 + EXIF 自动矫正）
     *    [R2][R3][R6] 保留：诊断日志、12MB 上限、跟随重定向、30s 断路器档
     *    [M2][M3][M4] 新增：64KB 缓冲、EXIF 方向矫正（带像素闸门）、isFinishing 守卫
     */
    private void initImage(final String url) {
        // [R6] 图片通路单独延长断路器：先撤 15s 默认档，重挂 30s 档
        handler.removeCallbacks(timeoutRunnable);
        handler.postDelayed(timeoutRunnable, SLOW_DISPLAY_TIMEOUT_MS);
        FileLogger.log(this, "COMPANION", "🖼️ 进入 initImage（原生 ImageView 通路，超时档 "
                + (SLOW_DISPLAY_TIMEOUT_MS / 1000) + "s）: " + url);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(android.graphics.Color.BLACK);
        rootLayout.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        // 异步下载图片，加入原汁原味的 OOM 降采样与单连接预读保护
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream raw = null;
            BufferedInputStream is = null;
            try {
                URL imgUrl = new URL(url);
                conn = (HttpURLConnection) imgUrl.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "DeviceFinder-Companion/2.3");
                conn.connect();

                int code = conn.getResponseCode();
                String contentType = conn.getContentType();
                long contentLen = conn.getContentLengthLong();
                // [M1] 这三要素是区分「服务端返 404 HTML 页」与「图片真下不下来」的唯一依据，不许删
                FileLogger.log(RemoteDisplayActivity.this, "COMPANION",
                        "🖼️ 图片响应 code=" + code + " type=" + contentType + " len=" + contentLen);

                if (code < 200 || code >= 300) {
                    throw new Exception("HTTP " + code + "（地址不可达或文件不存在，请核对手机能否访问该 URL）");
                }
                // [R2] 服务端返回 HTML：基本可断定「404 页 / 目录页 / 被路由到了 Web 控制台」
                if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                    throw new Exception("返回的是 HTML 而非图片（Content-Type=" + contentType
                            + "），多为文件不存在或服务端 MIME 错乱");
                }
                if (contentLen > IMAGE_MAX_BYTES) {
                    throw new Exception("图片过大 " + (contentLen / 1024 / 1024) + "MB，超出 "
                            + (IMAGE_MAX_BYTES / 1024 / 1024) + "MB 上限");
                }

                raw = conn.getInputStream();
                is = new BufferedInputStream(raw, 64 * 1024); // [M2] 64KB 足够，5MB 预分配是纯浪费堆

                // [R3] 先把字节收进内存（有上限），彻底规避 mark/reset 越界与半流解码返回 null
                byte[] bytes = readAll(is, IMAGE_MAX_BYTES);
                if (bytes == null || bytes.length < 64) {
                    throw new Exception("图片字节流为空或过短(" + (bytes == null ? 0 : bytes.length)
                            + "B)，多为上传被截断 / MIME 与真实格式不符");
                }

                // [M4] 用户已退出则安静收工，不再白烧一次大图解码（原因照落日志，不静默）
                if (isFinishing()) {
                    FileLogger.log(RemoteDisplayActivity.this, "COMPANION",
                            "🚪 下载完成时容器已退出，放弃解码与挂载: " + url);
                    return;
                }

                // OOM 保护：先读外廓尺寸
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);

                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    String head = hexHead(bytes);
                    throw new Exception("无法识别图片尺寸，该格式安卓 BitmapFactory 不支持(前16字节=" + head
                            + ")，iPhone 的 HEIC/RAW 需转 JPG 后再推");
                }

                // 计算降采样率 (目标最大宽高 1920)
                int inSampleSize = 1;
                if (opts.outWidth > 1920 || opts.outHeight > 1920) {
                    final int halfHeight = opts.outHeight / 2;
                    final int halfWidth = opts.outWidth / 2;
                    while ((halfHeight / inSampleSize) >= 1920 && (halfWidth / inSampleSize) >= 1920) {
                        inSampleSize *= 2;
                    }
                }
                // [M1] 降采样是否真的生效、原图多大，是大图 OOM / 卡 30s 断路器的唯一量化依据
                FileLogger.log(RemoteDisplayActivity.this, "COMPANION",
                        "🖼️ 外廓 " + opts.outWidth + "x" + opts.outHeight + " sample=" + inSampleSize
                                + " 字节=" + bytes.length);

                // [M3] EXIF 方向矫正：先判 JPEG 魔数 FFD8，PNG/WebP 走 ExifInterface 必抛异常、白烧异常栈
                int degree = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                    try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes)) {
                        android.media.ExifInterface exif = new android.media.ExifInterface(bais);
                        int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                                android.media.ExifInterface.ORIENTATION_NORMAL);
                        if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) degree = 90;
                        else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) degree = 180;
                        else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) degree = 270;
                    } catch (Exception ignored) { }
                }

                opts.inJustDecodeBounds = false;
                opts.inPreferredConfig = Bitmap.Config.RGB_565; // [R3] 陪伴相册无需 ARGB_8888，内存直接减半
                opts.inSampleSize = inSampleSize;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);

                if (bitmap == null) {
                    // [M1] 前 16 字节魔数必须留：这是识别 HEIC/WEBP/HEIF/半截 JPEG 的唯一线索
                    throw new Exception("BitmapFactory 解码返回 null(前16字节=" + hexHead(bytes) + ")");
                }

                // [M3] 旋转前先过像素闸门：越界只跳过矫正、照常出图，不把「躺倒」升级成「加载失败」
                if (degree != 0) {
                    final long pixels = (long) bitmap.getWidth() * bitmap.getHeight();
                    if (pixels > ROTATE_MAX_PIXELS) {
                        FileLogger.log(RemoteDisplayActivity.this, "COMPANION",
                                "⚠️ 位图 " + bitmap.getWidth() + "x" + bitmap.getHeight() + "("
                                        + (pixels / 1000000) + "M像素) 超过旋转闸门 "
                                        + (ROTATE_MAX_PIXELS / 1000000) + "M，跳过 EXIF " + degree
                                        + "° 矫正以防 OOM；长图请推送前先转码");
                    } else {
                        android.graphics.Matrix matrix = new android.graphics.Matrix();
                        matrix.postRotate(degree);
                        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        if (rotated != bitmap) {
                            bitmap.recycle(); // 新图已分配，立刻释放旧的错位位图
                            bitmap = rotated;
                        }
                        FileLogger.log(RemoteDisplayActivity.this, "COMPANION",
                                "🔄 已按 EXIF 矫正 " + degree + "°，最终 " + bitmap.getWidth()
                                        + "x" + bitmap.getHeight());
                    }
                }

                final Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> {
                    // [M4] 撤断路器与挂载放进同一个 UI 任务，杜绝「撤了表却没挂图」的黑屏僵住窗口
                    handler.removeCallbacks(timeoutRunnable);
                    if (isFinishing() || imageView == null) {
                        FileLogger.log(RemoteDisplayActivity.this, "COMPANION",
                                "🚪 回到主线程时容器已退出或视图已回收，放弃挂载: " + url);
                        return;
                    }
                    imageView.setImageBitmap(finalBitmap);

                    // 🌟 状态同步：落盘记录当前照片墙挂载的图片网址，以便主引擎打包同步
                    getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                        .putString("current_push_photo_url", url)
                        .putString("current_push_video_url", "")
                        .putString("current_push_audio_url", "")
                        .putString("current_push_web_url", "").commit();
                    FileLogger.log(RemoteDisplayActivity.this, "COMPANION", "✅ 照片已全屏挂载: " + url);
                });
            } catch (Exception e) {
                handler.removeCallbacks(timeoutRunnable);
                // [M4] 失败原因先无条件落日志，再判存活决定是否 toast + finish（守卫不吞日志）
                FileLogger.log(this, "COMPANION", "❌ 图片挂载彻底失败: " + e.getMessage());
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, "图片加载失败: " + safeMsg(e), Toast.LENGTH_LONG).show();
                    finish();
                });
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) {}
                try { if (raw != null) raw.close(); } catch (Exception ignored) {}
                try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(maxBytes, 256 * 1024));
        byte[] buf = new byte[16 * 1024];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new Exception("下载体积超限 " + (total / 1024 / 1024) + "MB");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String hexHead(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int lim = Math.min(b.length, 16);
        for (int i = 0; i < lim; i++) sb.append(String.format("%02X", b[i] & 0xFF));
        return sb.toString();
    }

    private static String safeMsg(Exception e) {
        String m = e == null ? "unknown" : e.getMessage();
        if (m == null) m = (e == null ? "unknown" : e.getClass().getSimpleName());
        if (m.length() > 90) m = m.substring(0, 90);
        return m;
    }

    private void resetAllViews() {
        try { if (videoView != null) { videoView.stopPlayback(); rootLayout.removeView(videoView); videoView = null; } } catch (Exception ignored) {}
        try { if (imageView != null) { imageView.setImageBitmap(null); rootLayout.removeView(imageView); imageView = null; } } catch (Exception ignored) {}
        try { if (webView != null) { webView.stopLoading(); webView.clearHistory(); rootLayout.removeView(webView); webView = null; } } catch (Exception ignored) {}
        try { if (backgroundAudioPlayer != null) { if (backgroundAudioPlayer.isPlaying()) backgroundAudioPlayer.stop(); backgroundAudioPlayer.release(); backgroundAudioPlayer = null; } } catch (Exception ignored) {}
    }

    private void hideSystemUI() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Exception ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI(); // 失去焦点恢复再次强切沉浸
    }

    // 按键拦截与多模智能退出逻辑（100% 沿用并优化您的防抖硬锁）
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isLocked) {
            return true; // 3秒内拦截任何硬键，防御误触
        }

        // 网页核心特征支持：优先响应网页后退，保证正常浏览不中断
        if (webView != null && webView.canGoBack() && keyCode == KeyEvent.KEYCODE_BACK) {
            webView.goBack();
            return true;
        }

        // 其它媒体流、或网页退无可退时，直接按任意键离场自毁
        finish();
        return true;
    }

    private final Runnable timeoutRunnable = () -> {
        FileLogger.log(RemoteDisplayActivity.this, "COMPANION",
                "⚠️ 媒体加载超时自愈触发 url=" + currentUrl);
        finish();
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(shutdownReceiver); } catch (Exception ignored) {}
        resetAllViews();
        handler.removeCallbacksAndMessages(null);

        // 🌟 退出状态清除：容器销毁瞬间擦除在播痕迹，主同步引擎下次上报自动将云端同步洗净
        getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
            .putString("current_push_photo_url", "")
            .putString("current_push_video_url", "")
            .putString("current_push_audio_url", "")
            .putString("current_push_web_url", "").commit();
    }
}
