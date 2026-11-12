#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apply_v201_patch.py — v2.0.1 通用架构优化补丁
"""

import os
import sys
import shutil
import argparse

BACKUP_DIR = ".patch_backup"

PATCHES = {
    "app/src/main/java/com/example/devicefinder/TrackingService.java": [
        {
            "id":   "TS-P1",
            "desc": "定位超时缩短至3秒，解耦上报线程",
            "marker": "final long FRESH_TIMEOUT_MS = 3000L;",
            "old": '''                final long FRESH_TIMEOUT_MS = 10000L;''',
            "new": '''                final long FRESH_TIMEOUT_MS = 3000L; // v2.0.1: 缩短至3秒，防阻塞线程池''',
        },
        {
            "id":   "TS-P2",
            "desc": "定位失败日志优化，明确后续处理",
            "marker": "将使用旧值/空值上报",
            "old": '''                            FileLogger.log(TrackingService.this, "REPORT", "⚠️ 主动刷新定位超时 (耗时 " + costMs + "ms, 旧值 " + (locAgeMs == Long.MAX_VALUE ? "无" : (locAgeMs / 1000) + "s 前") + ")，将使用旧值");''',
            "new": '''                            FileLogger.log(TrackingService.this, "REPORT", "⚠️ 主动刷新定位超时 (耗时 " + costMs + "ms, 旧值 " + (locAgeMs == Long.MAX_VALUE ? "无" : (locAgeMs / 1000) + "s 前") + ")，将使用旧值/空值上报");''',
        },
        {
            "id":   "TS-P3",
            "desc": "新增防抖与排期锁变量",
            "marker": "private final Object alarmScheduleLock = new Object();",
            "old": '''    private static final Object FLUSH_LOCK = new Object();''',
            "new": '''    private static final Object FLUSH_LOCK = new Object();

    // v2.0.1 补丁：防抖与排期锁
    private final Object alarmScheduleLock = new Object();
    private static long lastAlarmTriggerTime = 0;
    private static long lastTrimMemoryLogTime = 0;''',
        },
        {
            "id":   "TS-P4",
            "desc": "ACTION_ALARM_TRIGGER 增加2秒防抖",
            "marker": "if (now - lastAlarmTriggerTime < 2000)",
            "old": '''            } else if ("ACTION_ALARM_TRIGGER".equals(action)) {
                long actual = System.currentTimeMillis();''',
            "new": '''            } else if ("ACTION_ALARM_TRIGGER".equals(action)) {
                // v2.0.1 补丁：2秒内相同来源的重复唤醒，直接掐断
                long now = System.currentTimeMillis();
                if (now - lastAlarmTriggerTime < 2000) {
                    FileLogger.logD("ALARM", "⏭ 拦截 2 秒内的重复定时上报");
                    return START_STICKY;
                }
                lastAlarmTriggerTime = now;

                long actual = System.currentTimeMillis();''',
        },
        {
            "id":   "TS-P5",
            "desc": "scheduleNextReport 增加并发同步锁",
            "marker": "synchronized (alarmScheduleLock)",
            "old": '''    private void scheduleNextReport() {
        if (alarmManager == null || alarmPendingIntent == null) return;

        // ★ 补丁4：强制 cancel 先前可能因快速轮询或手动触发而在内核留存的旧句柄。
        // 彻底阻断因并发触发导致的闹钟频率超限（Quota 限流），消灭偶发性假死。
        try { alarmManager.cancel(alarmPendingIntent); } catch (Exception ignored) {}

        int rc = reportCount.get();
        // reportIntervalMs 已从 reportAlarmIntervalMs 初始化，远端 config 可继续覆盖
        long delay = (rc == 1 || rc == 2) ? 3 * 60 * 1000L : reportIntervalMs;
        long triggerAtMillis = System.currentTimeMillis() + delay;
        lastAlarmExpectedAt = triggerAtMillis;
        // ★ H2 修复 2026-11-07：30 分钟上报不需要秒级精度，改用 setAndAllowWhileIdle，
        //   避免占用 Android 12+ 精确闹钟配额（72 次/天）。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
        } catch (Exception e) {
            FileLogger.log(this, "ALARM", "⚠️ 主上报闹钟设置失败: " + e.getMessage());
        }
    }''',
            "new": '''    private void scheduleNextReport() {
        if (alarmManager == null || alarmPendingIntent == null) return;

        synchronized (alarmScheduleLock) { // v2.0.1 补丁：确保排队与取消的原子性
            // ★ 补丁4：强制 cancel 先前可能因快速轮询或手动触发而在内核留存的旧句柄。
            // 彻底阻断因并发触发导致的闹钟频率超限（Quota 限流），消灭偶发性假死。
            try { alarmManager.cancel(alarmPendingIntent); } catch (Exception ignored) {}

            int rc = reportCount.get();
            // reportIntervalMs 已从 reportAlarmIntervalMs 初始化，远端 config 可继续覆盖
            long delay = (rc == 1 || rc == 2) ? 3 * 60 * 1000L : reportIntervalMs;
            long triggerAtMillis = System.currentTimeMillis() + delay;
            lastAlarmExpectedAt = triggerAtMillis;
            // ★ H2 修复 2026-11-07：30 分钟上报不需要秒级精度，改用 setAndAllowWhileIdle，
            //   避免占用 Android 12+ 精确闹钟配额（72 次/天）。
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
                else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent);
            } catch (Exception e) {
                FileLogger.log(this, "ALARM", "⚠️ 主上报闹钟设置失败: " + e.getMessage());
            }
        }
    }''',
        },
        {
            "id":   "TS-P6",
            "desc": "onTrimMemory 增加 60 秒时间窗节流",
            "marker": "if (now - lastTrimMemoryLogTime < 60 * 1000L) return;",
            "old": '''        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            try {''',
            "new": '''        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // v2.0.1 补丁：60 秒时间窗，超过 1 分钟才允许记录一次文件，防高频 IO
            long now = System.currentTimeMillis();
            if (now - lastTrimMemoryLogTime < 60 * 1000L) return;
            lastTrimMemoryLogTime = now;

            try {''',
        },
    ],

    "CHANGELOG.txt": [
        {
            "id":   "CL-P7",
            "desc": "新增 v2.0.1 架构优化记录",
            "marker": "v2.0.1 — 2026-11-12",
            "old": '''================================================================================
v2.0 — 2026-11-11 · 协议 v2 稳定版 + 注释规范统一
================================================================================''',
            "new": '''================================================================================
v2.0.1 — 2026-11-12 · 通用架构优化（防抖 / 解耦 / 节流）
================================================================================

【变更】TrackingService.java · 定位获取解耦（防阻塞）
--------------------------------------------------------------------------------
- 单次主动刷新定位的超时时间从 10000ms 缩短至 3000ms
  原因：在 Android 15 室内或弱信号环境，系统底层长时间不回调。
        原设计强行等待 10 秒会严重阻塞上报线程池，拖慢网络 IO。
        优化后超时时间锐减，拿不到定位直接带空值上报，保证设备在线可追踪。
- 超时日志文案更新为「将使用旧值/空值上报」，明确告知排错人员未发生丢包。

【修复】TrackingService.java · 闹钟并发堆积（ZTE 实测同秒触发 3 次）
--------------------------------------------------------------------------------
- ACTION_ALARM_TRIGGER 分支新增 2 秒防抖拦截
  原因：系统底层调度异常或高频并发时，可能连续抛出相同唤醒。
        2 秒内的重复唤醒直接拦截丢弃，不执行业务。
- scheduleNextReport() 增加 alarmScheduleLock 同步锁
  原因：确保“取消旧闹钟”和“注册新闹钟”在内核中绝对原子。
        彻底杜绝多通道并发导致系统 PendingIntent 队列中堆积幽灵闹钟。

【变更】TrackingService.java · 内存警告日志节流
--------------------------------------------------------------------------------
- onTrimMemory 增加 60 秒时间窗限制
  原因：低端机（如中兴 MyOS）后台频繁 GC，原版一天会写入上百条日志。
        加窗后每分钟最多记录一次，既保留排错线索，又延长闪存寿命。

================================================================================
v2.0 — 2026-11-11 · 协议 v2 稳定版 + 注释规范统一
================================================================================''',
        },
    ],
}

def normalize_patches():
    for rel_path, patches in PATCHES.items():
        for p in patches:
            for key in ("old", "new"):
                if key in p:
                    p[key] = p[key].replace("\r\n", "\n")

def apply_one_file(rel_path, patches, root, dry_run):
    full_path = os.path.join(root, rel_path)
    if not os.path.exists(full_path):
        return "fail", f"文件不存在: {full_path}", []

    with open(full_path, "rb") as f:
        raw = f.read()

    newline = "\r\n" if b"\r\n" in raw else "\n"
    content = raw.decode("utf-8").replace("\r\n", "\n")
    original = content

    log = []
    for p in patches:
        pid = p["id"]
        desc = p["desc"]

        if p["marker"] in content:
            log.append((pid, "skip", desc + "  (已应用)"))
            continue

        cnt = content.count(p["old"])
        if cnt == 0:
            log.append((pid, "fail", desc + "  — 锚点不存在"))
            return "fail", "锚点不存在，该文件不做任何修改", log
        if cnt > 1:
            log.append((pid, "fail", desc + f"  — 锚点重复出现 {cnt} 次"))
            return "fail", f"锚点重复 {cnt} 次，该文件不做任何修改", log

        content = content.replace(p["old"], p["new"], 1)
        log.append((pid, "ok", desc))

    if content == original:
        return "unchanged", "无变化", log

    if not dry_run:
        backup_path = os.path.join(root, BACKUP_DIR, rel_path)
        os.makedirs(os.path.dirname(backup_path), exist_ok=True)
        shutil.copy2(full_path, backup_path)

        output = content.replace("\n", newline)
        with open(full_path, "wb") as f:
            f.write(output.encode("utf-8"))

    return "ok", ("已预览" if dry_run else "已写回"), log

def do_restore(root):
    backup_root = os.path.join(root, BACKUP_DIR)
    if not os.path.isdir(backup_root):
        print(f"[FAIL] 备份目录不存在: {backup_root}")
        sys.exit(1)

    restored = 0
    for dirpath, _, filenames in os.walk(backup_root):
        for fn in filenames:
            backup_file = os.path.join(dirpath, fn)
            rel = os.path.relpath(backup_file, backup_root)
            target = os.path.join(root, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(backup_file, target)
            print(f"  [OK]   {rel}")
            restored += 1
    print(f"\n共恢复 {restored} 个文件")

def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    parser = argparse.ArgumentParser(description="v2.0.1 通用架构优化补丁")
    parser.add_argument("--root", default=".", help="项目根目录")
    parser.add_argument("--dry-run", action="store_true", help="只预览")
    parser.add_argument("--restore", action="store_true", help="从 .patch_backup/ 恢复")
    args = parser.parse_args()

    root = os.path.abspath(args.root)

    if args.restore:
        print("=" * 64)
        print("  apply_v201_patch.py --restore")
        print("=" * 64)
        print(f"  根目录 : {root}\n")
        do_restore(root)
        return

    normalize_patches()

    mode = "预览（dry-run）" if args.dry_run else "应用（写文件）"

    print("=" * 64)
    print("  apply_v201_patch.py — v2.0.1 通用架构优化补丁")
    print("=" * 64)
    print(f"  根目录   : {root}")
    print(f"  模式     : {mode}")
    print(f"  备份目录 : {BACKUP_DIR}/")
    print("=" * 64 + "\n")

    total_files = len(PATCHES)
    total_patch_ok = 0
    total_patch_skip = 0
    total_patch_fail = 0
    files_ok = 0
    files_fail = 0

    for idx, (rel_path, patches) in enumerate(PATCHES.items(), 1):
        print(f"[{idx}/{total_files}] {rel_path}")
        status, summary, log = apply_one_file(rel_path, patches, root, args.dry_run)

        for pid, st, desc in log:
            if st == "ok":
                print(f"  [OK]   {pid}  {desc}")
                total_patch_ok += 1
            elif st == "skip":
                print(f"  [SKIP] {pid}  {desc}")
                total_patch_skip += 1
            else:
                print(f"  [FAIL] {pid}  {desc}")
                total_patch_fail += 1

        if status == "ok":
            print(f"  -> {summary}")
            files_ok += 1
        elif status == "unchanged":
            print("  -> 无变化（全部已应用）")
            files_ok += 1
        else:
            print(f"  -> [FAIL] {summary}")
            files_fail += 1
        print()

    print("=" * 64)
    print(f"  汇总: 文件成功 {files_ok} / 失败 {files_fail}")
    print(f"        patch 应用 {total_patch_ok} / 跳过 {total_patch_skip} / 失败 {total_patch_fail}")
    if not args.dry_run and files_ok > 0:
        print(f"  备份: {os.path.join(root, BACKUP_DIR)}")
    print("=" * 64 + "\n")

    if files_fail > 0:
        print("有文件处理失败，请检查上面 [FAIL] 的锚点定位问题。")
        print("如需回滚已应用的部分：\n  python apply_v201_patch.py --restore")
        sys.exit(1)

    if not args.dry_run and total_patch_ok > 0:
        print("下一步建议：")
        print("  git diff")
        print("  git add -A")
        print('  git commit -m "opt: v2.0.1 通用架构优化(定位解耦/闹钟防抖/日志节流)"')
        print("  git push")
        print("\n推送后 GitHub Actions 会自动构建 APK，去 Actions 页面等绿勾。")
    elif args.dry_run:
        print("当前为 dry-run 模式，未写入任何文件。")

if __name__ == "__main__":
    main()
