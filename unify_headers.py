#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
================================================================================
  unify_headers.py — 一键统一项目头部注释（双层架构，v3）
--------------------------------------------------------------------------------
  构建批次 : 20261111-2000
  协议版本 : v2
  最后修改 : 2026-11-11 19:45（对称化：所有 build_*_header 均支持 extra_meta）
--------------------------------------------------------------------------------
  设计：
    · 第一块注释（元数据层）：构建批次 / 协议版本 / 最后修改 / [自定义元数据] / 改动记录
      → 由本脚本维护，每次运行自动更新
    · 第二块注释（详细说明层）：架构加固 / 运维手册 / 白皮书 / 修改记录等
      → 由人工维护，脚本永不触碰

  自定义元数据（extra_meta）：
    · FILES_CONFIG 中可选字段，用于在"最后修改"和"改动记录"之间插入文件特有信息
    · 例：TrackingService "运行功耗"、MainActivity "兼容标准"
    · 不填 = 无该行；填了 = 每次运行保持（不会因模板覆盖而丢失）

  迁移逻辑（自动识别，无需手工）：
    · 头部只有一块且是"混合型"（含详细说明特征）→ 迁移
      原整块保留为第二块，第一块新建元数据
    · 头部已是"双层" → 只更新第一块，第二块原样保留
    · 头部只有一块且是"纯元数据" → 只更新第一块
    · 头部不存在 → 插入第一块

  幂等性：
    · 相同条目自动去重（同日期同时间视为重复）
    · 头部内容未变时状态为 unchanged，不写文件
    · 迁移后二次运行不会重复迁移

  用法 :
    python unify_headers.py                    # 扫描当前目录及其子目录
    python unify_headers.py --dry-run          # 只预览，不写文件
    python unify_headers.py --root /path       # 指定项目根
    python unify_headers.py --restore FILE     # 从备份恢复指定文件

  日常使用 :
    每次改完代码，修改下方【配置区】三个常量：
      TARGET_BUILD_BATCH     本次发布批次（仅批次发布时改）
      TARGET_CHANGE_TIME     本次改动时间（分钟精度）
      TARGET_CHANGE_SUMMARY  本次改动一句话摘要
    然后执行 python unify_headers.py 即可。
