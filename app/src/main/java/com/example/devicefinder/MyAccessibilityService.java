/*
 * ============================================================
 *  MyAccessibilityService.java — 无障碍保活外壳（含看门狗）
 * ------------------------------------------------------------
 *  构建批次 : 20261111-2000
 *  协议版本 : v2
 *  最后修改 : 2026-11-17 21:00（P0级保活修复：加入 30 分钟看门狗）
 * ============================================================
 */

package com.example.devicefinder;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍保活服务（含 30 分钟看门狗轮询）
 */
public class MyAccessibilityService extends AccessibilityService {

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isTrackingServiceRunning()) {
                FileLogger.log(MyAccessibilityService.this, "ACCESSIBILITY",
                        "🐕 看门狗：TrackingService 不在，强行拉起");
                try {
                    Intent intent = new Intent(MyAccessibilityService.this, TrackingService.class);
                    intent.setAction("ACTION_WAKE_BY_ACCESSIBILITY");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                } catch (Exception e) {
                    FileLogger.log(MyAccessibilityService.this, "ACCESSIBILITY",
                            "❌ 看门狗拉起失败: " + e.getMessage());
                }
            }
            // 每 30 分钟检查一次
            watchdogHandler.postDelayed(this, 30 * 60 * 1000L);
        }
    };

    private boolean isTrackingServiceRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info.service.getClassName().equals(TrackingService.class.getName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 纯保活外壳，不处理事件
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            Intent intent = new Intent(this, TrackingService.class);
            intent.setAction("ACTION_WAKE_BY_ACCESSIBILITY");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            FileLogger.log(this, "ACCESSIBILITY", "♿ 无障碍连接成功，已拉起 TrackingService");
        } catch (Exception e) {
            FileLogger.log(this, "ACCESSIBILITY", "❌ 拉起失败: " + e.getMessage());
        }

        // 启动看门狗
        watchdogHandler.removeCallbacks(watchdogRunnable);
        watchdogHandler.postDelayed(watchdogRunnable, 30 * 60 * 1000L);
        FileLogger.log(this, "ACCESSIBILITY", "🐕 看门狗已启动（30分钟周期）");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        watchdogHandler.removeCallbacksAndMessages(null);
        FileLogger.log(this, "ACCESSIBILITY", "🛑 无障碍服务销毁，看门狗停止");
    }
}
