package com.example.devicefinder;

import android.content.Context;
import android.content.Intent;

/**
 * CompanionModule — 远程陪伴插件中枢（终极工业抗洪版本）
 * 核心设计：彻底废弃 substring 测算，改用显式关键字硬匹配，绝不给高并发留出信令移位裂缝
 */
public class CompanionModule {

    private static long lastPushTime = 0;
    private static final long DEBOUNCE_MS = 2000;

    public static final String ACTION_COMPANION_SHUTDOWN = "com.example.devicefinder.ACTION_COMPANION_SHUTDOWN";

    public static void handleUdp(Context context, String message) {
        if (message == null) return;

        // ★ 第 1 步：陪伴总开关指令永远最优先处理
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

        // ★ 第 2 步：插件未开启，直接吞掉
        if (!PluginRegistry.isEnabled(context, PluginRegistry.PLUGIN_COMPANION)) {
            return;
        }

        // ★ 第 3 步：多模态五通路推送指令（彻底废弃 substring 测算，改用钢铁级硬匹配）
        if (message.startsWith("FIND_ME_PUSH_PHOTO:") 
                || message.startsWith("FIND_ME_PUSH_VIDEO:")
                || message.startsWith("FIND_ME_PUSH_AUDIO:") 
                || message.startsWith("FIND_ME_PUSH_WEB:")) {
            
            long now = System.currentTimeMillis();
            if (now - lastPushTime < DEBOUNCE_MS) return; // 拦截同一微秒内爆发的其余 23 个重复包
            lastPushTime = now;

            String[] parts = message.split(":", 3);
            if (parts.length == 3) {
                String contentUrl = parts[2].trim();
                if (isValidUrl(contentUrl)) {
                    
                    // 🌟 钢铁级对齐：使用 startsWith 显式精准划分 type，完美对齐 Activity 的 if-else 阀门
                    String type = "push_photo"; // 默认归图
                    if (message.startsWith("FIND_ME_PUSH_VIDEO:")) {
                        type = "video";
                    } else if (message.startsWith("FIND_ME_PUSH_AUDIO:")) {
                        type = "push_audio";
                    } else if (message.startsWith("FIND_ME_PUSH_WEB:")) {
                        type = "push_web";
                    }

                    FileLogger.log(context, "COMPANION", "📺 [信令闭环] 智能匹配下发通道: " + type + " -> " + contentUrl);
                    launchDisplay(context, type, contentUrl);
                }
            }
        }
    }

    private static void launchDisplay(Context context, String type, String url) {
        Intent intent = new Intent(context, RemoteDisplayActivity.class);
        intent.putExtra("type", type);
        intent.putExtra("url", url);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            FileLogger.log(context, "COMPANION", "❌ 唤起全屏展示崩溃失败: " + e.getMessage());
        }
    }

    private static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    public static void cleanup(Context context) {
        if (context == null) return;
        FileLogger.log(context, "COMPANION", "🧹 执行陪伴插件清理：发出强下线广播");
        try {
            Intent shutdownIntent = new Intent(ACTION_COMPANION_SHUTDOWN);
            shutdownIntent.setPackage(context.getPackageName());
            context.sendBroadcast(shutdownIntent);
        } catch (Exception e) {
            FileLogger.log(context, "COMPANION", "⚠️ 触发下线自毁广播异常: " + e.getMessage());
        }
    }
}