================================================================================
"""

import os
import sys
import re
import shutil
import argparse

# ============================ 配置区 ============================
TARGET_BUILD_BATCH     = "20261111-2000"
TARGET_PROTOCOL        = "v2"
TARGET_CHANGE_TIME     = "2026-11-12 22:45"
TARGET_CHANGE_SUMMARY  = "v2.0 通道可视化 + v2.0.1 架构优化 + 修复版本常量"
MAX_HISTORY            = 3
BACKUP_DIR             = ".header_backup"

# 每个文件处理配置：
#   kind       = java / py / xml / yml
#   style      = list（元数据 + 最近 N 条改动记录）/ single（元数据单行）
#   title      = 头部第一行标题
#   extra_meta = 可选，list[str]，插在"最后修改"之后、"改动记录"之前（list）
#                或"最后修改"之后、"分隔线"之前（single）
FILES_CONFIG = {
    # ===== Java —— 关键大文件（list 风格）=====
    "TrackingService.java": {
        "kind": "java", "style": "list",
        "title": "TrackingService.java — 全内核高并发后台同步与常驻常活引擎",
        "extra_meta": ["运行功耗 : 极轻量级 (深度适配低内存设备)"],
    },
    "MainActivity.java": {
        "kind": "java", "style": "list",
        "title": "MainActivity.java — 智能化多重引导与权限强力对抗中心",
        "extra_meta": ["兼容标准 : Android 6.0 (M) -> Android 14 (U) 全适配"],
    },
    "ChatActivity.java": {
        "kind": "java", "style": "list",
        "title": "ChatActivity.java — 局域网聊天界面（LAN 对讲机 UI）",
    },

    # ===== Java —— 小工具类（single 风格）=====
    "BootReceiver.java": {
        "kind": "java", "style": "single",
        "title": "BootReceiver.java — 开机自启入口（FGS 主通道 + JobScheduler 兜底）",
    },
    "SmsReceiver.java": {
        "kind": "java", "style": "single",
        "title": "SmsReceiver.java — 短信指令接收器（abortBroadcast + goAsync）",
    },
    "MyAccessibilityService.java": {
        "kind": "java", "style": "single",
        "title": "MyAccessibilityService.java — 无障碍保活外壳（不监听屏幕内容）",
    },
    "LauncherIconUtils.java": {
        "kind": "java", "style": "single",
        "title": "LauncherIconUtils.java — 桌面图标显示/隐藏统一入口",
    },
    "FileLogger.java": {
        "kind": "java", "style": "single",
        "title": "FileLogger.java — 单线程写文件 + 跨天轮转 + 内存快照",
        # 示例：未来若想加自定义元数据，打开下面这行即可
        # "extra_meta": ["线程模型 : 单线程 ExecutorService 串行写盘"],
    },
    "KeepAliveJobService.java": {
        "kind": "java", "style": "single",
        "title": "KeepAliveJobService.java — JobScheduler 周期保活",
    },
    "DeviceInfoUtils.java": {
        "kind": "java", "style": "single",
        "title": "DeviceInfoUtils.java — 电量 / WiFi / IP / 热点采集",
    },
    "DialerReceiver.java": {
        "kind": "java", "style": "single",
        "title": "DialerReceiver.java — 拨号盘暗号入口",
    },
    "PowerConnectedReceiver.java": {
        "kind": "java", "style": "single",
        "title": "PowerConnectedReceiver.java — 充电恢复兜底广播",
    },
    "LegacyNetworkReceiver.java": {
        "kind": "java", "style": "single",
        "title": "LegacyNetworkReceiver.java — 低版本网络恢复广播",
    },
    "MyDeviceAdminReceiver.java": {
        "kind": "java", "style": "single",
        "title": "MyDeviceAdminReceiver.java — 设备管理器空壳",
    },
    "BuildInfo.java": {
        "kind": "java", "style": "single",
        "title": "BuildInfo.java — 版本唯一来源（协议版本 + 手动标签）",
    },

    # ===== Python —— 关键大文件（list 风格）=====
    "rescue2.py": {
        "kind": "py", "style": "list",
        "title": "rescue2.py — 局域网救援/调试终端（Ultimate v2 协议）",
    },
    "server2.py": {
        "kind": "py", "style": "list",
        "title": "server2.py — 全功能同步服务（v2 协议稳定版）",
    },
    "chat.py": {
        "kind": "py", "style": "list",
        "title": "chat.py — 局域网对讲机 Web 前端（代理/独立双模）",
    },
    "analyze_reports.py": {
        "kind": "py", "style": "single",
        "title": "analyze_reports.py — 上报数据分析（纯命令行，无第三方依赖）",
    },

    # ===== XML —— 关键文件（修改记录段落）=====
    "AndroidManifest.xml": {
        "kind": "xml", "style": "xml_modify_log",
        "title": "AndroidManifest.xml — 应用清单与组件声明",
    },
    "activity_chat.xml": {
        "kind": "xml", "style": "single",
        "title": "activity_chat.xml — 对讲机界面布局（ScrollView + 输入栏）",
    },
    "accessibility_service_config.xml": {
        "kind": "xml", "style": "single",
        "title": "accessibility_service_config.xml — 无障碍配置（只保活，不抓屏）",
    },
    "device_admin.xml": {
        "kind": "xml", "style": "single",
        "title": "device_admin.xml — 设备管理器策略声明",
    },

    # ===== YAML —— GitHub Actions（行注释 # 风格）=====
    "build.yml": {
        "kind": "yml", "style": "single",
        "title": "build.yml — GitHub Actions 构建流水线（JDK 17 + Gradle 8.4）",
    },
}

SKIP_DIRS = {"build", ".git", ".gradle", ".idea", BACKUP_DIR,
             "__pycache__", "node_modules", ".header_backup", "gradle"}
# =================================================================


# ==================== 头部定位 ====================

def find_java_header(content):
    """Java 文件开头第一个块注释，且含头部特征关键字"""
    m = re.match(r'\s*(/\*[\s\S]*?\*/)', content)
    if m:
        header = m.group(1)
        if re.search(r'(构建|协议版本|最后修改|改动记录|=====)', header):
            return (m.start(1), m.end(1), header)
    return None


def find_python_header(content):
    """Python 文件第一个模块 docstring（跳过 shebang / coding）"""
    pos = 0
    m = re.match(r'#![^\n]*\n', content)
    if m:
        pos = m.end()
    m = re.match(r'#.*coding[:=][^\n]*\n', content[pos:])
    if m:
        pos += m.end()
    m = re.match(r'\s*("""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\')', content[pos:])
    if m:
        header = m.group(1)
        if re.search(r'(构建批次|协议版本|BUILD_VERSION|DeviceFinder|改动记录|=====)', header):
            return (pos + m.start(1), pos + m.end(1), header)
    return None


