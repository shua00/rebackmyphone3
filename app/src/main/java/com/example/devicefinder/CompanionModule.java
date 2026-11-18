package com.example.devicefinder;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * CompanionModule — 远程陪伴插件中枢【最小必要改动版 minimal-20261118】
 *
 * 与仓库原版的差异只有 4 处，全部对应已确诊问题，其余逻辑逐字保持原样：
 *   [M1] 开关 off 时不再静默 return：自动上膛并放行（确诊根因，device_log.txt 03:49:57 那 27 个包）
 *   [M2] 每个"被丢弃/被拦截"的分支都落 FileLogger（原版 4 个静默黑洞）
 *   [M3] Toast 回主线程（原版在 UDP 接收线程 show()，异常被 catch(ignored) 吞掉 → 气泡永不弹）
 *   [M4] 去重由"全局 2 秒窗"改为"同一 URL 8 秒窗"（原版连推两张不同照片，第 2 张必被吞）
 *
 * 明确未做（上一版有、本版删掉，理由见交付说明）：
 *   - 不做 secret 硬拦截：原版从不校验，加了就多一处"常量漂移→全链路静默丢包"的失败面；
 *     本版只在 secret 不一致时打一条 warn 日志，放行行为与原版一致。
 *   - 不做全屏通知兜底：Android 10+ 后台起 Activity 未被日志确诊；且该通道依赖
 *     POST_NOTIFICATIONS 授权，Android 13+ 未授权时 notify() 静默丢弃，反而比原版更差。
 *   - 不加 handleUdpInternal 双入口：A 档无人使用返回值，纯冗余。
 *
 * handleUdp 签名保持 void，与 TrackingService 原版 7 个调用点逐字兼容，可单独替换。
 * type 值沿用原版长名（push_photo / video / push_audio / push_web），
 * 因此新旧 RemoteDisplayActivity 都能正确分流，不存在必须同批换的耦合。
 */
public class CompanionModule {

    /** 仅用于一致性自检；必须与 TrackingService 第 185 行同名常量一致 */
    private static final String RESCUE_SECRET = "your_private_secret_key";

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_URL = "url";

    /** [M4] 同一 URL 在窗口期内只唤起一次（拦服务端重发包），不同 URL 互不影响 */
    private static final long DUP_WINDOW_MS = 8000;
    private static String lastPushUrl = null;
    private static long lastPushTime = 0;

    /** [M1] true=推送到达而开关为 off 时自动上膛放行(推荐)；false=严格尊重开关并丢弃 */
    private static final boolean AUTO_ARM_ON_PUSH = true;

