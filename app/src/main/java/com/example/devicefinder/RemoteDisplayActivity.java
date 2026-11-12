package com.example.devicefinder;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class RemoteDisplayActivity extends Activity {

    private boolean isLocked = true; // 3 秒防误触锁
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. 保持亮屏 & 沉浸式全屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        String type = getIntent().getStringExtra("type");
        String url = getIntent().getStringExtra("url");

        if (url == null || url.trim().isEmpty()) {
            finish();
            return;
        }

        if ("video".equals(type)) {
            initVideo(url);
        } else {
            initImage(url); // 默认按图片处理
        }

        // 2. 3 秒防误触倒计时
        handler.postDelayed(() -> {
            isLocked = false;
            // 可选：Toast 提示一下"现在可以按返回键退出了"
        }, 3000);
    }

    private void initVideo(String url) {
        try {
            videoView = new VideoView(this);
            setContentView(videoView);
            videoView.setVideoURI(Uri.parse(url));
            videoView.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                videoView.start();
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                FileLogger.log(this, "COMPANION", "❌ 视频播放失败: what=" + what);
                Toast.makeText(this, "视频加载失败", Toast.LENGTH_SHORT).show();
                finish();
                return true;
            });
        } catch (Exception e) {
            finish();
        }
    }

    private void initImage(String url) {
        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(imageView);

        // 异步下载图片，加入 OOM 降采样保护
        new Thread(() -> {
            try {
                // 设置超时，防止死等
                URL imgUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) imgUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                InputStream is = conn.getInputStream();

                // OOM 保护：先读宽高
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                is.close();

                // 计算降采样率 (目标最大宽高 1920)
                int inSampleSize = 1;
                if (opts.outWidth > 1920 || opts.outHeight > 1920) {
                    final int halfHeight = opts.outHeight / 2;
                    final int halfWidth = opts.outWidth / 2;
                    while ((halfHeight / inSampleSize) >= 1920 && (halfWidth / inSampleSize) >= 1920) {
                        inSampleSize *= 2;
                    }
                }

                // 真正加载
                conn = (HttpURLConnection) imgUrl.openConnection();
                is = conn.getInputStream();
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = inSampleSize;
                Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
                is.close();

                if (bitmap != null) {
                    runOnUiThread(() -> imageView.setImageBitmap(bitmap));
                } else {
                    throw new Exception("Bitmap decode null");
                }
            } catch (Exception e) {
                FileLogger.log(this, "COMPANION", "❌ 图片加载失败: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    // 3. 按键拦截与退出逻辑
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isLocked) {
            // 3秒内无视任何按键
            return true; 
        }
        // 3秒后，按任意键退出 (遥控器返回键、OK键、手机返回键均可)
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoView != null) {
            try {
                videoView.stopPlayback();
                videoView.suspend();
            } catch (Exception ignored) {}
        }
        handler.removeCallbacksAndMessages(null);
    }
}