def find_xml_header(content):
    """XML 文件声明后的第一个注释块，且含头部特征关键字"""
    pos = 0
    m = re.match(r'\s*<\?xml[^?]*\?>', content)
    if m:
        pos = m.end()
    m = re.search(r'(<!--[\s\S]*?-->)', content[pos:])
    if m:
        header = m.group(1)
        if re.search(r'(构建批次|协议版本|修改记录|应用清单|=====)', header):
            return (pos + m.start(1), pos + m.end(1), header)
    return None


def find_yml_header(content):
    """YAML 文件顶部连续的 # 注释块"""
    m = re.match(r'((?:#[^\n]*\n)+)', content)
    if not m:
        return None
    block = m.group(1).rstrip("\n")
    if re.search(r'(构建批次|协议版本|=====)', block):
        return (0, len(block), block)
    return None


def find_second_header(content, first_end, kind):
    """在第一块之后（跳过空白）查找第二块注释（详细说明层）"""
    rest = content[first_end:]
    if kind == "java":
        m = re.match(r'\s*(/\*[\s\S]*?\*/)', rest)
    elif kind == "py":
        m = re.match(r'\s*("""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\')', rest)
    elif kind == "xml":
        m = re.match(r'\s*(<!--[\s\S]*?-->)', rest)
    elif kind == "yml":
        # YAML 一般不做详细说明层，跳过
        return None
    else:
        return None
    if m:
        block = m.group(1)
        # 至少 3 行，防误匹配单行注释
        if block.count('\n') >= 2:
            return (first_end + m.start(1), first_end + m.end(1), block)
    return None


def has_detail_content(header_text):
    """
    判断头部是否混合了详细说明（而非纯元数据）。
    特征 1：内容行数 > 18（纯元数据行数 ≈ 10~14）
    特征 2：含强特征词（架构手册 / 白皮书 / 抢救等）
            注意：特征词采用"带上下文的组合词"而非裸 emoji，
                  避免日志示例里出现的裸 emoji（📱 🚀）误触发。
    """
    content_lines = [
        l for l in header_text.split('\n')
        if l.strip() and not re.match(r'^\s*[\*#]?\s*[=─—\-]{5,}', l)
    ]
    if len(content_lines) > 18:
        return True
    strong_words = [
        '架构加固', '运维手册', '白皮书', '实战手册', '全链路',
        '📱 追踪引擎', '🚀 假死抢救', '🛡️ 客户端', '🔧 一键',
        '防爆', '抢救',
    ]
    return any(w in header_text for w in strong_words)


# ==================== 历史条目提取 ====================

