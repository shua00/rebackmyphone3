package com.example.devicefinder;

import android.content.Context;
import android.content.Intent;

/**
 * 远程陪伴插件：逻辑调度中枢。
 * 核心职责：拦截专用前缀指令，判断开关状态，安全地唤起 RemoteDisplayActivity。
 */
public class CompanionModule {
    
    // 简单的内存防抖，避免路由器/多网卡导致瞬间连发多次弹屏
    private static long lastPushTime = 0;
    private static final long DEBOUNCE_MS = 2000;

    public static void handleUdp(Context context, String message) {
    if (message == null) return;

    // 1. 开关指令必须优先处理，不受当前开关状态影响
    if (message.startsWith("FIND_ME_PLUGIN:companion:")) {
        String[] parts = message.split(":", 4);
        if (parts.length == 4) {
            boolean enable = "on".equals(parts[3].trim());
            PluginRegistry.setEnabled(context, PluginRegistry.PLUGIN_COMPANION, enable);
            try {
                android.widget.Toast.makeText(context,
                        enable ? "✅ 远程陪伴已开启" : "🕶️ 远程陪伴已关闭",
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        }
        return;
    }

    // 2. 其余指令：插件未开启直接吞掉，保持隐蔽
    if (!PluginRegistry.isEnabled(context, PluginRegistry.PLUGIN_COMPANION)) {
        return;
    }

    // 3. 拦截推送指令
    if (message.startsWith("FIND_ME_PUSH_PHOTO:") || message.startsWith("FIND_ME_PUSH_VIDEO:")) {
        long now = System.currentTimeMillis();
        if (now - lastPushTime < DEBOUNCE_MS) return;
        lastPushTime = now;

        String[] parts = message.split(":", 3);
        if (parts.length == 3) {
            String type = message.startsWith("FIND_ME_PUSH_VIDEO") ? "video" : "image";
            String contentUrl = parts[2].trim();
            if (isValidUrl(contentUrl)) {
                FileLogger.log(context, "COMPANION", "📺 收到推送: " + type + " -> " + contentUrl);
                launchDisplay(context, type, contentUrl);
            }
        }
    }
}

    private static void launchDisplay(Context context, String type, String url) {
        Intent intent = new Intent(context, RemoteDisplayActivity.class);
        intent.putExtra("type", type);
        intent.putExtra("url", url);
        // 核心保命 Flag：独立任务栈，且清理之前可能残留的弹窗
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            FileLogger.log(context, "COMPANION", "❌ 唤起全屏展示失败: " + e.getMessage());
        }
    }

    private static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 插件关闭时的清理工作
     */
    public static void cleanup(Context context) {
        // 目前 RemoteDisplayActivity 会由 Android 系统控制生命周期
        // 如果有悬浮球需要关闭，后续在这里实现
        FileLogger.log(context, "COMPANION", "🧹 执行陪伴插件清理");
    }
}