    public static final String ACTION_COMPANION_SHUTDOWN = "com.example.devicefinder.ACTION_COMPANION_SHUTDOWN";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static void handleUdp(Context context, String message) {
        if (message == null) return;

        // ★ 第 1 步：陪伴总开关指令永远最优先处理（在 isEnabled 检查之前，不受开关与去重影响）
        if (message.startsWith("FIND_ME_PLUGIN:companion:")) {
            String[] parts = message.split(":", 4);
            if (parts.length != 4) {                                   // [M2]
                FileLogger.log(context, "COMPANION",
                        "❌ PLUGIN 信令段数异常(需4段,实得" + parts.length + "): " + message);
                return;
            }
            warnIfSecretMismatch(context, "PLUGIN", parts[2]);
            boolean enable = "on".equals(parts[3].trim());
            PluginRegistry.setEnabled(context, PluginRegistry.PLUGIN_COMPANION, enable);
            FileLogger.log(context, "PLUGIN", "🔧 插件 [companion] 状态变更为: " + (enable ? "开启" : "关闭"));
            showToast(context, enable ? "✅ 远程陪伴已开启" : "🕶️ 远程陪伴已关闭");   // [M3]
            return;
        }

        // ★ 第 2 步：插件未开启 —— 原版此处静默 return，线上无从判断指令是没到还是被吞
        if (!PluginRegistry.isEnabled(context, PluginRegistry.PLUGIN_COMPANION)) {
            String head = brief(message);
            if (AUTO_ARM_ON_PUSH) {
                PluginRegistry.setEnabled(context, PluginRegistry.PLUGIN_COMPANION, true);
                FileLogger.log(context, "PLUGIN", "🔧 插件 [companion] 状态变更为: 开启（由推送指令自动上膛）");
                FileLogger.log(context, "COMPANION", "⚠️ 收到指令时开关为 off，已自动上膛并继续放行: " + head);
            } else {
                FileLogger.log(context, "COMPANION",
                        "🚫 插件开关=companion:off，指令被丢弃(STRICT): " + head + " → 需先下发 companion_on");
                return;
            }
        }

        // ★ 第 3 步：多模态四通路推送指令（显式关键字硬匹配，绝不用 substring 测算）
        if (message.startsWith("FIND_ME_PUSH_PHOTO:")
                || message.startsWith("FIND_ME_PUSH_VIDEO:")
                || message.startsWith("FIND_ME_PUSH_AUDIO:")
                || message.startsWith("FIND_ME_PUSH_WEB:")) {

            String[] parts = message.split(":", 3);
            if (parts.length != 3) {                                   // [M2]
                FileLogger.log(context, "COMPANION",
                        "❌ 推送信令段数异常(需3段,实得" + parts.length + "): " + message);
                return;
            }
            warnIfSecretMismatch(context, "PUSH", parts[1]);

            String contentUrl = parts[2].trim();
            if (!isValidUrl(contentUrl)) {                             // [M2]
                FileLogger.log(context, "COMPANION", "❌ URL 非法(非 http/https)，拒绝渲染: [" + contentUrl + "]");
                return;
            }

            // [M4] 按 URL 去重：拦住服务端重发的同图多包，放行连推的不同照片
            long now = System.currentTimeMillis();
            if (contentUrl.equals(lastPushUrl) && now - lastPushTime < DUP_WINDOW_MS) {
                FileLogger.log(context, "COMPANION",
                        "🔁 命中 " + DUP_WINDOW_MS + "ms 同URL去重窗，忽略重复包(服务端重发放大，非丢包): " + contentUrl);
                return;
            }
            lastPushUrl = contentUrl;
            lastPushTime = now;

            // type 沿用原版长名，新旧 RemoteDisplayActivity 都能正确分流
            String type = "push_photo";
            if (message.startsWith("FIND_ME_PUSH_VIDEO:"))      type = "video";
            else if (message.startsWith("FIND_ME_PUSH_AUDIO:")) type = "push_audio";
            else if (message.startsWith("FIND_ME_PUSH_WEB:"))   type = "push_web";

            FileLogger.log(context, "COMPANION", "📺 [信令闭环] 智能匹配下发通道: " + type + " -> " + contentUrl);
            launchDisplay(context, type, contentUrl);
            return;
        }

        // [M2] 未识别信令前缀：原版这里是整条 if-else 链的静默黑洞，补一行日志
        FileLogger.log(context, "COMPANION", "ℹ️ 非陪伴域信令，本模块忽略: " + brief(message));
    }

    /** 只观测不拦截：secret 漂移时日志里一眼可见，但不会因常量不一致而全链路丢包 */
    private static void warnIfSecretMismatch(Context context, String tag, String secret) {
        if (secret == null || !RESCUE_SECRET.equals(secret.trim())) {
            FileLogger.log(context, "COMPANION",
                    "⚠️ " + tag + " 信令 secret 与 CompanionModule.RESCUE_SECRET 不一致（本版不拦截，仅提示核对）");
        }
    }

    private static String brief(String message) {
        return message.length() > 40 ? message.substring(0, 40) + "..." : message;
    }

    private static void launchDisplay(Context context, String type, String url) {
        Intent intent = new Intent(context, RemoteDisplayActivity.class);
        intent.putExtra(EXTRA_TYPE, type);
        intent.putExtra(EXTRA_URL, url);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
            FileLogger.log(context, "COMPANION", "🚀 已唤起全屏容器 type=" + type);
        } catch (Exception e) {
            // Android 10+ 后台起 Activity 被系统静默拦截时，这里抓不到异常，只会"无反应"；
            // 若日志出现"已唤起"而屏幕无变化，即为该场景，需开悬浮窗权限或上通知兜底版。
            FileLogger.log(context, "COMPANION", "❌ 唤起全屏展示失败: " + e.getMessage());
        }
    }

    private static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** [M3] Toast 必须回主线程，否则在非 Looper 线程直接抛 RuntimeException */
    private static void showToast(final Context context, final String text) {
        try {
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(context.getApplicationContext(),
                                text, android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
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
        lastPushUrl = null;
        lastPushTime = 0;
    }
}