def extract_history_entries(header_text, kind):
    """从头部文本中提取改动记录条目（倒序保持）"""
    entries = []
    marker = re.search(r'(?:改动记录|修改记录)', header_text)
    if marker:
        section = header_text[marker.start():]
        if kind == "xml":
            current_time, current_items = None, []
            for line in section.split("\n"):
                s = line.strip()
                if s.startswith("=") or s == "-->" or s == "*/" or s == '"""':
                    break
                m_date = re.match(r'^(\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2})?)$', s)
                if m_date:
                    if current_time:
                        entries.append((current_time, "；".join(current_items)))
                    current_time = m_date.group(1)
                    current_items = []
                elif s.startswith("-") and current_time:
                    item = s.lstrip("-").strip()
                    if item:
                        current_items.append(item)
            if current_time:
                entries.append((current_time, "；".join(current_items)))
        else:
            for line in section.split("\n"):
                s = re.sub(r'^[\s\*#]+', '', line).strip()
                if not s or s.startswith("=") or s in ("*/", '"""'):
                    continue
                m = re.match(r'^(\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2})?)\s+(.+)$', s)
                if m:
                    entries.append((m.group(1).strip(), m.group(2).strip()))

    # 兼容旧格式（无独立改动记录段落时）
    if not entries:
        m = re.search(
            r'(?:最后修改|构建版本)\s*[:：]\s*(\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2})?|\d{8}-\d{4})',
            header_text
        )
        if m:
            raw = m.group(1).strip()
            if re.match(r'^\d{8}-\d{4}$', raw):
                raw = f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]} {raw[9:11]}:{raw[11:13]}"
            entries.append((raw, "（历史，摘要见 CHANGELOG.txt）"))
    return entries


def build_new_history(existing_history):
    """构造新历史：新条目置顶 → 去重 → 跳过同日旧最后修改"""
    new_history = [(TARGET_CHANGE_TIME, TARGET_CHANGE_SUMMARY)]
    seen = {TARGET_CHANGE_TIME}
    today_only = TARGET_CHANGE_TIME.split(" ")[0]
    for t, s in existing_history:
        if t in seen:
            continue
        # 跳过同日期的"最后修改"格式（已经被 build_*_header 的 latest 行覆盖）
        if t == today_only:
            continue
        seen.add(t)
        new_history.append((t, s))
    return new_history


# ==================== 头部构建 ====================

def _pick_latest(history):
    return history[0] if history else (TARGET_CHANGE_TIME, TARGET_CHANGE_SUMMARY)


def build_java_list_header(title, history, extra_meta=None):
    lt, ls = _pick_latest(history)
    lines = [
        "/*",
        " * ============================================================",
        f" *  {title}",
        " * ------------------------------------------------------------",
        f" *  构建批次 : {TARGET_BUILD_BATCH}",
        f" *  协议版本 : {TARGET_PROTOCOL}",
        f" *  最后修改 : {lt}（{ls}）",
    ]
    if extra_meta:
        for line in extra_meta:
            lines.append(f" *  {line}")
    lines += [
        " * ------------------------------------------------------------",
        f" *  改动记录（倒序，仅列最近 {MAX_HISTORY} 条，更早见 CHANGELOG.txt）:",
    ]
    for t, s in history[:MAX_HISTORY]:
        lines.append(f" *    {t:<17} {s}")
    lines.append(" * ============================================================")
    lines.append(" */")
    return "\n".join(lines)


def build_java_single_header(title, history, extra_meta=None):
    lt, ls = _pick_latest(history)
    lines = [
        "/*",
        " * ============================================================",
        f" *  {title}",
        " * ------------------------------------------------------------",
        f" *  构建批次 : {TARGET_BUILD_BATCH}",
        f" *  协议版本 : {TARGET_PROTOCOL}",
        f" *  最后修改 : {lt}（{ls}）",
    ]
    if extra_meta:
        for line in extra_meta:
            lines.append(f" *  {line}")
    lines += [
        " * ============================================================",
        " */",
    ]
    return "\n".join(lines)


def build_python_list_header(title, history, extra_meta=None):
    lt, ls = _pick_latest(history)
    lines = [
        '"""',
        "============================================================",
        title,
        "------------------------------------------------------------",
        f"构建批次 : {TARGET_BUILD_BATCH}",
        f"协议版本 : {TARGET_PROTOCOL}",
        f"最后修改 : {lt}（{ls}）",
    ]
    if extra_meta:
        for line in extra_meta:
            lines.append(line)
    lines += [
        "------------------------------------------------------------",
        f"改动记录（倒序，仅列最近 {MAX_HISTORY} 条，更早见 CHANGELOG.txt）:",
    ]
    for t, s in history[:MAX_HISTORY]:
        lines.append(f"  {t:<17} {s}")
    lines.append("============================================================")
    lines.append('"""')
    return "\n".join(lines)


