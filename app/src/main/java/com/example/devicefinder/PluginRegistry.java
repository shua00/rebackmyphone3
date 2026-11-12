package com.example.devicefinder;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 插件注册表与全局开关。
 * 核心职责：管理各个独立模块的开启/关闭状态，确保默认完全静默。
 */
public class PluginRegistry {
    public static final String PLUGIN_COMPANION = "companion";
    public static final String PLUGIN_HEALTH = "health";

    private static final String PREF_NAME = "device_finder_prefs";

    public static boolean isEnabled(Context ctx, String pluginId) {
        if (ctx == null) return false;
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean("plugin_" + pluginId + "_enabled", false);
    }

    public static void setEnabled(Context ctx, String pluginId, boolean enabled) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        // 使用 commit 确保状态立即落盘
        sp.edit().putBoolean("plugin_" + pluginId + "_enabled", enabled).commit();
        
        FileLogger.log(ctx, "PLUGIN", "🔧 插件 [" + pluginId + "] 状态变更为: " + (enabled ? "开启" : "关闭"));
        
        // 联动清理：关闭陪伴插件时，清理可能残留的 UI
        if (PLUGIN_COMPANION.equals(pluginId) && !enabled) {
            CompanionModule.cleanup(ctx);
        }
    }
}