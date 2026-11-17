package com.example.devicefinder;

import android.app.Activity;
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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * RemoteDisplayActivity — 远程陪伴五模全自动多媒体容器（带有全状态回传持久化版本）
 * 支持：静态图片、循环视频、网络直播流、后台音频流、全功能交互式网页 H5 挂载。
 * 具备：15秒自愈断路器、2K级防 OOM 降采样、网络缓冲区硬拉高、智能按键锁拦截、真实 URL 状态持久化。
 * 2026-11-18
 */
public class RemoteDisplayActivity extends Activity {

    private boolean isLocked = true; // 3 秒防误触锁
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView videoView;
    private ImageView imageView;
    private WebView webView;
    private MediaPlayer backgroundAudioPlayer; // 🎵 用于纯音频流后台硬解（省电模式）
    
    private FrameLayout rootLayout;
    private final int LOADING_TIMEOUT_MS = 15000; // 15秒加载超时断路门限

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. 强制保持亮屏、突破锁屏特权防线、允许在系统最顶层绘制
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
                
        // 隐藏状态栏和导航栏，强制全面屏沉浸
        hideSystemUI();

        // 2. 纯动态内存创建视图树，零资源文件依赖，绝不报布局加载 NPE
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(rootLayout);

        // 3秒防误触锁倒计时
        handler.postDelayed(() -> isLocked = false, 3000);

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
            finish();
            return;
        }

        // 重置状态：强力拔除上一个正在运行的媒体内核
        handler.removeCallbacks(timeoutRunnable);
        handler.postDelayed(timeoutRunnable, LOADING_TIMEOUT_MS);
        resetAllViews();

        // 3. 智能五通路网关切流分流
        if ("video".equals(type)) {
            initVideo(url);
        } else if ("push_audio".equals(type)) {
            initAudio(url);
        } else if ("push_web".equals(type)) {
            initWebView(url);
        } else {
            // 盲发纠偏线：防止前端后缀分类意外错乱
            String lowerUrl = url.toLowerCase();
            if (lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".m3u8") || lowerUrl.contains("live") || lowerUrl.contains("stream")) {
                initVideo(url);
            } else if (lowerUrl.endsWith(".mp3") || lowerUrl.endsWith(".aac") || lowerUrl.contains("audio") || lowerUrl.contains("fm")) {
                initAudio(url);
            } else if (lowerUrl.startsWith("http")) {
                initWebView(url);
            } else {
                initImage(url);
            }
        }
    }

    /**
     * 1. 🌐 全功能网页加载引擎（支持完整点击交互、滑动、双指缩放）
     */
    private void initWebView(final String url) {
        FileLogger.log(this, "WEB_CORE", "🌐 全屏纯净 H5 容器挂载启动: " + url);
        try {
            webView = new WebView(this);
            rootLayout.addView(webView, new FrameLayout.LayoutParams(-1, -1));
            
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);    // 激活时钟、天气网页的自动异步刷新
            settings.setDomStorageEnabled(true);     // 开启 DOM 缓存支撑大型复杂 H5
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            
            // 🌟 网页缩放特权支持：方便长辈放大看新闻
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false); // 隐藏原生丑陋的缩放加减气泡按钮

            // 强行关闭混合内容安全沙箱，允许在非加密环境下加载外链 HTTPS 图像资产
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String loadedUrl) {
                    handler.removeCallbacks(timeoutRunnable); // 渲染成功，撤销超时门限
                    
                    // 🌟 状态无损同步：落盘记录当前全屏常驻的网页外链，以便主追踪引擎打包回传
                    getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                        .putString("current_push_web_url", loadedUrl)
                        .putString("current_push_photo_url", "")
                        .putString("current_push_video_url", "")
                        .putString("current_push_audio_url", "").commit();
                }
                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    handler.removeCallbacks(timeoutRunnable);
                    FileLogger.log(RemoteDisplayActivity.this, "WEB_CORE", "❌ 网页加载失败: " + description);
                    finish();
                }
            });
            
            webView.loadUrl(url);
        } catch (Exception e) {
            handler.removeCallbacks(timeoutRunnable);
            finish();
        }
    }
    /**
     * 2. 🎬 视频/直播流渲染管道（M3U8 直播源原地治愈断连）
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
                
                // 🌟 状态无损同步：落盘记录当前全屏播放的视频或直播源，以便主追踪引擎打包回传
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
                
                // 🌟 状态无损同步：落盘记录当前后台正在收听的网络电台戏曲，以便主追踪引擎打包回传
                getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                    .putString("current_push_audio_url", url)
                    .putString("current_push_photo_url", "")
                    .putString("current_push_video_url", "")
                    .putString("current_push_web_url", "").commit();
                Toast.makeText(RemoteDisplayActivity.this, "📻 局域网/云端广播已起播", Toast.LENGTH_SHORT).show();
            });
            backgroundAudioPlayer.setOnErrorListener((mp, what, extra) -> {
                handler.removeCallbacks(timeoutRunnable);
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
     * 4. 🖼️ 图片解析管道（5MB 内存预读缓冲区，杜绝二次连接漏洞 + 2K级降采样抗 OOM）
     */
    private void initImage(final String url) {
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rootLayout.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        new Thread(() -> {
            HttpURLConnection conn = null; InputStream is = null;
            try {
                URL imgUrl = new URL(url);
                conn = (HttpURLConnection) imgUrl.openConnection();
                conn.setConnectTimeout(6000); conn.setReadTimeout(6000); conn.connect();
                if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
                
                // 挂载高阶全包围缓冲区，赋予输入流指针 mark/reset 特权
                is = new BufferedInputStream(conn.getInputStream());
                is.mark(1024 * 1024 * 5); // 预锁 5MB 缓冲空间

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                
                try {
                    is.reset(); // 🟢 关键：流指针秒回原点，阻断对服务器的第二次网络连接
                } catch (Exception e) {
                    if (conn != null) conn.disconnect();
                    conn = (HttpURLConnection) imgUrl.openConnection();
                    conn.setConnectTimeout(6000); is = conn.getInputStream();
                }

                // 计算抗爆发采样率 (限制最大宽度 1920 规避低配机 OOM)
                int inSampleSize = 1;
                if (opts.outWidth > 1920 || opts.outHeight > 1920) {
                    final int halfHeight = opts.outHeight / 2; final int halfWidth = opts.outWidth / 2;
                    while ((halfHeight / inSampleSize) >= 1080 && (halfWidth / inSampleSize) >= 1920) inSampleSize *= 2;
                }
                
                opts.inJustDecodeBounds = false; opts.inSampleSize = inSampleSize;
                Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
                
                if (bitmap != null) {
                    handler.removeCallbacks(timeoutRunnable);
                    final Bitmap bmp = bitmap;
                    runOnUiThread(() -> {
                        imageView.setImageBitmap(bmp);
                        // 🌟 状态无损同步：落盘记录当前全屏照片墙挂载的图片网址，以便主追踪引擎打包回传
                        getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                            .putString("current_push_photo_url", url)
                            .putString("current_push_video_url", "")
                            .putString("current_push_audio_url", "")
                            .putString("current_push_web_url", "").commit();
                    });
                } else {
                    throw new Exception("Bitmap null");
                }
            } catch (Exception e) {
                handler.removeCallbacks(timeoutRunnable);
                FileLogger.log(this, "COMPANION", "❌ 图片下载硬伤崩溃: " + e.getMessage());
                runOnUiThread(() -> { Toast.makeText(this, "图片挂载失败", Toast.LENGTH_SHORT).show(); finish(); });
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) {}
                try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            }
        }).start();
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
        if (hasFocus) hideSystemUI(); // 失去焦点恢复（如短信弹窗关闭后）再次自动强切全面屏沉浸
    }

    // 5. 按键拦截与多模智能退出逻辑
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isLocked) return true; // 3秒硬解锁定门限，防老人手抖误触退出

        // 🌟 网页特权：如果当前正在全屏浏览网页（且网页自身可以返回上一页，如点开了新闻详情）
        if (webView != null && webView.canGoBack() && keyCode == KeyEvent.KEYCODE_BACK) {
            webView.goBack(); // 优先响应网页后退，不关闭界面
            return true;
        }

        // 其余情况（或网页退无可退、按了遥控器OK键等），Activity 直接自毁离场归还手机桌面
        finish();
        return true;
    }

    private final Runnable timeoutRunnable = () -> {
        FileLogger.log(RemoteDisplayActivity.this, "COMPANION", "⚠️ 15秒流加载超时，启动异常自愈断路器");
        finish();
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        resetAllViews();
        handler.removeCallbacksAndMessages(null);
        
        // 🌟 退出状态联动清空：清空所有在播痕迹，主追踪服务下次上报会自动把云端状态对齐洗净
        getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
            .putString("current_push_photo_url", "")
            .putString("current_push_video_url", "")
            .putString("current_push_audio_url", "")
            .putString("current_push_web_url", "").commit();
    }
}