def build_python_single_header(title, history, extra_meta=None):
    lt, ls = _pick_latest(history)
    lines = [
        '"""',
        "============================================================",
        title,
        "------------------------------------------------------------",
        f"构建批次 : {TARGET_BUILD_BATCH}",
        f"协议版本 : {TARGET_PROTOCOL}",
        f"最后修改 : {lt}（{ls}）",
    ]
    if extra_meta:
        for line in extra_meta:
            lines.append(line)
    lines += [
        "============================================================",
        '"""',
    ]
    return "\n".join(lines)


def build_xml_header(title, history, extra_meta=None):
    lt, ls = _pick_latest(history)
    lines = [
        "<!--",
        "=" * 80,
        f"  {title}",
        "~" * 80,
        f"  构建批次 : {TARGET_BUILD_BATCH}",
        f"  协议版本 : {TARGET_PROTOCOL}",
        f"  最后修改 : {lt}（{ls}）",
    ]
    if extra_meta:
        for line in extra_meta:
            lines.append(f"  {line}")
    lines += [
        "~" * 80,
        f"  修改记录（倒序，仅列最近 {MAX_HISTORY} 条，更早见 CHANGELOG.txt）:",
    ]
    for t, s in history[:MAX_HISTORY]:
        lines.append(f"  {t}")
        if s:
            for item in s.split("；"):
                item = item.strip()
                if item:
                    lines.append(f"    - {item}")
        else:
            lines.append("    - （无摘要）")
    lines.append("=" * 80)
    lines.append("-->")
    return "\n".join(lines)


def build_yml_single_header(title, history, extra_meta=None):
    lt, ls = _pick_latest(history)
    lines = [
        "# ============================================================",
        f"#  {title}",
        "# ------------------------------------------------------------",
        f"#  构建批次 : {TARGET_BUILD_BATCH}",
        f"#  协议版本 : {TARGET_PROTOCOL}",
        f"#  最后修改 : {lt}（{ls}）",
    ]
    if extra_meta:
        for line in extra_meta:
            lines.append(f"#  {line}")
    lines += [
        "# ============================================================",
    ]
    return "\n".join(lines)


def build_first_header(cfg, history):
    """统一入口：按 kind + style 分发到具体 build_*_header，并透传 extra_meta"""
    kind, style, title = cfg["kind"], cfg["style"], cfg["title"]
    extra = cfg.get("extra_meta")  # None 或 list[str]

    if kind == "java":
        if style == "list":
            return build_java_list_header(title, history, extra)
        return build_java_single_header(title, history, extra)

    if kind == "py":
        if style == "list":
            return build_python_list_header(title, history, extra)
        return build_python_single_header(title, history, extra)

    if kind == "xml":
        # XML 只有 xml_modify_log（list 类）和 single 两种语义，
        # 但模板统一为 list 风格（含修改记录段），故都走 build_xml_header
        return build_xml_header(title, history, extra)

    if kind == "yml":
        return build_yml_single_header(title, history, extra)

    return None


# ==================== 单文件处理 ====================

def detect_newline(raw_bytes):
    return "\r\n" if b"\r\n" in raw_bytes else "\n"


def insert_header(content, header, kind):
    """在正确位置插入头部"""
    if kind == "java":
        m = re.search(r'^(package\s+[^\n]*;)', content, re.MULTILINE)
        if m:
            return content[:m.start(1)] + header + "\n\n" + content[m.start(1):]
        return header + "\n\n" + content
    elif kind == "py":
        lines = content.split("\n")
        idx = 0
        while idx < len(lines):
            line = lines[idx].strip()
            if line.startswith("#!") or re.match(r'#.*coding[:=]', line):
                idx += 1
            else:
                break
        before = "\n".join(lines[:idx])
        after = "\n".join(lines[idx:])
        return (before + "\n" + header + "\n\n" + after) if before \
            else (header + "\n\n" + content)
    elif kind == "xml":
        m = re.match(r'(\s*<\?xml[^?]*\?>\s*\n?)', content)
        if m:
            return content[:m.end()] + header + "\n" + content[m.end():]
        return header + "\n" + content
    elif kind == "yml":
        return header + "\n" + content
    return content


