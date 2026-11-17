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
 * RemoteDisplayActivity — 远程陪伴五模全自动多媒体容器（精准对齐版）
 * 核心设计：100% 保留最初版本测试通过的图片mark/reset缓冲区与降采样防爆装甲，追加纯音频、全网页和状态自动回传。
 */
public class RemoteDisplayActivity extends Activity {

    private boolean isLocked = true; // 3 秒防误触锁
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView videoView;
    private ImageView imageView;
    private WebView webView;
    private MediaPlayer backgroundAudioPlayer; 
    
    private FrameLayout rootLayout;
    private final int LOADING_TIMEOUT_MS = 15000; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. 保持亮屏 & 沉浸式全屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        // 重置状态
        handler.removeCallbacks(timeoutRunnable);
        handler.postDelayed(timeoutRunnable, LOADING_TIMEOUT_MS);
        resetAllViews();

        // 智能五通路网关分流
        if ("video".equals(type)) {
            initVideo(url);
        } else if ("push_audio".equals(type)) {
            initAudio(url);
        } else if ("push_web".equals(type)) {
            initWebView(url);
        } else {
            // 盲发纠偏线
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
     * 1. 🌐 全功能网页加载引擎
     */
    private void initWebView(final String url) {
        FileLogger.log(this, "WEB_CORE", "🌐 全屏纯净 H5 容器挂载启动: " + url);
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
     * 4. 🖼️ 图片解析管道（100% 还原您最初测试通过的 mark/reset 双全缓冲机制 + OOM 防护）
     */
    private void initImage(final String url) {
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(android.graphics.Color.BLACK);
        rootLayout.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        // 异步下载图片，加入原汁原味的 OOM 降采样与单连接预读保护
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            try {
                URL imgUrl = new URL(url);
                conn = (HttpURLConnection) imgUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();

                // 🌟 100% 还原最初成功的 mark/reset 原流回弹指针设计，拒绝二次连接断流
                is = new BufferedInputStream(conn.getInputStream());
                is.mark(1024 * 1024 * 5); // 预锁 5MB 内存预读缓冲区

                // OOM 保护：先读外廓尺寸
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                
                try {
                    is.reset(); // 原流指针秒回起点
                } catch (Exception e) {
                    if (conn != null) conn.disconnect();
                    conn = (HttpURLConnection) imgUrl.openConnection();
                    conn.setConnectTimeout(5000);
                    is = conn.getInputStream();
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

                opts.inJustDecodeBounds = false;
                opts.inSampleSize = inSampleSize;
                Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);

                if (bitmap != null) {
                    handler.removeCallbacks(timeoutRunnable); // 成功挂载，撤销断路器
                    final Bitmap finalBitmap = bitmap;
                    runOnUiThread(() -> {
                        if (imageView != null) {
                            imageView.setImageBitmap(finalBitmap);
                            
                            // 🌟 状态同步：落盘记录当前照片墙挂载的图片网址，以便主引擎打包同步
                            getSharedPreferences("device_finder_prefs", MODE_PRIVATE).edit()
                                .putString("current_push_photo_url", url)
                                .putString("current_push_video_url", "")
                                .putString("current_push_audio_url", "")
                                .putString("current_push_web_url", "").commit();
                        }
                    });
                } else {
                    throw new Exception("Bitmap decode null");
                }
            } catch (Exception e) {
                handler.removeCallbacks(timeoutRunnable);
                FileLogger.log(this, "COMPANION", "❌ 图片挂载彻底失败: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
                    finish();
                });
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
        FileLogger.log(RemoteDisplayActivity.this, "COMPANION", "⚠️ 15秒媒体流加载超时自愈触发");
        finish();
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
