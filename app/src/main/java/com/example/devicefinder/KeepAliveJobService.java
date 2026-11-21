/*
 * ============================================================
 *  KeepAliveJobService.java — JobScheduler 周期保活
 * ------------------------------------------------------------
 *  构建批次 : 20261111-2000
 *  协议版本 : v2
 *  最后修改 : 2026-11-21 10:45（优化范围：同步完成语义、离线可调度、Job 唤醒日志，保留 JOB_ID、15 分钟周期、持久化设置和原有拉起行为）
 * ============================================================
 */

package com.example.devicefinder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class KeepAliveJobService extends JobService {

    private static final int JOB_ID = 10086;
    private static final long JOB_INTERVAL_MS = 15 * 60 * 1000L;

    @Override
    public boolean onStartJob(JobParameters params) {
        FileLogger.log(this, "JOB",
                "⏰ JobService 收到系统调度，jobId=" + params.getJobId());

        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction("ACTION_JOB_WAKE_UP");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            FileLogger.log(this, "JOB",
                    "✅ 已提交 TrackingService 启动请求，jobId=" + params.getJobId());
        } catch (Exception e) {
            FileLogger.log(this, "JOB",
                    "❌ 拉起 TrackingService 失败，jobId=" + params.getJobId()
                            + " error=" + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }

        // 本任务只同步提交一次服务启动请求，没有异步 Job 工作。
        // 返回 false 即表示本次 Job 已完成，不需要再调用 jobFinished()。
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        FileLogger.log(this, "JOB",
                "⏹ Job 被系统停止，不请求重试，jobId=" + params.getJobId());
        return false;
    }

    public static void scheduleJob(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        Context appContext = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) appContext.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            FileLogger.log(appContext, "JOB", "❌ JobScheduler 不可用");
            return;
        }

        // 同一 JOB_ID 已存在时不重新 schedule，避免无意义地重置周期。
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == JOB_ID) {
                FileLogger.log(appContext, "JOB",
                        "ℹ️ 周期任务已存在，跳过重复注册，jobId=" + JOB_ID);
                return;
            }
        }

        JobInfo.Builder builder = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(appContext, KeepAliveJobService.class));
        builder.setPeriodic(JOB_INTERVAL_MS);
        builder.setPersisted(true);

        // 不设置网络约束：保活唤醒不依赖网络，离线时也允许系统调度。
        try {
            int result = scheduler.schedule(builder.build());
            if (result == JobScheduler.RESULT_SUCCESS) {
                FileLogger.log(appContext, "JOB",
                        "✅ 周期任务注册成功，间隔15分钟，jobId=" + JOB_ID);
            } else {
                FileLogger.log(appContext, "JOB",
                        "❌ 周期任务注册失败，result=" + result + " jobId=" + JOB_ID);
            }
        } catch (Exception e) {
            FileLogger.log(appContext, "JOB",
                    "❌ 周期任务注册异常: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }
    }
}

