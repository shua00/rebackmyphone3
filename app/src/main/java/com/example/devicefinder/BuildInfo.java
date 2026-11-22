/*
 * ============================================================
 *  BuildInfo.java — 版本唯一来源（协议版本 + 手动标签）
 * ------------------------------------------------------------
 *  构建批次 : 20261122-ANR-UI
 *  协议版本 : v2
 *  最后修改 : 2026-11-22（启动主线程与网络回调阻塞修复）
 * ============================================================
 */

package com.example.devicefinder;

public final class BuildInfo {
    /** 协议版本：机器读。v1=旧 FIND_ME_MSG，v2=FLOAT/CHAT 新前缀 */
    public static final int PROTOCOL_VERSION = 2;

    /** 
     * ★【手动编号/标签】：
     * v2.0.1 通用架构优化批次：通道可视化 + 闹钟防抖 + 定位解耦
     */
    private static final String CUSTOM_TAG = "-V2520";

    public static final String BUILD_VERSION = BuildConfig.AUTO_BUILD_VERSION + CUSTOM_TAG;

    private BuildInfo() {}
}
