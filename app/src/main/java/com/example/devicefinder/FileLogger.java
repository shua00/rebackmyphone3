/*
 * ============================================================
 *  FileLogger.java — 单线程写文件 + 跨天轮转 + 内存快照
 * ------------------------------------------------------------
 *  构建批次 : 20261111-2000
 *  协议版本 : v2
 *  最后修改 : 2026-11-12 22:45（v2.0 通道可视化 + v2.0.1 架构优化 + 修复版本常量）
 * ============================================================
 */

package com.example.devicefinder;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileLogger {
    private static final String TAG = "FileLogger";
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_MEMORY_LOGS = 50;   // 原 20→50，加日志后需更多快照
    private static final int KEEP_DAYS = 7;          // 历史日志保留 7 天
    private static final String LOG_FILE_NAME = "device_log.txt";
    private static final String LOG_FILE_PREFIX = "device_log_";

    private static final SimpleDateFormat sdfTime = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private static final LinkedList<String> memoryLogs = new LinkedList<>();

    // 单线程写文件，避免每次 new Thread（原实现线程爆炸）
    private static final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();

    // 记录当前文件日期，跨天时轮转
    private static String currentFileDate = null;

    /**
     * INFO/WARN/ERROR：写文件 + 内存 + logcat
     */
    public static void log(Context context, String tag, String msg) {
        String time = sdfTime.format(new Date());
        String line = time + " | " + tag + " | " + msg;

        synchronized (memoryLogs) {
            memoryLogs.add(line);
            if (memoryLogs.size() > MAX_MEMORY_LOGS) {
                memoryLogs.removeFirst();
            }
        }

        Log.i("DF_" + tag, msg);

        final Context appCtx = context.getApplicationContext();
        fileExecutor.execute(() -> writeToFile(appCtx, line));
    }

    /**
     * DEBUG：只进 logcat，不进文件、不上报云端
     * 用于高频日志（定位每次变化、UDP 每个包等）
     */
    public static void logD(String tag, String msg) {
        Log.d("DF_" + tag, msg);
    }

    private static void writeToFile(Context context, String line) {
        // ★ 补丁6：防弹衣，保证底层 IO 被系统沙盒拦截时绝不崩溃
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;

            Date now = new Date();
            String today = sdfDate.format(now);
            File logFile = new File(dir, LOG_FILE_NAME);

            // 进程重启后也按文件实际修改日期判断跨天，不能依赖进程内缓存。
            if (logFile.exists() && logFile.length() > 0
                    && !today.equals(sdfDate.format(new Date(logFile.lastModified())))) {
                if (!archiveCurrentLog(dir, logFile, now)) return;
            }

            byte[] encodedLine = (line + "\\n").getBytes("UTF-8");
            // 超过单文件预算时先归档旧文件，再创建新文件；绝不 delete 后重写。
            if (logFile.exists() && logFile.length() > 0
                    && logFile.length() + encodedLine.length > MAX_FILE_SIZE) {
                if (!archiveCurrentLog(dir, logFile, now)) return;
            }

            FileWriter writer = new FileWriter(logFile, true);
            writer.write(line);
            writer.write("\\n");
            writer.flush();
            writer.close();
            cleanOldLogs(dir);
        } catch (Exception e) {
            // ★ 补丁6核心：捕获所有异常（含 SecurityException），防止进程崩溃
            Log.e(TAG, "写入日志失败(已被系统拦截): " + e.getMessage());
        }
    }

    /**
     * 将当前日志归档为唯一文件名。归档失败时保留原文件并停止本次写入，
     * 避免在空间/文件系统异常时覆盖唯一诊断证据。
     */
    private static boolean archiveCurrentLog(File dir, File current, Date now) {
        if (current == null || !current.exists() || current.length() == 0) return true;
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(now);
        File archived = new File(dir, LOG_FILE_PREFIX + stamp + ".txt");
        int suffix = 1;
        while (archived.exists()) {
            archived = new File(dir, LOG_FILE_PREFIX + stamp + "_" + suffix++ + ".txt");
        }
        if (!current.renameTo(archived)) {
            Log.e(TAG, "日志归档失败，保留原文件并暂停本次写入: " + current.getName());
            return false;
        }
        Log.i(TAG, "日志已归档: " + archived.getName() + " (" + archived.length() + " bytes)");
        return true;
    }

    private static void cleanOldLogs(File dir) {
        try {
            long now = System.currentTimeMillis();
            long maxAge = KEEP_DAYS * 24L * 60 * 60 * 1000;
            File[] files = dir.listFiles((d, name) -> name.startsWith(LOG_FILE_PREFIX) && name.endsWith(".txt"));
            if (files == null) return;
            for (File f : files) {
                if (now - f.lastModified() > maxAge) f.delete();
            }
        } catch (Exception ignored) {
            // ★ 补丁6：同样兜底
        }
    }

    public static String getMemoryLogs() {
        synchronized (memoryLogs) {
            if (memoryLogs.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String log : memoryLogs) sb.append(log).append("\n");
            return sb.toString();
        }
    }
}
