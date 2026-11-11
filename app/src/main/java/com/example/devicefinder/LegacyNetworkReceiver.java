/*
 * ============================================================
 *  LegacyNetworkReceiver.java — 低版本网络恢复广播
 * ------------------------------------------------------------
 *  构建批次 : 20261111-2000
 *  协议版本 : v2
 *  最后修改 : 2026-11-11 19:45（注释规范统一（无逻辑改动））
 * ============================================================
 */

package com.example.devicefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

public class LegacyNetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // 仅在 Android 7.0 以下处理（7.0及以上由 TrackingService 的 NetworkCallback 接管）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return;

        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                // 网络恢复，唤起前台服务强制上报一次
                Intent svc = new Intent(context, TrackingService.class);
                svc.setAction("ACTION_FORCE_REPORT");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }
        }
    }
}