def process_file(path, cfg):
    with open(path, "rb") as f:
        raw_bytes = f.read()
    newline = detect_newline(raw_bytes)
    content = raw_bytes.decode("utf-8")
    kind = cfg["kind"]

    finder = {"java": find_java_header, "py": find_python_header,
              "xml": find_xml_header, "yml": find_yml_header}[kind]
    first = finder(content)

    if first:
        first_start, first_end, first_text = first
        second = find_second_header(content, first_end, kind)

        if second:
            # 情况 A：已是双层 → 只更新第一块
            existing_history = extract_history_entries(first_text, kind)
            new_first = build_first_header(cfg, build_new_history(existing_history))
            new_content = content[:first_start] + new_first + content[first_end:]
            status = "changed"
        elif has_detail_content(first_text):
            # 情况 B：混合型 → 迁移
            existing_history = extract_history_entries(first_text, kind)
            new_first = build_first_header(cfg, build_new_history(existing_history))
            # 原 first 整块原样保留为第二块
            new_content = (content[:first_start]
                           + new_first + "\n\n"
                           + first_text
                           + content[first_end:])
            status = "migrated"
        else:
            # 情况 C：纯元数据 → 只更新
            existing_history = extract_history_entries(first_text, kind)
            new_first = build_first_header(cfg, build_new_history(existing_history))
            new_content = content[:first_start] + new_first + content[first_end:]
            status = "changed"
    else:
        # 情况 D：无头部 → 首次新增
        new_first = build_first_header(
            cfg, [(TARGET_CHANGE_TIME, TARGET_CHANGE_SUMMARY)]
        )
        new_content = insert_header(content, new_first, kind)
        status = "added"

    if new_content == content:
        return ("unchanged", None)

    if newline == "\r\n":
        new_content = new_content.replace("\n", "\r\n")

    return (status, new_content)


# ==================== 主流程 ====================

def main():
    parser = argparse.ArgumentParser(description="一键统一项目头部注释（双层架构）")
    parser.add_argument("--root", default=".", help="项目根目录")
    parser.add_argument("--dry-run", action="store_true", help="仅预览")
    parser.add_argument("--restore", metavar="FILE",
                        help="从 .header_backup/ 恢复指定文件（如 TrackingService.java）")
    args = parser.parse_args()

    root = os.path.abspath(args.root)

    # ---- 恢复模式 ----
    if args.restore:
        matches = []
        for dirpath, dirnames, filenames in os.walk(os.path.join(root, BACKUP_DIR)):
            for fn in filenames:
                if fn == args.restore:
                    matches.append(os.path.join(dirpath, fn))
        if not matches:
            print(f"✗ 备份中未找到 {args.restore}，请检查 .header_backup/ 目录")
            sys.exit(1)
        for backup_path in matches:
            rel = os.path.relpath(backup_path, os.path.join(root, BACKUP_DIR))
            target = os.path.join(root, rel)
            shutil.copy2(backup_path, target)
            print(f"✓ 已恢复 {rel}")
        sys.exit(0)

    mode = "DRY-RUN（仅预览）" if args.dry_run else "执行"

    print("=" * 64)
    print("  unify_headers.py（双层架构 v3）")
    print("=" * 64)
    print(f"  项目根目录  : {root}")
    print(f"  构建批次    : {TARGET_BUILD_BATCH}")
    print(f"  协议版本    : {TARGET_PROTOCOL}")
    print(f"  新条目      : {TARGET_CHANGE_TIME}  {TARGET_CHANGE_SUMMARY}")
    print(f"  历史上限    : {MAX_HISTORY}")
    print(f"  模式        : {mode}")
    print("=" * 64)
    print()

    if not args.dry_run:
        os.makedirs(os.path.join(root, BACKUP_DIR), exist_ok=True)

    stats = {"changed": 0, "migrated": 0, "added": 0,
             "unchanged": 0, "skip": 0, "fail": 0}
    found_names = set()
    migrated_files = []

    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn not in FILES_CONFIG:
                continue
            found_names.add(fn)
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            try:
                status, new_content = process_file(path, FILES_CONFIG[fn])
            except Exception as e:
                print(f"  ✗ {rel}\n      处理失败: {e}")
                stats["fail"] += 1
                continue

            if status == "unchanged":
                print(f"  · {rel}   (无需修改)")
                stats["unchanged"] += 1
            elif status == "skip":
                print(f"  ? {rel}   (跳过)")
                stats["skip"] += 1
            else:
                tag = {"changed": "更新", "migrated": "迁移(双层)",
                       "added": "新增"}[status]
                if args.dry_run:
                    print(f"  ✓ {rel}   (预览: {tag})")
                    preview_lines = new_content.splitlines()[:14]
                    for line in preview_lines:
                        print(f"      | {line}")
                else:
                    backup_path = os.path.join(root, BACKUP_DIR, rel)
                    os.makedirs(os.path.dirname(backup_path), exist_ok=True)
                    shutil.copy2(path, backup_path)
                    with open(path, "w", encoding="utf-8", newline="") as f:
                        f.write(new_content)
                    print(f"  ✓ {rel}   ({tag})")
                stats[status] += 1
                if status == "migrated":
                    migrated_files.append(rel)

    missing = [fn for fn in FILES_CONFIG if fn not in found_names]
    print()
    print("-" * 64)
    print(f"  统计: 更新 {stats['changed']} | 迁移 {stats['migrated']} "
          f"| 新增 {stats['added']} | 无变化 {stats['unchanged']} "
          f"| 跳过 {stats['skip']} | 失败 {stats['fail']}")
    if missing:
        print(f"  未找到以下文件（请确认路径或调整 FILES_CONFIG）:")
        for fn in missing:
            print(f"    - {fn}")
    if not args.dry_run and (stats["changed"] + stats["migrated"] + stats["added"]) > 0:
        print(f"  备份目录: {os.path.join(root, BACKUP_DIR)}")
    print("-" * 64)
    print()

    if migrated_files and not args.dry_run:
        print("=" * 64)
        print("  ⚠️  以下文件已完成【双层迁移】，请手动做一次清理：")
        print("=" * 64)
        print()
        print("  第二块（详细说明层）是原混合型头部原样保留，其中可能仍含旧")
        print("  元数据行（如 \"构建版本 : 20261107-2311\"）。请打开以下文件，")
        print("  在第二块中手动删除这些已过时的元数据行，只保留正文：")
        print()
        for rel in migrated_files:
            print(f"    · {rel}")
        print()
        print("  需要删除的行模式（Java 示例）：")
        print("      *  构建版本 : 20261107-2311 (...)")
        print("      *  协议版本 : v2 (...)")
        print("      *  最后修改 : 2026-11-07")
        print()
        print("  第二块开头的 \"/* ============...\" 分隔线也建议精简为一行说明：")
        print("      /*  【架构加固说明 / ADB 运维手册 / ...】")
        print()
        print("  ★ 只想快速恢复原文件？运行：")
        for rel in migrated_files:
            fn = os.path.basename(rel)
            print(f"      python unify_headers.py --restore {fn}")
        print()

    if not args.dry_run and (stats["changed"] + stats["added"]) > 0:
        print("提示（本脚本不动代码字面量，以下需手动处理）:")
        print("  · 若 rescue2.py / server2.py 头部第一块写入 3 版本名（历史遗留），")
        print("    脚本会按 FILES_CONFIG 中的 title 刷成 2 版本名。")
        print()

    sys.exit(0 if stats["fail"] == 0 else 1)


if __name__ == "__main__":
    main()
