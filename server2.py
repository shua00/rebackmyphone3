"""
============================================================
server2.py — 全功能同步服务（v2 协议稳定版）
------------------------------------------------------------
构建批次 : 20261117-1000
协议版本 : v2
最后修改 : 2026-11-18 23:00（v2.2 远程陪伴面板折叠化，与对讲机交互统一，上报未收集全）
------------------------------------------------------------
改动记录（倒序，仅列最近 3 条，更早见 CHANGELOG.txt）:
  2026-11-17 10:00  v2.2 远程陪伴面板改为按钮折叠式，与「局域网对讲机」交互完全一致，首屏更清爽
  2026-11-13 10:30  v2.1 媒体上传动态后缀 + 远程陪伴 Web 面板 + 图/视频自动识别推送
  2026-11-12 22:45  v2.0 通道可视化 + v2.0.1 架构优化 + 修复版本常量
============================================================
"""

import socket
import json
import datetime
import threading
import time
import sqlite3
import gzip
import os
import re
import html as html_lib  # ★ 2026-11-07 [P4.7 XSS]
import faulthandler
import signal
from urllib.parse import urlparse, parse_qs
# ★ 新增ThreadPoolExecutor 补丁： 2026-11-05 13:50
from concurrent.futures import ThreadPoolExecutor

"""
============================================================
DeviceFinder server2.py — 全功能同步服务（终极稳健版）
============================================================
版本发布 : 2026-11-18
核心架构 : 原生 Socket + 多线程 (ThreadPoolExecutor)
============================================================

【一、核心功能概览】
- reports 表完整持久化（含扩展字段 ext_json、SIM 状态等）
- HTTP 下行队列（pending_commands 双通道下发）
- UDP 监听 + 聊天室去重
- 离线设备管理（单设备删除、批量清理 >2h 设备）
- 地图三图层（天地图无偏移 + 高德自动坐标转换 + 谷歌国际版备用）
- 地图容器延迟初始化（解决手机端白屏）
- 全前端 JS 已 ES5 化，完美兼容所有浏览器 / 微信 / WebView
- ★ v2.1 远程陪伴：Web 面板一键推图/推视频，自动识别 MIME 类型
- ★ v2.2 远程陪伴面板改为按钮折叠式，与「局域网对讲机」交互统一

【二、历史安全 / 健壮性修复】
- 修复 .get("key", {}).get() 链式调用遇到 null 崩溃的问题，统一改用 (get() or {}).get()
- 修复 has_internet 等字段为 0 时被误判为 None 的 Bug，新增严格 _parse_bool_val 解析
- GET / 增加 try-except 保护，防止个别设备脏数据导致全页面白屏
- 修复 Python 字符串与 JS 换行符转义冲突导致的 SyntaxError

【三、2026-11-06 假死抢救与极限加固】
1. 独立健康检查端口 8081
   - 不受 8080 业务线程池影响。即便 8080 被并发压满，8081 也能秒回进程状态。
2. 有界队列 + 503 限流
   - 业务线程池外挂 BoundedSemaphore(50)。超过 50 个请求排队时直接返回 503，彻底斩断“连接无限制挂起 -> CLOSE-WAIT 堆积 -> fd 耗尽”的雪崩链。
3. UDP 8888 绑定重试
   - 若 8888 被 rescue2.py 占用，旧版会 return 导致对讲机永久失聪。新版改为 10 秒死循环重试，直到成功抢占。
4. faulthandler 信号抓栈
   - 注册了 SIGUSR1 信号，用于诊断“进程活着但业务不响应”的假死情况，替代了极度危险的 gdb 操作。
5. 内存全状态原子锁 (state_lock 升级为 RLock)
   - 全面覆盖对讲机（收发）、失联检测、Web 控制台渲染。彻底杜绝 “RuntimeError: list iterator mutated during traversal” 崩溃与死锁。
6. 字节流无损架构与 SQLite 防泄漏
   - download_log 规范化单行字节流拼接；所有 SQLite 边缘分支套上 try...finally 强力自愈闭环，确保极端报错下 fd 100% 释放。
7. 纵深防御沙箱 (XSS 切断)
   - 对云端留言 (showServerMessages) 与日志预览 (showCloudLogs) 引入 escapeHtml 沙箱净化，斩断所有恶意报文注入通道。

【四、2026-11-13 v2.1 远程陪伴媒体推送】
- POST /api/upload_media 改为动态识别后缀（.jpg/.jpeg/.png/.gif/.mp4/.webm），修复旧版写死 .jpg 导致视频 MIME 错乱、手机端无法播放的 Bug
- Web 面板新增「👨‍👩‍👧 远程陪伴」蓝色控制区：开启/关闭陪伴模式、选择本地文件、一键推送到电视/手机
- 前端 uploadCompanionMedia() 依据 file.type 自动判断走 push_photo 还是 push_video，无需人工选择
- 静态媒体服务 GET /uploads/media/ 依据后缀返回正确 Content-Type（image/jpeg、image/png、image/gif、video/mp4、video/webm）

【五、2026-11-17 v2.2 远程陪伴面板折叠化】
- 原常驻蓝色面板改为「按钮触发 → 独立容器展开/收起」模式，与「🗣️ 局域网对讲机」交互完全一致
- 首屏占用从 ~200px 降为 0，设备状态表直接上移，符合“监控优先”的使用习惯
- 按钮使用蓝色描边强调色（与对讲机同款），保证发现性
- uploadCompanionMedia() 推送成功后自动展开面板（若当前为折叠态），避免家属错失反馈
- 未改动 HTML 结构内的 <details> 高级设置（保留原生无障碍语义，视觉已足够区分）

============================================================
【六、🚀 日常运维与排错实战手册】
============================================================

▶ 1. 服务生死管理 (Systemd 接管)
  - 启动/重启服务 : sudo systemctl restart devicefinder
  - 查看服务状态 : sudo systemctl status devicefinder
  - 开机自启管理 : sudo systemctl enable devicefinder
  - 查看实时日志 : journalctl -u devicefinder -f -n 100

▶ 2. 探活检测 (区分进程活与业务活)
  - 独立探活 (最准，必秒回) : curl -s http://127.0.0.1:8081/health
  - 业务探活 (可能排队)    : curl -s http://127.0.0.1:8080/health

▶ 3. 假死现场抓取 (终极必杀技)
  - 命令: kill -USR1 $(pgrep -f 'server2.py')
  - 随后查看日志: tail -100 /root/server2.log  (或对应的日志重定向文件)
  - 说明: 日志中会直接打印所有活动的 Python 线程调用栈，精准定位卡在哪一行。

▶ 4. 网络连接与端口排查
  - 查看端口占用: ss -tulnp | grep -E ':8080|:8081|:8888'
  - 检查死连接  : ss -tnp | grep ':8080' | awk '{print $1}' | sort | uniq -c
    (若 CLOSE-WAIT 数量持续 > 50 且不减少，说明客户端异常断开但 fd 未及时释放)

▶ 5. 数据库紧急维护 (Python 一行流替代 sqlite3 命令行，注意对齐实际路径)
  - 查询设备数 : python3 -c "import sqlite3; print(sqlite3.connect('track_history.db').execute('SELECT COUNT(DISTINCT device_id) FROM reports').fetchone()[0])"
  - 清理并压缩: python3 -c "import sqlite3; c=sqlite3.connect('track_history.db'); c.execute(\"DELETE FROM reports WHERE device_id LIKE 'heavy_%'\"); c.commit(); c.execute('VACUUM'); print('VACUUM Done')"

▶ 6. 远程陪伴媒体推送排错（v2.1 新增）
  - 确认上传目录存在 : ls -lh uploads/media/
  - 确认静态访问可达 : curl -I http://127.0.0.1:8080/uploads/media/<文件名>
  - 若视频不播放     : 检查手机端是否支持该编码（建议 H.264/AAC 的 .mp4）
  - 若图片不显示     : 检查浏览器控制台，确认返回 Content-Type 是否与后缀一致

▶ 7. 远程陪伴面板入口（v2.2 变更）
  - 原常驻面板已改为按钮折叠式，入口在「🗣️ 局域网对讲机」右侧的「👨‍👩‍👧 远程陪伴」按钮
  - 若家属反馈“找不到”，确认按钮是否因浏览器缓存显示旧版，强制刷新（Ctrl+F5）即可

============================================================
"""


# ===== 上报数据保留策略 =====
REPORTS_KEEP_RAW_DAYS = 30        # raw_json 保留天数
REPORTS_KEEP_NEARBY_DAYS = 90     # nearby_wifi_json 保留天数
REPORTS_RAW_MAX_BYTES = 8192      # raw_json 单条最大字节
REPORTS_EXT_MAX_BYTES = 4096      # ext_json 单条最大字节

# ===== 清理策略 =====
CLEAN_OFFLINE_THRESHOLD_MS = 2 * 3600 * 1000    # "清理全部离线设备"阈值：2 小时
MANAGE_OFFLINE_THRESHOLD_S = 30 * 60            # "管理离线设备"弹窗展示阈值：30 分钟

PORT = 8080
# ★ FIX-1 2026-11-18 [安全 P0]：口令改为支持环境变量注入。
#   原值 your_private_secret_key 直接写在源码里，等于公开：任何拿到本文件的人
#   都能伪造 /api/report 上报、伪造带 RESCUE_SECRET 的 UDP 信令。
AUTH_TOKEN = os.environ.get("DF_AUTH_TOKEN") or "your_private_secret_key"
RESCUE_SECRET = os.environ.get("DF_RESCUE_SECRET") or AUTH_TOKEN
# 控制面（/api/control）鉴权开关，默认开启。
# 现场若有大量不带 token 的旧前端/脚本，可设 DF_CONTROL_AUTH=0 临时过渡（务必同时改口令）。
CONTROL_AUTH_ENABLED = (os.environ.get("DF_CONTROL_AUTH", "1") != "0")
USING_DEFAULT_CREDENTIALS = (AUTH_TOKEN == "your_private_secret_key")

# ===== 版本 =====
BUILD_VERSION = "20261117-1000"
PROTOCOL_VERSION = 2

GLOBAL_DEFAULT_CONFIG = {
    "report_interval_seconds": 1800,
    "accuracy_mode": "coarse",
    "next_server_url": "",
    "next_config_url": "",
    "hide_icon": True
}

ALL_DEVICES_STATUS = {}
CHAT_MESSAGES = []
print_lock = threading.Lock()
state_lock = threading.RLock()  # ★ 升级：RLock 防止意外嵌套导致死锁

# ★★★ 新增：数据库写锁，防止高并发写入时 SQLite 锁死 2026-11-05 14:30★★★
db_write_lock = threading.Lock()

LAST_MSG_INFO = {}
LAST_MSG_INFO_MAX = 100
LAST_MSG_INFO_TTL = 10.0

UPLOAD_LOG_DIR = "uploads/logs"
os.makedirs(UPLOAD_LOG_DIR, exist_ok=True)

# ★ v2.1 新增：媒体上传目录（图片/视频）
UPLOAD_MEDIA_DIR = "uploads/media"
os.makedirs(UPLOAD_MEDIA_DIR, exist_ok=True)

# ===== HTTP 下行指令队列 =====
PENDING_COMMANDS = {}
CMD_TTL_SECONDS = 86400
# ★ 2026-11-18 补丁A：展示类指令 TTL 由 86400 压到 600
#   原因：客户端改成"未渲染成功不 ack"（CompanionModule/TrackingService 补丁）后，失败指令会留在队列反复重投。
#   展示类（图片/视频/音频/网页/陪伴开关）过期即失去意义，留 24h 会让老人机一天内被同一张图反复弹屏。
# ★ 2026-11-18 [A档修正] 原补丁A 写的是 600，在「不换 TrackingService」的组合下会直接丢指令，故上调：
#   依据：本文件 147 行 report_interval_seconds 默认 1800，且 device_log.txt 155/188 行实证
#   「距上次成功上报 30 分钟，强制触发一次」→ 手机最长 1800s 才取一次队列。
#   原版 TrackingService 只在上报时取 pending 指令，600s < 1800s ⇒ push_photo 还没被取走就过期删除，
#   服务端日志只有「📋 [HTTP队列] +push_photo」再无任何后续，表现即「推了永远不显示」。
#   取 7200 = 覆盖 2 个完整上报周期。等将来上了「未渲染成功不 ack」的客户端(C档)，
#   失败指令会被主动重投，届时可把它调回 600~1800 以抑制反复弹屏。
DISPLAY_CMD_TTL_SECONDS = 7200
DISPLAY_ACTIONS = {"push_photo", "push_video", "push_audio", "push_web",
                   "companion_on", "companion_off"}

def cmd_ttl_for(action):
    """按指令类型返回 TTL：展示类 600s，其余（上报/告警/留言/配置）维持 86400s"""
    return DISPLAY_CMD_TTL_SECONDS if (action in DISPLAY_ACTIONS) else CMD_TTL_SECONDS

# ★ 2026-11-18 补丁B：UDP 重发包数预算（原先 all 全推一次最多打出 27 份重复信令，日志已实证）
UDP_ROUNDS_BROADCAST = 2      # 广播/子网广播 每目标重发轮次（原 3）
UDP_ROUNDS_UNICAST   = 1      # 单播 每目标重发轮次（原 2）
UDP_TOTAL_PACKET_BUDGET = 4   # 单次 smart_send 总包数上限，超过则退化为一次广播
CMD_MAX_PER_DEVICE = 50
CMD_SEQ = 0
_cmd_lock = threading.Lock()

HTTP_SUPPORTED_ACTIONS = {
    "force_report", "play_alarm", "custom_msg", "chat_msg",
    "change_server", "change_config", "clear_history", "clear_chat",
    "lock_chat", "unlock_chat", "set_sms_num", "set_cloud_log",
    "upload_log", "locate_once", "scan_wifi", "restart_udp", "test_low_battery",
    # ★ 2026-11-07 [P1-3/P2]
    "elder_mode",
    "companion_on", "companion_off", "push_photo", "push_video", # ★ 2026-11-13 新增陪伴插件白名单
    "push_audio", "push_web"  # ★ v2.6 2026-11-18 强力挂载新特权
}

# ===== 允许的媒体后缀白名单（v2.1）=====
ALLOWED_MEDIA_EXTS = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.mp4', '.webm', '.mkv',
                     '.mp3', '.aac', '.m4a', '.wav', '.ogg', '.wma', '.html', '.h5'}


# ===== reports 表字段声明 =====
REPORTS_COLUMNS = [
    ("id",                     "INTEGER PRIMARY KEY AUTOINCREMENT"),
    ("device_id",              "TEXT NOT NULL"),
    ("timestamp",              "INTEGER NOT NULL"),
    ("received_at",            "INTEGER NOT NULL"),
    ("day_str",                "TEXT"),
    ("hour",                   "INTEGER"),
    ("weekday",                "INTEGER"),
    ("battery_level",          "INTEGER"),
    ("is_charging",            "INTEGER"),
    ("battery_temp_c",         "REAL"),
    ("battery_voltage_mv",     "INTEGER"),
    ("wifi_ssid",              "TEXT"),
    ("wifi_bssid",             "TEXT"),
    ("wifi_rssi",              "INTEGER"),
    ("local_ip",               "TEXT"),
    ("source_ip",              "TEXT"),
    ("network_type",           "TEXT"),
    ("network_subtype",        "TEXT"),
    ("has_internet",           "INTEGER"),
    ("sim_state",              "TEXT"),
    ("sim_operator",           "TEXT"),
    ("is_screen_on",           "INTEGER"),
    ("is_screen_locked",       "INTEGER"),
    ("airplane_mode",          "INTEGER"),
    ("ringer_mode",            "TEXT"),
    ("volume_alarm",           "INTEGER"),
    ("unlock_count_since_boot","INTEGER"),
    ("chat_locked",            "INTEGER"),
    ("elder_mode",             "INTEGER"),
    ("os_version",             "TEXT"),
    ("uptime_ms",              "INTEGER"),
    ("lat",                    "REAL"),
    ("lng",                    "REAL"),
    ("accuracy",               "REAL"),
    ("location_provider",      "TEXT"),
    ("location_time",          "INTEGER"),
    ("location_age_s",         "INTEGER"),
    ("recent_locations_json",  "TEXT"),
    ("nearby_wifi_count",      "INTEGER"),
    ("nearby_wifi_json",       "TEXT"),
    ("raw_json",               "TEXT"),
    ("ext_json",               "TEXT"),
    ("build_version",          "TEXT"),
    ("protocol_version",       "INTEGER"),
    
    # 🌟 2026-11-18 终极合流：完整补齐远程陪伴插件「全链路全状态」落库物理字段
    ("companion_enabled",      "INTEGER"), # 陪伴模式总开关状态 (1:开启, 0:关闭)
    ("push_photo_url",         "TEXT"),    # 当前全屏挂载的图片 URL 地址
    ("push_video_url",         "TEXT"),    # 当前循环播放的视频/直播源 URL 地址
    ("push_audio_url",         "TEXT"),    # 当前后台硬解的纯音频/网络广播 URL 地址
    ("push_web_url",           "TEXT")     # 当前全屏常驻的网页/H5 看板 URL 地址
]

# ★ 修复：补上缺失的 REPORTS_INDEXES 定义（原代码在 init_db 中遍历它却从未定义，导致启动即 NameError）
REPORTS_INDEXES = [
    ("idx_reports_device_ts", "ON reports (device_id, timestamp)"),
    ("idx_reports_timestamp", "ON reports (timestamp)"),
    ("idx_reports_day_str",   "ON reports (day_str)"),
]

def _parse_bool_val(v):
    """严格解析布尔/整型状态，避免 0 == False 导致 0 被转为 None"""
    if v is None:
        return None
    if v is True or v == 1:
        return 1
    if v is False or v == 0:
        return 0
    return None


def init_db():
    conn = sqlite3.connect('track_history.db', timeout=10)
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS history
                 (device_id TEXT, lat REAL, lng REAL, timestamp INTEGER)''')
    c.execute('''CREATE TABLE IF NOT EXISTS messages
                 (device_id TEXT, msg_time TEXT, message TEXT, timestamp INTEGER)''')
    col_defs = ", ".join(f"{name} {typ}" for name, typ in REPORTS_COLUMNS)
    c.execute(f"CREATE TABLE IF NOT EXISTS reports ({col_defs})")
    try:
        existing = {row[1] for row in c.execute("PRAGMA table_info(reports)").fetchall()}
        for name, typ in REPORTS_COLUMNS:
            if name in existing:
                continue
            if "PRIMARY KEY" in typ.upper() or "NOT NULL" in typ.upper():
                continue
            try:
                c.execute(f"ALTER TABLE reports ADD COLUMN {name} {typ}")
            except Exception as e:
                print(f"⚠️ [reports] 迁移列 {name} 失败: {e}")
    except Exception as e:
        print(f"⚠️ [reports] PRAGMA table_info 失败: {e}")
    for idx_name, idx_def in REPORTS_INDEXES:
        try:
            c.execute(f"CREATE INDEX IF NOT EXISTS {idx_name} {idx_def}")
        except Exception:
            pass
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=10000")
    except Exception:
        pass
    conn.commit()
    conn.close()


if hasattr(signal, 'SIGUSR1') and hasattr(faulthandler, 'register'):
    faulthandler.register(signal.SIGUSR1, all_threads=True)
init_db()


# ==================== save_report ====================

def save_report(dev_id, data, source_ip):
    """完整上报落库。失败不影响 /api/report 的 200 响应。"""
    try:
        received_at = data.get("received_at", 0) or int(datetime.datetime.now().timestamp() * 1000)
        ts = data.get("timestamp", 0) or 0
        if not isinstance(ts, (int, float)) or ts < 1000000000000 or ts > received_at + 86400000:
            ts = received_at
        ts = int(ts)
        received_at = int(received_at)
        dt = datetime.datetime.fromtimestamp(ts / 1000.0)

        battery = data.get("battery") or {}
        batt_level = battery.get("level")
        if not isinstance(batt_level, int):
            try: batt_level = int(batt_level)
            except (TypeError, ValueError): batt_level = None
        is_charging = _parse_bool_val(battery.get("is_charging"))
        batt_temp = battery.get("temp_c")
        if not isinstance(batt_temp, (int, float)): batt_temp = None
        batt_volt = battery.get("voltage_mv")
        if not isinstance(batt_volt, int): batt_volt = None

        network = data.get("network") or {}
        wifi_ssid = (network.get("wifi_ssid") or "").strip()
        wifi_bssid = (network.get("wifi_bssid") or "").strip() or None
        local_ip = (network.get("local_ip") or "").strip() or None
        wifi_rssi = network.get("wifi_rssi")
        if not isinstance(wifi_rssi, int):
            try: wifi_rssi = int(wifi_rssi)
            except (TypeError, ValueError): wifi_rssi = None
        net_subtype = network.get("network_subtype") or None
        has_internet = _parse_bool_val(network.get("has_internet"))

        if wifi_ssid and wifi_ssid not in ("UNKNOWN", "OFF", "<unknown ssid>"):
            net_type = "WiFi"
        elif local_ip and not local_ip.startswith("127.") and local_ip != "0.0.0.0":
            net_type = "MOBILE"
        else:
            net_type = "离线"

        sim_state = data.get("sim_state")
        if not isinstance(sim_state, str): sim_state = None
        sim_operator = data.get("sim_operator")
        if not isinstance(sim_operator, str): sim_operator = None

        ringer_mode = data.get("ringer_mode")
        if not isinstance(ringer_mode, str): ringer_mode = None
        volume_alarm = data.get("volume_alarm")
        if not isinstance(volume_alarm, int): volume_alarm = None
        unlock_cnt = data.get("unlock_count_since_boot")
        if not isinstance(unlock_cnt, int): unlock_cnt = None
        
        is_screen_locked = _parse_bool_val(data.get("is_screen_locked"))
        airplane_mode = _parse_bool_val(data.get("airplane_mode"))

        loc = data.get("location") or {}
        def _num(v):
            if v is None: return None
            try:
                f = float(v)
                if f != f or f in (float('inf'), float('-inf')): return None
                return f
            except (TypeError, ValueError): return None

        lat = _num(loc.get("latitude")); lng = _num(loc.get("longitude"))
        if lat is None or not (-90 <= lat <= 90): lat = None
        if lng is None or not (-180 <= lng <= 180): lng = None
        if lat is None or lng is None: lat = lng = None
        accuracy = _num(loc.get("accuracy"))
        if accuracy is not None and (accuracy < 0 or accuracy > 100000): accuracy = None
        loc_time = loc.get("time")
        if not isinstance(loc_time, int) or loc_time <= 0: loc_time = None
        loc_age_s = loc.get("age_s")
        if not isinstance(loc_age_s, int) or loc_age_s < 0: loc_age_s = None

        recent_locs = data.get("recent_locations")
        recent_locs_json = None
        if isinstance(recent_locs, list) and recent_locs:
            try: recent_locs_json = json.dumps(recent_locs, ensure_ascii=False)[:4000]
            except Exception: pass

        nearby = data.get("nearby_wifi") or []
        if not isinstance(nearby, list): nearby = []
        nearby_json = None
        if nearby:
            try: nearby_json = json.dumps(nearby, ensure_ascii=False)[:8000]
            except Exception: pass

        raw_json = None
        try: raw_json = json.dumps(data, ensure_ascii=False)[:REPORTS_RAW_MAX_BYTES]
        except Exception: pass

        declared_keys = {
            "device_id", "timestamp", "received_at", "battery", "network",
            "location", "nearby_wifi", "recent_locations", "is_screen_on",
            "chat_locked", "elder_mode", "os_version", "uptime_ms",
            "sim_state", "sim_operator", "ringer_mode", "volume_alarm",
            "unlock_count_since_boot", "is_screen_locked", "airplane_mode",
            "token", "last_executed_cmd_ids"
        }
        extra = {k: v for k, v in data.items() if k not in declared_keys}
        ext_json = None
        if extra:
            try: ext_json = json.dumps(extra, ensure_ascii=False)[:REPORTS_EXT_MAX_BYTES]
            except Exception: pass

        # ★★★ 核心修改：加锁写入，排队执行，防止多线程并发写导致 database is locked 2026-11-05 14:30★★★
        with db_write_lock:
            conn = sqlite3.connect('track_history.db', timeout=10)
            try:
                conn.execute("PRAGMA busy_timeout=10000")
                conn.execute("""INSERT INTO reports
                    (device_id, timestamp, received_at, day_str, hour, weekday,
                     battery_level, is_charging, battery_temp_c, battery_voltage_mv,
                     wifi_ssid, wifi_bssid, wifi_rssi, local_ip, source_ip,
                     network_type, network_subtype, has_internet,
                     sim_state, sim_operator,
                     is_screen_on, is_screen_locked, airplane_mode,
                     ringer_mode, volume_alarm, unlock_count_since_boot,
                     chat_locked, elder_mode, os_version, uptime_ms,
                     lat, lng, accuracy, location_provider, location_time, location_age_s,
                     recent_locations_json,
                     nearby_wifi_count, nearby_wifi_json, raw_json, ext_json,
                     build_version, protocol_version)
                    VALUES (?,?,?,?,?,?, ?,?,?,?, ?,?,?,?,?, ?,?,?, ?,?, ?,?,?, ?,?,?, ?,?,?,?, ?,?,?,?,?,?, ?, ?,?,?,?, ?,?)""", (
                    dev_id, ts, received_at, dt.strftime("%Y-%m-%d"), dt.hour, dt.weekday(),
                    batt_level, is_charging, batt_temp, batt_volt,
                    wifi_ssid or None, wifi_bssid, wifi_rssi, local_ip, source_ip,
                    net_type, net_subtype, has_internet,
                    sim_state, sim_operator,
                    1 if data.get("is_screen_on") else 0, is_screen_locked, airplane_mode,
                    ringer_mode, volume_alarm, unlock_cnt,
                    1 if data.get("chat_locked") else 0,
                    1 if data.get("elder_mode") else 0,
                    data.get("os_version") if isinstance(data.get("os_version"), str) else None,
                    data.get("uptime_ms") if isinstance(data.get("uptime_ms"), int) else None,
                    lat, lng, accuracy,
                    (loc.get("provider") or None) if isinstance(loc.get("provider"), str) else None,
                    loc_time, loc_age_s,
                    recent_locs_json,
                    len(nearby), nearby_json, raw_json, ext_json,
                    data.get("build_version"),
                    data.get("protocol_version"),
                ))
                conn.commit()
            finally:
                conn.close()
    except Exception as e:
        with print_lock:
            print(f"⚠️ [reports] 写入失败 {dev_id}: {e}")


def cleanup_old_reports():
    """分阶段清理，控制 reports 表膨胀"""
    try:
        now_ms = int(datetime.datetime.now().timestamp() * 1000)
        cleared_raw = cleared_nearby = 0
        # ★ FIX-2 2026-11-18：这是两条全表 UPDATE（写事务），原先没持 db_write_lock，
        #   与 save_report / save_history / POST /api/message 的写事务并发 -> database is locked
        with db_write_lock:
            conn = sqlite3.connect('track_history.db', timeout=30)
            try:
                conn.execute("PRAGMA busy_timeout=30000")
                c = conn.cursor()
                if REPORTS_KEEP_RAW_DAYS > 0:
                    cutoff = now_ms - REPORTS_KEEP_RAW_DAYS * 86400 * 1000
                    c.execute("UPDATE reports SET raw_json=NULL WHERE timestamp < ? AND raw_json IS NOT NULL", (cutoff,))
                    cleared_raw = c.rowcount
                if REPORTS_KEEP_NEARBY_DAYS > 0:
                    cutoff = now_ms - REPORTS_KEEP_NEARBY_DAYS * 86400 * 1000
                    c.execute("UPDATE reports SET nearby_wifi_json=NULL WHERE timestamp < ? AND nearby_wifi_json IS NOT NULL", (cutoff,))
                    cleared_nearby = c.rowcount
                conn.commit()
            finally:
                conn.close()
        if cleared_raw or cleared_nearby:
            with print_lock:
                print(f"🧹 [reports] 清理完成: raw_json -{cleared_raw}, nearby -{cleared_nearby}")
    except Exception as e:
        with print_lock:
            print(f"⚠️ [reports] 清理失败: {e}")


# ==================== 设备解析 ====================

def _state_lock_snapshot():
    class _Ctx:
        def __enter__(self_inner):
            with state_lock:
                return {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
        def __exit__(self_inner, *args):
            return False
    return _Ctx()


def resolve_target_devices(target_ip):
    with _state_lock_snapshot() as devices:
        if not devices:
            return []
        if target_ip == "all" or target_ip == "255.255.255.255":
            return list(devices.keys())
        if isinstance(target_ip, list):
            ips = set(ip for ip in target_ip if ip and ip != "0.0.0.0")
            if not ips: return []
            result = []
            for dev_id, info in devices.items():
                candidates = {
                    info.get("source_ip"),
                    info.get("udp_source_ip"),
                    (info.get("network") or {}).get("local_ip"),
                }
                if candidates & ips:
                    result.append(dev_id)
            return result
        result = []
        for dev_id, info in devices.items():
            candidates = {
                info.get("source_ip"),
                info.get("udp_source_ip"),
                (info.get("network") or {}).get("local_ip"),
            }
            if target_ip in candidates:
                result.append(dev_id)
        return result


# ★ 2026-11-07 [P4.1]: 版本感知前缀生成
def get_target_protocol(dev_id):
    """查设备协议版本，旧设备默认 v1"""
    with state_lock:
        info = ALL_DEVICES_STATUS.get(dev_id, {})
        v = info.get("protocol_version")
        return int(v) if isinstance(v, (int, str)) and str(v).isdigit() else 1


def _build_version_aware_commands(target_devs, action_name, payload):
    """
    版本感知前缀（2026-11-07 [P4.1]）:
    - custom_msg: 全 v2→FLOAT; 全 v1→MSG(不含冒号); 混合/广播→FLOAT(含冒号只FLOAT)
    - chat_msg:   CHAT + MSG 双发
    """
    if action_name == "custom_msg":
        versions = set(get_target_protocol(d) for d in target_devs) if target_devs else set()
        has_colon = ":" in payload
        if versions == {2}:
            return [f"FIND_ME_FLOAT:{RESCUE_SECRET}:{payload}"]
        if versions == {1}:
            # ★ FIX-3 2026-11-18：旧版此处 return []，使 /api/control 回 status=error；
            #   但第 1377~1380 行早已把这条 custom_msg 入队 —— 前端提示"失败"，
            #   设备下次上报却照样收到并弹屏，返回码与实际行为相反。
            #   改为把半角冒号降级成全角冒号：v1 用首个冒号切字段，全角冒号不会被截断，
            #   消息不丢、返回码也恢复真实。
            safe_payload = payload.replace(":", "：") if has_colon else payload
            return [f"FIND_ME_MSG:{RESCUE_SECRET}:{safe_payload}"]
        cmds = [f"FIND_ME_FLOAT:{RESCUE_SECRET}:{payload}"]
        if not has_colon:
            cmds.append(f"FIND_ME_MSG:{RESCUE_SECRET}:{payload}")
        return cmds
    if action_name == "chat_msg":
        return [f"FIND_ME_CHAT:{RESCUE_SECRET}:Web_Console:{payload}",
                f"FIND_ME_MSG:{RESCUE_SECRET}:Web_Console:{payload}"]
    return []


# ==================== HTTP 下行队列 ====================

def enqueue_command(dev_id, action, payload):
    global CMD_SEQ
    if not dev_id: return
    now = time.time()
    payload_str = payload if payload is not None else ""
    with _cmd_lock:
        queue = PENDING_COMMANDS.setdefault(dev_id, [])
        for i, c in enumerate(queue):
            if c["type"] == action and c["payload"] == payload_str:
                queue[i] = {"id": c["id"], "type": action, "payload": payload_str, "ts": now}
                with print_lock:
                    print(f"📋 [HTTP队列] {dev_id} ~{action}({c['id']}) 去重（延后过期）")
                return
        CMD_SEQ += 1
        cmd_id = f"c_{int(now)}_{CMD_SEQ}"
        dropped = 0
        while len(queue) >= CMD_MAX_PER_DEVICE:
            queue.pop(0); dropped += 1
        queue.append({"id": cmd_id, "type": action, "payload": payload_str, "ts": now})
    with print_lock:
        if dropped:
            print(f"📋 [HTTP队列] {dev_id} +{action}({cmd_id}) 队列满丢弃 {dropped} 条旧指令")
        else:
            print(f"📋 [HTTP队列] {dev_id} +{action}({cmd_id})")


def process_executed_commands(dev_id, executed_ids):
    if not executed_ids: return
    id_set = {x for x in executed_ids if isinstance(x, str) and x}
    if not id_set: return
    removed = 0
    with _cmd_lock:
        queue = PENDING_COMMANDS.get(dev_id, [])
        if not queue: return
        new_queue = [c for c in queue if c["id"] not in id_set]
        removed = len(queue) - len(new_queue)
        if new_queue: PENDING_COMMANDS[dev_id] = new_queue
        else: PENDING_COMMANDS.pop(dev_id, None)
    if removed > 0:
        with print_lock:
            print(f"✅ [HTTP队列] {dev_id} 已确认执行 {removed} 条")


def get_pending_commands(dev_id):
    now = time.time()
    with _cmd_lock:
        queue = PENDING_COMMANDS.get(dev_id, [])
        if not queue: return []
        valid = [c for c in queue if now - c["ts"] < cmd_ttl_for(c.get("type", ""))]
        if len(valid) != len(queue):
            if valid: PENDING_COMMANDS[dev_id] = valid
            else: PENDING_COMMANDS.pop(dev_id, None)
        return [dict(c) for c in valid]


def cleanup_expired_commands():
    now = time.time()
    with _cmd_lock:
        for dev_id in list(PENDING_COMMANDS.keys()):
            queue = PENDING_COMMANDS.get(dev_id, [])
            valid = [c for c in queue if now - c["ts"] < cmd_ttl_for(c.get("type", ""))]
            if valid: PENDING_COMMANDS[dev_id] = valid
            else: PENDING_COMMANDS.pop(dev_id, None)


def cmd_cleaner():
    """每小时清理一次过期数据"""
    while True:
        try:
            time.sleep(3600)
            cleanup_expired_commands()
            cleanup_old_reports()
        except Exception as e:
            with print_lock:
                print(f"⚠️ 清理异常: {e}")


# ==================== UDP 监听 ====================

def udp_listener():
    while True:
        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            udp_sock.bind(('0.0.0.0', 8888))
            print("🎧 UDP 监听已启动，等待局域网对讲机消息...")
            while True:
                try:
                    data, addr = udp_sock.recvfrom(4096)
                except Exception as recv_err:
                    print(f"⚠️ UDP recvfrom 异常: {recv_err}")
                    time.sleep(1)
                    continue
                msg = data.decode('utf-8', errors='ignore')
                source_ip = addr[0]
                # ★ 2026-11-07 [P4.6 修正]: v2 CHAT + legacy MSG
                if msg.startswith("FIND_ME_CHAT:") or msg.startswith("FIND_ME_MSG:"):
                    parts = msg.split(":", 3)
                    if len(parts) == 4 and parts[1] == RESCUE_SECRET:
                        sender = parts[2]
                        content = parts[3]
                        if sender not in ["Web_Console", "PC_Terminal"]:
                            dedup_key = sender + ":" + content
                            now_ts = time.time()
                            if now_ts - LAST_MSG_INFO.get(dedup_key, 0) < 3.0:
                                continue
                            LAST_MSG_INFO[dedup_key] = now_ts
                            if len(LAST_MSG_INFO) > LAST_MSG_INFO_MAX:
                                cutoff = now_ts - LAST_MSG_INFO_TTL
                                stale = [k for k, v in list(LAST_MSG_INFO.items()) if v < cutoff]
                                for k in stale: LAST_MSG_INFO.pop(k, None)
                            now_ms = int(datetime.datetime.now().timestamp() * 1000)
                            ts = datetime.datetime.now().strftime("%H:%M:%S")
                            # ★ 合并为单个锁块，消除一切嵌套疑虑
                            with state_lock:
                                if sender not in ALL_DEVICES_STATUS:
                                    ALL_DEVICES_STATUS[sender] = {
                                        "device_id": sender, "received_at": now_ms,
                                        "network": {}, "battery": {"level": "?"}
                                    }
                                info = ALL_DEVICES_STATUS[sender]
                                if not info.get('network'): info['network'] = {}
                                info['udp_source_ip'] = source_ip
                                info["received_at"] = now_ms
                                info.pop("offline_alerted", None)
                                info["last_chat"] = now_ms
                                
                                CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                                if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
                                
                            with print_lock:
                                print(f"\n🗣️ [对讲机] {sender} (来自IP: {source_ip}) 说: {content}")
        except OSError as e:
            if e.errno in (98, 10048) or "Address already in use" in str(e):
                print(f"❌ udp 8888 bind 失败: {e}，10s 后重试")
                try: udp_sock.close()
                except Exception: pass
                time.sleep(10)
                continue
            else:
                print(f"❌ UDP 监听绑定失败: {e}")
                time.sleep(5)
                continue
        except Exception as e:
            print(f"❌ UDP 监听异常: {e}")
            time.sleep(5)
            continue
        finally:
            try: udp_sock.close()
            except Exception: pass


def offline_detector():
    """
    失联检测：每 5 分钟扫描一次，>2h 未上报告警并排队兜底指令。

    ★ 2026-11-07 修复：
      1) 死锁：原代码在持有 print_lock 时调用 enqueue_command，后者内部再拿 print_lock，
         threading.Lock 不可重入 -> 检测线程永久卡死。
      2) 重复告警：原代码 info 是 dict 快照，info['offline_alerted'] = True 未写回
         ALL_DEVICES_STATUS，导致每轮扫描重复告警、重复排队。
      现改为：先在 state_lock 下做快照并释放，处理时单独加锁，并把 offline_alerted 写回全局。
    """
    while True:
        try:
            time.sleep(300)
            now = int(datetime.datetime.now().timestamp() * 1000)
            with state_lock:
                devices_snapshot = [(k, dict(v)) for k, v in ALL_DEVICES_STATUS.items()]
            for dev_id, info in devices_snapshot:
                last_seen = info.get('received_at', 0)
                gap_min = (now - last_seen) // 60000
                already_alerted = info.get('offline_alerted', False)

                if gap_min > 120 and not already_alerted:
                    with state_lock:
                        if dev_id in ALL_DEVICES_STATUS:
                            ALL_DEVICES_STATUS[dev_id]['offline_alerted'] = True
                    with print_lock:
                        print(f"\n🚨 【失联告警】{dev_id} 已 {gap_min // 60} 小时 {gap_min % 60} 分钟未上报！")
                    # 给失联设备排队兜底指令：只要它有一次 HTTP 上报，就能拿到并重启 UDP
                    enqueue_command(dev_id, "force_report", "")
                    enqueue_command(dev_id, "restart_udp", "")
                    with print_lock:
                        print(f"📋 [兜底] 已为 {dev_id} 排队 force_report 和 restart_udp 指令")

                elif gap_min <= 120 and already_alerted:
                    with state_lock:
                        if dev_id in ALL_DEVICES_STATUS:
                            ALL_DEVICES_STATUS[dev_id]['offline_alerted'] = False
                    with print_lock:
                        print(f"\n✅ 【恢复】{dev_id} 已恢复上报")
        except Exception as e:
            with print_lock:
                print(f"⚠️ 失联检测异常: {e}")



def save_history(dev_id, lat, lng, ts):
    if lat is None or lng is None: return
    with db_write_lock:
        conn = None
        try:
            conn = sqlite3.connect('track_history.db', timeout=10)
            c = conn.cursor()
            c.execute("INSERT INTO history VALUES (?, ?, ?, ?)", (dev_id, lat, lng, ts))
            conn.commit()
        except Exception: 
            pass
        finally:
            if conn:
                try: conn.close()
                except Exception: pass


def format_loc_time(ts_ms):
    if not ts_ms: return "N/A"
    try:
        dt = datetime.datetime.fromtimestamp(ts_ms / 1000.0)
        seconds = int((datetime.datetime.now() - dt).total_seconds())
        if seconds < 0: ago = "刚刚"
        elif seconds < 60: ago = f"{seconds}秒前"
        elif seconds < 3600: ago = f"{seconds // 60}分钟前"
        elif seconds < 86400: ago = f"{seconds // 3600}小时前"
        else: ago = f"{seconds // 86400}天前"
        return f"{dt.strftime('%m-%d %H:%M:%S')} <br><span style='font-size:12px;color:#57606a;'>{ago}</span>"
    except Exception: return "N/A"


# ★ FIX-6 2026-11-18 [性能 P1]：get_local_ip 原先每次 smart_send 都跑一遍
#   （最多 4 次 UDP connect，各 1s 超时；全失败还要同步读 /proc/net/fib_trie），
#   而 send_udp_broadcast 内部 get_subnet_broadcast 会再调一次 —— 单次 /api/control 最多探测 2 轮。
#   15 worker 的线程池下，外网抖动时几个慢请求就能把 worker 占满，表现为整站变慢。
#   改为进程级 TTL 缓存（探测逻辑原样保留在 _detect_local_ip）。
_LOCAL_IP_CACHE_TTL = 300
_local_ip_cache = {"ip": None, "ts": 0.0}
_local_ip_lock = threading.Lock()


def _detect_local_ip():
    for target in [("8.8.8.8", 80), ("114.114.114.114", 53), ("1.1.1.1", 443), ("223.5.5.5", 53)]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(1); s.connect(target)
            ip = s.getsockname()[0]; s.close()
            if (ip and not ip.startswith("127.") and ip != "0.0.0.0" and not ip.startswith("169.254.")):
                return ip
        except Exception: pass
    try:
        with open("/proc/net/fib_trie", "r") as f:
            lines = f.readlines()
        for i, line in enumerate(lines):
            if "LOCAL" in line and i > 0:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", lines[i - 1])
                if m:
                    ip = m.group(1)
                    if (ip != "0.0.0.0" and not ip.startswith("127.") and not ip.startswith("169.254.")):
                        return ip
    except Exception: pass
    return "127.0.0.1"


def get_local_ip(force_refresh=False):
    """带 TTL 缓存的本机出口 IP。force_refresh=True 强制重探（切网/网卡热插拔时用）。"""
    now = time.time()
    with _local_ip_lock:
        if (not force_refresh) and _local_ip_cache["ip"] and (now - _local_ip_cache["ts"]) < _LOCAL_IP_CACHE_TTL:
            return _local_ip_cache["ip"]
    ip = _detect_local_ip()
    with _local_ip_lock:
        _local_ip_cache["ip"] = ip
        _local_ip_cache["ts"] = time.time()
    return ip


def get_subnet_broadcast():
    ip = get_local_ip()
    if ip.startswith("127.") or ip == "0.0.0.0": return "255.255.255.255"
    parts = ip.split(".")
    if len(parts) == 4: return ".".join(parts[:3]) + ".255"
    return "255.255.255.255"


def send_udp_broadcast(command_str, target_ip="255.255.255.255"):
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        is_full = (target_ip == "255.255.255.255")
        is_subnet = target_ip.endswith(".255") and not is_full
        if is_full:
            subnet = get_subnet_broadcast()
            if subnet != "255.255.255.255":
                actual = subnet; targets = [subnet, "255.255.255.255"]
            else:
                actual = "255.255.255.255"; targets = ["255.255.255.255"]
            repeats = UDP_ROUNDS_BROADCAST
        elif is_subnet:
            actual = target_ip; targets = [target_ip]; repeats = UDP_ROUNDS_BROADCAST
        else:
            actual = target_ip; targets = [target_ip]; repeats = UDP_ROUNDS_UNICAST
        mode = " (广播)" if (is_full or is_subnet) else " (单播)"
        with print_lock:
            print(f"\n📡 [控制台] 发送至 {actual}{mode}: {command_str}")
        sent_pkts = 0
        for t in targets:
            for _ in range(repeats):
                try:
                    sock.sendto(command_str.encode('utf-8'), (t, 8888))
                    sent_pkts += 1
                    time.sleep(0.05)
                except Exception: pass
        sock.close()
        # ★ FIX-7 2026-11-18：返回真实发包数。旧版只要 socket 建得起就恒返 True，
        #   使 smart_send 几乎永远 sent_any=True，/api/control 里
        #   "UDP 未确认送达，已排队 N 条" 那条兜底分支成了死代码，
        #   UI 一律弹"✅ 指令发送成功" —— 把"发出去了"报成"成功了"，误导运维。
        return sent_pkts
    except Exception as e:
        with print_lock: print(f"发送失败: {e}")
        return 0


# ==================== 媒体 MIME 判定（v2.1）====================

# ★ 2026-11-18 修复：补齐 webp/bmp/音频，杜绝“后缀与 Content-Type 不一致导致手机端不解码”
MIME_BY_EXT = {
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
    '.png': 'image/png', '.gif': 'image/gif',
    '.webp': 'image/webp', '.bmp': 'image/bmp',
    '.mp4': 'video/mp4', '.webm': 'video/webm', '.mkv': 'video/x-matroska',
    '.mp3': 'audio/mpeg', '.aac': 'audio/aac', '.m4a': 'audio/mp4',
    '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.wma': 'audio/x-ms-wma',
    '.html': 'text/html; charset=utf-8', '.h5': 'text/html; charset=utf-8',
}


def get_media_mime(filename):
    """根据后缀返回正确的 Content-Type，避免视频被当作图片发送"""
    ext = os.path.splitext(filename)[1].lower()
    return MIME_BY_EXT.get(ext, 'application/octet-stream')


def sniff_media_ext(body_bytes):
    """★ 2026-11-18 新增：用文件头魔数判定真实类型。
    前端 fetch 上传时后缀来自文件名，用户改名/手机相册导出成 .heic/浏览器伪造 .png
    都会让服务端写出“假 jpg”，手机端 BitmapFactory 直接 decode 出 null —— 表现就是推图不显示。
    """
    if not body_bytes or len(body_bytes) < 16:
        return None
    head = body_bytes[:16]
    if head[:3] == b'\xff\xd8\xff':
        return '.jpg'
    if head[:8] == b'\x89PNG\r\n\x1a\n':
        return '.png'
    if head[:6] in (b'GIF87a', b'GIF89a'):
        return '.gif'
    if head[:2] == b'BM':
        return '.bmp'
    if head[:4] == b'RIFF' and body_bytes[8:12] == b'WEBP':
        return '.webp'
    if head[4:8] == b'ftyp':
        return '.mp4'
    if head[:4] == b'\x1a\x45\xdf\xa3':
        return '.webm'
    if head[:3] == b'ID3' or head[:2] in (b'\xff\xfb', b'\xff\xf3', b'\xff\xf2'):
        return '.mp3'
    if head[:4] == b'\x00\x00\x00' or head[4:8] == b'wAVe':
        return '.wav'
    if head[:5] in (b'<?xml', b'<!DOC', b'<html', b'<Html'):
        return '.html'
    return None


def is_probably_image(ext):
    return (ext or '') in ('.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp')


# ==================== 主处理器 ====================

def _channel_badge_html(ch, gap_min):
    """
    v2.0 补丁：生成通道健康度徽章 HTML。
    返回空字符串表示不显示（旧版 APK 未上报 channel）。
    """
    if gap_min is not None and gap_min > 120:
        return "<span title='失联超过 2 小时' style='color:#cf222e;font-size:13px;'>🔴 </span>"
    if not ch:
        return ""
    health = ch.get("health", "")
    if health == "good":
        return "<span title='UDP 直连正常，对讲机可用' style='color:#2da44e;font-size:13px;'>📡 </span>"
    if health == "degraded":
        reasons = []
        if not ch.get("udp_listening", True):
            reasons.append("UDP 监听未启动")
        q = ch.get("offline_queue_size", 0)
        if isinstance(q, int) and q > 0:
            reasons.append(f"离线队列积压 {q} 条")
        elif isinstance(q, int) and q < 0:
            reasons.append("离线队列状态未知")
        cf = ch.get("consecutive_failures", 0) or 0
        if cf > 0:
            reasons.append(f"连续上报失败 {cf} 次")
        tip = " / ".join(reasons) if reasons else "通道异常"
        return (f"<span title='{html_lib.escape(tip)}' "
                f"style='color:#bf8700;font-size:13px;'>🌐 </span>")
    return ""


def handle_client(client_conn, client_addr):
    try:
        client_conn.settimeout(3.0)
        try:
            first_chunk = client_conn.recv(65536)
        except socket.timeout:
            return
        if not first_chunk: return

        client_conn.settimeout(10.0)
        raw = first_chunk
        while b"\r\n\r\n" not in raw:
            chunk = client_conn.recv(65536)
            if not chunk: break
            raw += chunk
            if len(raw) > 10 * 1024 * 1024: break
        header_part, _, body_part = raw.partition(b"\r\n\r\n")
        header_text = header_part.decode('utf-8', errors='ignore')

        content_length = 0
        for line in header_text.split("\r\n"):
            if line.lower().startswith("content-length:"):
                try: content_length = int(line.split(":", 1)[1].strip())
                except Exception: pass
                break
        if content_length > 0:
            while len(body_part) < content_length:
                try:
                    chunk = client_conn.recv(65536)
                except socket.timeout:
                    break
                if not chunk: break
                body_part += chunk
                if len(raw) > 30 * 1024 * 1024: break
            body_part = body_part[:content_length]
        # ★ 2026-11-18 修复：记录截断标记。旧版把半张图当完整文件落盘，
        # 手机端 BitmapFactory.decodeStream 返回 null —— 典型症状“推送图片手机端不显示”。
        BODY_TRUNCATED = (content_length > 0 and len(body_part) < content_length)
        if not header_text: return

        if header_text.startswith("OPTIONS"):
            client_conn.sendall(b"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return

        # ===== POST /api/report =====
        if "POST /api/report" in header_text:
            try:
                data = json.loads(body_part.decode('utf-8', errors='ignore'))
                if data.get("token") != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                dev_id = data.get("device_id", "unknown_device")
                
                if data.get("network") is None: data["network"] = {}
                if data.get("battery") is None: data["battery"] = {}
                if data.get("location") is None: data["location"] = {}
                if data.get("nearby_wifi") is None: data["nearby_wifi"] = []

                executed_ids = data.pop("last_executed_cmd_ids", [])
                if executed_ids:
                    process_executed_commands(dev_id, executed_ids)
                data["received_at"] = int(datetime.datetime.now().timestamp() * 1000)

                # v2.0 补丁：通道健康度判定（服务端算，客户端不填）
                ch = data.get("channel") or {}
                if ch:
                    qsize = ch.get("offline_queue_size")
                    if qsize is None or (isinstance(qsize, int) and qsize < 0):
                        ch["health"] = "degraded"  # 队列状态未知，保守降级
                    elif (ch.get("udp_listening") and
                          qsize == 0 and
                          (ch.get("consecutive_failures", 0) or 0) == 0):
                        ch["health"] = "good"
                    else:
                        ch["health"] = "degraded"
                    data["channel"] = ch

                with state_lock:
                    if dev_id not in ALL_DEVICES_STATUS:
                        ALL_DEVICES_STATUS[dev_id] = {}
                    prev_info = dict(ALL_DEVICES_STATUS[dev_id])
                    prev_udp_ip = prev_info.get("udp_source_ip")
                    if "cloud_logs" in data:
                        prev_info["cloud_logs"] = data["cloud_logs"]
                        prev_info["cloud_logs_time"] = data["received_at"]
                    elif "cloud_logs" in prev_info:
                        data["cloud_logs"] = prev_info["cloud_logs"]
                        data["cloud_logs_time"] = prev_info.get("cloud_logs_time", 0)
                    data["source_ip"] = client_addr[0]
                    data["local_ip"] = (data.get("network") or {}).get("local_ip", "")
                    # ★ 2026-11-07 [P4.4]
                    data["build_version"] = data.get("build_version") or "旧版"
                    data["protocol_version"] = data.get("protocol_version") or 1
                    if prev_udp_ip:
                        data["udp_source_ip"] = prev_udp_ip
                    if prev_info.get("last_chat"):
                        data["last_chat"] = prev_info["last_chat"]
                    ALL_DEVICES_STATUS[dev_id] = data

                loc = data.get('location') or {}
                lat, lng = loc.get('latitude'), loc.get('longitude')
                if lat is not None and lng is not None:
                    save_history(dev_id, lat, lng, data["received_at"])
                save_report(dev_id, data, client_addr[0])

                pending = get_pending_commands(dev_id)
                next_poll = 30 if pending else 0
                response_body = json.dumps({
                    "status": "success",
                    "config": GLOBAL_DEFAULT_CONFIG,
                    "pending_commands": pending,
                    "next_poll_seconds": next_poll
                })
                response_bytes = response_body.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {len(response_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + response_bytes)
            except Exception as e:
                with print_lock: print(f"❌ POST /api/report 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== POST /api/message =====
        elif "POST /api/message" in header_text:
            try:
                data = json.loads(body_part.decode('utf-8', errors='ignore'))
                if data.get("token") != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                dev_id = data.get("device_id", "unknown")
                msg_time = data.get("time_str", "")
                msg = data.get("message", "")
                ts = data.get("timestamp", 0)
                # ★ FIX-2 2026-11-18：这是全文件唯一一处绕过 db_write_lock 的 INSERT。
                #   必须与 save_report / save_history 共用同一把写锁，
                #   否则与 30 分钟一轮的高频上报并发时就是 database is locked 的真源头
                #   （评审说的"读不加锁"反而是伪问题：WAL 下读者读快照，不会被写者挡住）。
                with db_write_lock:
                    conn = sqlite3.connect('track_history.db', timeout=10)
                    try:
                        conn.execute("PRAGMA busy_timeout=10000")
                        c = conn.cursor()
                        c.execute("INSERT INTO messages VALUES (?, ?, ?, ?)", (dev_id, msg_time, msg, ts))
                        conn.commit()
                    finally:
                        conn.close()
                client_conn.sendall(b"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except Exception as e:
                with print_lock: print(f"❌ POST /api/message 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== POST /api/upload_media =====
        # ★ v2.1 补丁：动态识别后缀，支持视频（修复旧版写死 .jpg 导致 MIME 错乱）
        elif "POST /api/upload_media" in header_text:
            try:
                os.makedirs(UPLOAD_MEDIA_DIR, exist_ok=True)
                # 从 URL 中提取原始文件名后缀（例如 ?filename=test.mp4）
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                orig_name = params.get("filename", ["unknown.jpg"])[0]
                ext = os.path.splitext(orig_name)[1].lower()
                if ext not in ALLOWED_MEDIA_EXTS:
                    ext = '.jpg' # 兜底

                # ★ 2026-11-18 修复①：拒绝残缺文件落盘（上传超时 / 10MB 截断）
                if content_length <= 0:
                    raise ValueError("上传体为空(Content-Length=0)")
                if BODY_TRUNCATED:
                    raise ValueError(f"上传不完整: 期望 {content_length} 字节，仅收到 {len(body_part)} 字节")

                # ★ 2026-11-18 修复②：用魔数纠正后缀，杜绝“假 jpg / 真 heic”导致手机端解码失败
                sniffed = sniff_media_ext(body_part)
                if sniffed and sniffed != ext:
                    with print_lock:
                        print(f"⚠️ [media] 声明后缀 {ext} 与真实魔数 {sniffed} 不符，已按真实类型纠正"
                              + ("（原始可能是 HEIC/TIFF 等手机私有格式，安卓端可能仍无法解码）"
                                 if sniffed == '.html' or ext in ('.heic', '.heif') else ""))
                    if sniffed:
                        ext = sniffed
                if not is_probably_image(ext) and sniffed and is_probably_image(sniffed):
                    ext = sniffed

                filename = datetime.datetime.now().strftime("media_%Y%m%d_%H%M%S_%f") + ext
                filepath = os.path.join(UPLOAD_MEDIA_DIR, filename)

                with open(filepath, "wb") as f:
                    f.write(body_part)
                    f.flush()
                    os.fsync(f.fileno())   # ★ 2026-11-18 修复③：立即刷盘，消除“刚推就 404”的竞态

                # ★ 2026-11-18 修复④：URL 主机优先取浏览器实际访问的 Host 头。
                # 旧版写死 get_local_ip()：多网卡 / Docker(172.17.x.x) / NAT / 公网映射场景下
                # 会选到一个手机根本连不通的网段 IP，图片必然不显示。
                host_hdr = ""
                for _hl in header_text.split("\r\n"):
                    if _hl.lower().startswith("host:"):
                        host_hdr = _hl.split(":", 1)[1].strip().strip("/")
                        break
                if host_hdr and not host_hdr.startswith("127.") and host_hdr != "localhost":
                    media_url = f"http://{host_hdr}/uploads/media/{filename}"
                else:
                    media_url = f"http://{get_local_ip()}:{PORT}/uploads/media/{filename}"
                with print_lock:
                    print(f"🖼️ [media] 已转存 {filename} ({len(body_part)}B, mime={get_media_mime(filename)}) -> {media_url}")
                resp = json.dumps({"status": "ok", "url": media_url,
                                   "mime": get_media_mime(filename), "size": len(body_part)})
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode() + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ 上传媒体失败: {e}")
                try:
                    err = json.dumps({"status": "error", "msg": f"媒体转存失败: {e}"}, ensure_ascii=False).encode('utf-8')
                    client_conn.sendall(
                        f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(err)}\r\nConnection: close\r\n\r\n".encode() + err)
                except Exception: pass

        # ★ 插件：静态媒体文件访问 (供手机下载展示)
        elif "GET /uploads/media/" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                # ★ 2026-11-18 修复：先剥掉 query（?t=123 会让 basename 得到带问号的假文件名 -> 必然 404）
                path = path.split("?")[0].split("#")[0]
                safe_name = os.path.basename(path)
                filepath = os.path.join(UPLOAD_MEDIA_DIR, safe_name)
                if not os.path.isfile(filepath):
                    with print_lock:
                        print(f"⚠️ [media] 404: 请求 {path} -> 本地不存在 {filepath}")
                    client_conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                with open(filepath, "rb") as f:
                    content = f.read()
                mime = get_media_mime(safe_name)

                header = (
                    "HTTP/1.1 200 OK\r\n"
                    f"Content-Type: {mime}\r\n"
                    "Access-Control-Allow-Origin: *\r\n"
                    "Accept-Ranges: bytes\r\n"
                    "Cache-Control: no-cache\r\n"
                    f"Content-Length: {len(content)}\r\n"
                    "Connection: close\r\n\r\n"
                ).encode('utf-8')
                client_conn.sendall(header + content)
            except Exception as e:
                with print_lock: print(f"❌ 访问媒体文件异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        elif "POST /api/upload_log" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                query = urlparse(path_with_query).query
                params = parse_qs(query)
                token = params.get("token", [""])[0]
                device_id = params.get("device_id", ["unknown"])[0]
                if token != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
                    return
                try: log_data = gzip.decompress(body_part)
                except Exception: log_data = body_part
                ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S_%f")
                safe_id = "".join(c for c in device_id if c.isalnum() or c in "_-")
                filename = f"{safe_id}_{ts}.log"
                filepath = os.path.join(UPLOAD_LOG_DIR, filename)
                with open(filepath, "wb") as f: f.write(log_data)
                with print_lock: print(f"\n📋 收到日志上传: {device_id} -> {filename} ({len(log_data)} bytes)")
                resp = json.dumps({"status": "ok", "filename": filename, "size": len(log_data)})
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode() + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ upload_log 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/logs =====
        elif "GET /api/logs" in header_text:
            try:
                files = []
                if os.path.isdir(UPLOAD_LOG_DIR):
                    for fn in sorted(os.listdir(UPLOAD_LOG_DIR), reverse=True):
                        if fn.endswith(".log"):
                            fp = os.path.join(UPLOAD_LOG_DIR, fn)
                            files.append({"filename": fn, "size": os.path.getsize(fp), "mtime": int(os.path.getmtime(fp) * 1000)})
                resp = json.dumps(files); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode() + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/logs 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/download_log =====
        elif "GET /api/download_log" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                name = params.get("name", [""])[0]
                safe_name = os.path.basename(name)
                if not safe_name.endswith(".log"):
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
                    return
                filepath = os.path.join(UPLOAD_LOG_DIR, safe_name)
                if not os.path.isfile(filepath):
                    client_conn.sendall(b"HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
                    return
                with open(filepath, "rb") as f: 
                    content = f.read()
                # ★ 使用最稳妥的单行拼接，彻底告别语法兼容问题
                header = (
                    "HTTP/1.1 200 OK\r\n"
                    "Content-Type: text/plain; charset=utf-8\r\n"
                    f"Content-Disposition: attachment; filename=\"{safe_name}\"\r\n"
                    "Access-Control-Allow-Origin: *\r\n"
                    f"Content-Length: {len(content)}\r\n"
                    "Connection: close\r\n\r\n"
                ).encode('utf-8')
                client_conn.sendall(header + content)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/download_log 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/cloud_logs =====
        elif "GET /api/cloud_logs" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                dev_id = params.get("device_id", [""])[0]
                with state_lock:
                    info = dict(ALL_DEVICES_STATUS.get(dev_id, {}))
                resp = json.dumps({"cloud_logs": info.get("cloud_logs", ""), "cloud_logs_time": info.get("cloud_logs_time", 0)})
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/cloud_logs 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/cmd_status =====
        elif "GET /api/cmd_status" in header_text:
            try:
                with state_lock:
                    devices_snapshot = {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
                now = time.time()
                result = {}
                for dev_id, info in devices_snapshot.items():
                    queue = get_pending_commands(dev_id)
                    last_seen = info.get("received_at", 0) / 1000.0
                    ip = (info.get("source_ip") or info.get("udp_source_ip") or (info.get("network") or {}).get("local_ip", ""))
                    battery = info.get("battery") or {}
                    batt_level = battery.get("level")
                    if batt_level == "?" or batt_level is None: batt_level = None
                    loc = info.get("location") or {}
                    loc_lat = loc.get("latitude")
                    loc_lng = loc.get("longitude")
                    loc_age = loc.get("age_s")
                    loc_obj = None
                    if loc_lat is not None and loc_lng is not None:
                        loc_obj = {"lat": loc_lat, "lng": loc_lng, "accuracy": loc.get("accuracy"), "age_s": loc_age if isinstance(loc_age, int) else None}
                    
                    net = info.get("network") or {}
                    wifi_ssid = net.get("wifi_ssid") or ""
                    net_type = "离线"
                    if wifi_ssid and wifi_ssid not in ("UNKNOWN", "<unknown ssid>"): net_type = "WiFi"
                    elif ip and not ip.startswith("127.") and ip != "0.0.0.0":
                        net_type = "MOBILE" if ip.startswith("10.") else "WiFi"

                    result[dev_id] = {
                        "pending": len(queue),
                        "oldest_pending_s": max(0, int(now - queue[0]["ts"])) if queue else 0,
                        "last_report_ago_s": max(0, int(now - last_seen)) if last_seen > 0 else -1,
                        "ip": ip or "",
                        "battery": batt_level,
                        "is_charging": bool(battery.get("is_charging")),
                        "wifi_ssid": wifi_ssid,
                        "network_type": net_type,
                        "location": loc_obj,
                        "commands": [{"id": c["id"], "type": c["type"], "age_s": max(0, int(now - c["ts"]))} for c in queue[:10]],
                        # ★ 2026-11-07 [P4.8]
                        "build_version": info.get("build_version", "旧版"),
                        "protocol_version": info.get("protocol_version", 1),
                        "channel": info.get("channel") or {},
                    }
                resp = json.dumps(result); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/cmd_status 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== POST /api/control =====
        elif "POST /api/control" in header_text:
            try:
                cmd_data = json.loads(body_part.decode('utf-8', errors='ignore'))
                # ★ FIX-1 2026-11-18 [安全 P0]：控制面此前完全裸奔 —— /api/report(1021)
                #   与 /api/message(1098) 都校验 token，唯独 /api/control 没有。
                #   任何能连到 8080 的人都可以下发 clear_history / lock_chat /
                #   change_server / push_web(任意 URL 全屏挂到老人手机) / clean_device。
                if not isinstance(cmd_data, dict):
                    client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                if CONTROL_AUTH_ENABLED and cmd_data.get("token") != AUTH_TOKEN:
                    with print_lock:
                        print(f"⛔ [控制面鉴权失败] {client_addr[0]} 的 /api/control 请求 token 无效")
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                action = cmd_data.get("action")
                payload = cmd_data.get("payload", "")
                target_ip = cmd_data.get("target_ip", "255.255.255.255")
                success = False
                extra_msg = ""

                target_devs = []
                if action in HTTP_SUPPORTED_ACTIONS:
                    target_devs = resolve_target_devices(target_ip)
                    for dev_id in target_devs:
                        enqueue_command(dev_id, action, payload)

                def smart_send(cmd_prefix):
                    nonlocal extra_msg          # ★ 2026-11-18：必须声明，否则下面赋值只改到局部变量
                    sent_pkts = 0
                    try:
                        local_ip = get_local_ip()
                        local_prefix = ".".join(local_ip.split(".")[:3])
                    except Exception: local_prefix = ""
                    if isinstance(target_ip, list):
                        for ip in target_ip:
                            if ip and ip != '0.0.0.0':
                                sent_pkts += send_udp_broadcast(cmd_prefix, ip)
                    elif target_ip == "all" or target_ip == "255.255.255.255":
                        # ★ 2026-11-07 修复：改用 state_lock，与 /api/report、clean_device 等写方互斥
                        # ★ 2026-11-18 补丁B：先聚合去重、再一次性发送，总包数受 UDP_TOTAL_PACKET_BUDGET 约束
                        with state_lock:
                            devices_snapshot = {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
                        unicast_ips = []
                        remote_ips = []
                        for dev_id, info in devices_snapshot.items():
                            ip = (info.get("source_ip") or info.get("udp_source_ip") or (info.get("network") or {}).get("local_ip"))
                            if not ip or ip == '0.0.0.0': continue
                            if local_prefix and ip.startswith(local_prefix + "."):
                                if ip not in unicast_ips: unicast_ips.append(ip)
                            else:
                                # ★ FIX-8 2026-11-18：旧版在这里把该 IP 直接丢弃，注释写"广播已能覆盖全网段"
                                #   —— 这是错的：255.255.255.255 不出路由器、不出公网，跨网段/专网路由可达的
                                #   设备因此彻底收不到信令，只能等下一次 HTTP 上报（默认 1800s）。
                                #   改为单独收集，广播照发的同时在预算内补一次定向单播。
                                if ip not in remote_ips: remote_ips.append(ip)
                        need_broadcast = (not unicast_ips) or (len(unicast_ips) > UDP_TOTAL_PACKET_BUDGET)
                        if need_broadcast:
                            sent_pkts += send_udp_broadcast(cmd_prefix, "255.255.255.255")
                        else:
                            for ip in unicast_ips:
                                sent_pkts += send_udp_broadcast(cmd_prefix, ip)
                        if remote_ips and (len(unicast_ips) + len(remote_ips)) <= UDP_TOTAL_PACKET_BUDGET:
                            for ip in remote_ips:
                                sent_pkts += send_udp_broadcast(cmd_prefix, ip)
                        if not unicast_ips and not remote_ips:
                            # ★ 2026-11-18 修复（自 server2_2.py 移植）：无在线设备时旧版直接回"指令执行失败"，
                            #   家属以为是推送逻辑坏了；实际包已广播，只是设备不在列表/不在同网段。
                            extra_msg = "已向子网广播，但当前设备列表为空：请确认手机已在同一 WiFi 下且已成功上报（走流量时 UDP 不可达）"
                    else:
                        sent_pkts += send_udp_broadcast(cmd_prefix, target_ip)
                    return sent_pkts > 0

                # ★ 插件挂载：远程陪伴控制
                if action == "companion_on": success = smart_send(f"FIND_ME_PLUGIN:companion:{RESCUE_SECRET}:on")
                elif action == "companion_off": success = smart_send(f"FIND_ME_PLUGIN:companion:{RESCUE_SECRET}:off")
                elif action == "push_photo": success = smart_send(f"FIND_ME_PUSH_PHOTO:{RESCUE_SECRET}:{payload}")
                elif action == "push_video": success = smart_send(f"FIND_ME_PUSH_VIDEO:{RESCUE_SECRET}:{payload}")
                # ★ 2026-11-18 修复：v2.6 只把 push_audio/push_web 加进了 HTTP_SUPPORTED_ACTIONS，
                #   却忘了在 /api/control 里补 UDP 下发分支 —— 这两个 action 永远 success=False，
                #   只能等手机下一次上报（默认 1800 秒）才从队列里取到，表现为“推了没反应/很久才显示”。
                elif action == "push_audio": success = smart_send(f"FIND_ME_PUSH_AUDIO:{RESCUE_SECRET}:{payload}")
                elif action == "push_web":   success = smart_send(f"FIND_ME_PUSH_WEB:{RESCUE_SECRET}:{payload}")
                
                elif action == "force_report": success = smart_send(f"FIND_ME_REPORT:{RESCUE_SECRET}")
                elif action == "play_alarm": success = smart_send(f"FIND_ME_ALARM:{RESCUE_SECRET}")
                elif action == "custom_msg":
                    # ★ 2026-11-07 [P4.5]: 版本感知双发
                    success = False
                    cmds = _build_version_aware_commands(target_devs, "custom_msg", payload)
                    for cmd_prefix in cmds:
                        if smart_send(cmd_prefix):
                            success = True
                    # ★ FIX-3 2026-11-18：v1+冒号已改为全角冒号降级下发，cmds 不再为空；
                    #   这里只保留"确实构造不出信令"的兜底提示。
                    if not cmds:
                        success = False
                        extra_msg = "未能构造下行信令，指令已写入队列，等手机下次上报时下发"
                elif action == "chat_msg":
                    # ★ 2026-11-07 [P4.5]: CHAT + MSG 双发
                    success = False
                    for cmd_prefix in _build_version_aware_commands(target_devs, "chat_msg", payload):
                        if smart_send(cmd_prefix):
                            success = True
                    if success:
                        ts = datetime.datetime.now().strftime("%H:%M:%S")
                        with state_lock:
                            CHAT_MESSAGES.append({"time": ts, "sender": "Web_Console", "content": payload})
                            if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
                elif action == "change_server": success = smart_send(f"FIND_ME_SERVER:{RESCUE_SECRET}:{payload}")
                # ★ 2026-11-07 [P1-3]
                elif action == "change_config": success = smart_send(f"FIND_ME_CONFIG:{RESCUE_SECRET}:{payload}")
                elif action == "clear_history": success = smart_send(f"FIND_ME_CLEAR_HISTORY:{RESCUE_SECRET}")
                elif action == "clear_chat": success = smart_send(f"FIND_ME_CLEAR_CHAT:{RESCUE_SECRET}")
                elif action == "lock_chat": success = smart_send(f"FIND_ME_CHAT_LOCK:{RESCUE_SECRET}")
                elif action == "unlock_chat": success = smart_send(f"FIND_ME_CHAT_UNLOCK:{RESCUE_SECRET}")
                elif action == "set_sms_num": success = smart_send(f"FIND_ME_SET_SMS_NUM:{RESCUE_SECRET}:{payload}")
                elif action == "set_cloud_log": success = smart_send(f"FIND_ME_CLOUD_LOG:{RESCUE_SECRET}:{payload}")
                elif action == "upload_log": success = smart_send(f"FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}")
                elif action == "locate_once": success = smart_send(f"FIND_ME_LOCATE_ONCE:{RESCUE_SECRET}")
                elif action == "scan_wifi": success = smart_send(f"FIND_ME_SCAN_WIFI:{RESCUE_SECRET}")
                elif action == "restart_udp": success = smart_send(f"FIND_ME_RESTART_UDP:{RESCUE_SECRET}")
                elif action == "test_low_battery": success = smart_send(f"FIND_ME_TEST_LOW_BATTERY:{RESCUE_SECRET}")
                # ★ 2026-11-07 [P2]
                elif action == "elder_mode": success = smart_send(f"FIND_ME_ELDER_MODE:{RESCUE_SECRET}:{payload}")

                elif action == "clean_device":
                    target_dev_id = payload
                    if not isinstance(target_dev_id, str) or not target_dev_id.strip():
                        success = False; extra_msg = "无效的设备 ID"
                    else:
                        target_dev_id = target_dev_id.strip()
                        with state_lock:
                            existed = target_dev_id in ALL_DEVICES_STATUS
                            ALL_DEVICES_STATUS.pop(target_dev_id, None)
                        with _cmd_lock:
                            PENDING_COMMANDS.pop(target_dev_id, None)
                        success = existed
                        extra_msg = f"已删除 {target_dev_id}" if existed else f"设备 {target_dev_id} 不存在（可能已清理）"

                elif action == "clean_offline":
                    try:
                        now_ts = int(datetime.datetime.now().timestamp() * 1000)
                        to_delete = []
                        with state_lock:
                            for k, v in list(ALL_DEVICES_STATUS.items()):
                                if now_ts - v.get('received_at', 0) > CLEAN_OFFLINE_THRESHOLD_MS:
                                    to_delete.append(k)
                            for k in to_delete:
                                if k in ALL_DEVICES_STATUS:
                                    del ALL_DEVICES_STATUS[k]
                        with _cmd_lock:
                            for k in to_delete:
                                PENDING_COMMANDS.pop(k, None)
                        success = len(to_delete) > 0
                        threshold_h = CLEAN_OFFLINE_THRESHOLD_MS // 3600000
                        extra_msg = f"清理了 {len(to_delete)} 台设备（>{threshold_h}小时未活动）" if to_delete else f"没有符合条件的设备（>{threshold_h}小时未活动）"
                    except Exception as e:
                        success = False; extra_msg = f"清理失败: {e}"

                queued_count = len(target_devs) if action in HTTP_SUPPORTED_ACTIONS else 0
                # ★ FIX-7 2026-11-18：UDP 无送达确认，"包发出去了" != "设备执行了"。
                #   旧版只要 smart_send 返回真就报"指令发送成功"，且 queued_count>0 的
                #   排队提示因 success 恒真而永不显示。现在如实回传链路状态。
                if extra_msg:
                    resp = json.dumps({"status": "ok" if success else "error", "msg": extra_msg})
                elif queued_count > 0 and not success:
                    resp = json.dumps({"status": "ok", "msg": f"UDP 不可达，已排队 {queued_count} 条，等手机下次上报时下发"})
                elif queued_count > 0 and success:
                    resp = json.dumps({"status": "ok", "msg": f"✅ 已发出并排队 {queued_count} 条（UDP 无送达确认，设备离线时靠队列兜底）"})
                else:
                    resp = json.dumps({"status": "ok" if success else "error", "msg": "" if success else "指令执行失败"})
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ POST /api/control 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/history =====
        elif "GET /api/history" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                dev_id = params.get("device_id", [""])[0]
                conn = sqlite3.connect('track_history.db', timeout=10)
                # ★ 2026-11-07 修复：异常时也保证 conn.close()，防 fd 泄漏
                try:
                    c = conn.cursor()
                    c.execute("SELECT lat, lng, timestamp FROM history WHERE device_id=? ORDER BY timestamp DESC LIMIT 50", (dev_id,))
                    rows = c.fetchall()
                finally:
                    conn.close()
                resp = json.dumps(rows); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/history 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/messages =====
        elif "GET /api/messages" in header_text:
            try:
                conn = sqlite3.connect('track_history.db', timeout=10)
                # ★ 2026-11-07 修复：异常时也保证 conn.close()，防 fd 泄漏
                try:
                    c = conn.cursor()
                    c.execute("SELECT device_id, msg_time, message FROM messages ORDER BY timestamp DESC LIMIT 50")
                    rows = c.fetchall()
                finally:
                    conn.close()
                resp = json.dumps(rows); resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/messages 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== GET /api/chat_history =====
        elif "GET /api/chat_history" in header_text:
            try:
                with state_lock:
                    devices_snapshot = {k: dict(v) for k, v in ALL_DEVICES_STATUS.items()}
                    messages_snapshot = list(CHAT_MESSAGES)
                devices_list = []
                for dev_id, info in devices_snapshot.items():
                    ip = (info.get("source_ip") or info.get("udp_source_ip") or (info.get("network") or {}).get("local_ip"))
                    if ip and ip != '0.0.0.0':
                        devices_list.append({"id": dev_id, "ip": ip})
                data_to_send = {"messages": messages_snapshot, "devices": devices_list}
                with print_lock: resp = json.dumps(data_to_send)
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/chat_history 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass
                
        # ===== GET /health (健康检查) =====
        elif "GET /health" in header_text:
            try:
                resp = b'{"status":"ok"}'
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp)
            except Exception:
                pass

        # ===== GET / (Web 控制台) =====
        elif "GET /" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                if path != "/" and path != "/index.html" and not path.startswith("/?"):
                    client_conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return

                chat_options = '<label class="device-tag"><input type="checkbox" class="target-cb" value="all" checked><span>所有人 (群发)</span></label>'
                now_ms = int(datetime.datetime.now().timestamp() * 1000)

                html = """<!DOCTYPE html>
<html><head><meta charset='utf-8'>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>终端设备管理</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
:root {
    --color-canvas-default: #ffffff; --color-canvas-subtle: #f6f8fa;
    --color-border-default: #d0d7de; --color-border-muted: #d8dee4;
    --color-fg-default: #24292f; --color-fg-muted: #57606a;
    --color-accent-fg: #0969da; --color-btn-bg: #f6f8fa;
    --color-btn-primary-bg: #24292f; --color-btn-primary-hover-bg: #1b1f24;
}
* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; margin: 24px; background: var(--color-canvas-subtle); color: var(--color-fg-default); }
h2 { font-size: 20px; font-weight: 600; margin-bottom: 20px; }
.panel { background: var(--color-canvas-default); padding: 16px; border-radius: 6px; border: 1px solid var(--color-border-default); margin-bottom: 24px; }
button { padding: 5px 16px; font-size: 14px; font-weight: 500; line-height: 20px; white-space: nowrap; cursor: pointer; border-radius: 6px; border: 1px solid var(--color-border-default); background-color: var(--color-btn-bg); color: var(--color-fg-default); transition: 0.2s; }
button:hover { background-color: var(--color-border-muted); }
.btn-primary { background-color: var(--color-btn-primary-bg); color: #fff; border-color: var(--color-btn-primary-bg); }
.btn-primary:hover { background-color: var(--color-btn-primary-hover-bg); }
.btn-danger { background-color: #fff; color: #cf222e; border-color: var(--color-border-default); }
.btn-danger:hover { background-color: #cf222e; color: #fff; border-color: #cf222e; }
button.btn-sm { padding: 3px 12px; font-size: 12px; line-height: 18px; }
input[type="text"] { padding: 5px 12px; font-size: 14px; line-height: 20px; border: 1px solid var(--color-border-default); border-radius: 6px; transition: border-color 0.2s, box-shadow 0.2s; }
input[type="text"]:focus { border-color: var(--color-accent-fg); box-shadow: 0 0 0 3px rgba(9,105,218,0.3); }
.table-responsive { width: 100%; overflow-x: auto; background: var(--color-canvas-default); border-radius: 6px; border: 1px solid var(--color-border-default); }
table { border-collapse: collapse; width: 100%; min-width: 900px; }
th, td { border-bottom: 1px solid var(--color-border-muted); padding: 12px 16px; text-align: left; font-size: 14px; vertical-align: middle; }
th { background-color: var(--color-canvas-subtle); font-weight: 600; }
tr:last-child td { border-bottom: none; }
tr:hover { background-color: var(--color-canvas-subtle); }
a { color: var(--color-accent-fg); text-decoration: none; }
details { cursor: pointer; font-weight: 600; user-select: none; }
details summary::-webkit-details-marker { display: none; }
details summary { display: flex; align-items: center; }
details summary::before { content: '▶'; font-size: 10px; margin-right: 8px; transition: transform 0.2s; }
details[open] summary::before { transform: rotate(90deg); }
.btn-group { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.input-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 12px; }
.input-row input[type="text"] { flex: 1; min-width: 200px; }
#mapContainer { display: none; margin-top: 24px; }
#map { height: 500px; width: 100%; border-radius: 6px; border: 1px solid var(--color-border-default); min-height: 300px; }
#msgModal { display: none; position: fixed; z-index: 9999; left: 0; top: 0; width: 100%; height: 100%; background-color: rgba(0,0,0,0.5); }
#msgContent { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); background: var(--color-canvas-default); border-radius: 6px; border: 1px solid var(--color-border-default); width: 90%; max-width: 720px; max-height: 80%; overflow-y: auto; }
.modal-header { padding: 16px; border-bottom: 1px solid var(--color-border-muted); display: flex; justify-content: space-between; }
.modal-body { padding: 16px; line-height: 1.6; font-size: 14px; }
#toast { visibility: hidden; min-width: 250px; background-color: #24292f; color: #fff; text-align: center; border-radius: 6px; padding: 12px; position: fixed; z-index: 10000; left: 50%; bottom: 30px; transform: translateX(-50%); font-size: 14px; opacity: 0; transition: 0.3s; }
#toast.show { visibility: visible; opacity: 1; bottom: 50px; }

td button.btn-sm { display: inline-block; width: 32%; margin-bottom: 6px; margin-right: 1.5%; padding: 5px 0; text-align: center; box-sizing: border-box; font-size: 12px; white-space: nowrap; }
td button.btn-sm:nth-child(3n) { margin-right: 0; }

#chatBox { display: none; margin-top: 24px; background: #fff; border-radius: 6px; border: 1px solid var(--color-border-default); flex-direction: column; height: 450px; }
#chatMessages { flex: 1; overflow-y: auto; padding: 16px; font-size: 14px; line-height: 1.5; }
.chat-msg { margin-bottom: 8px; }
.chat-time { color: #8c959f; font-size: 12px; margin-right: 8px; }
.chat-sender { color: #0969da; font-weight: 600; margin-right: 8px; }
.chat-content { background: #f0f3f6; padding: 6px 12px; border-radius: 6px; display: inline-block; word-break: break-all; }
.chat-input-area { display: flex; padding: 12px; border-top: 1px solid var(--color-border-muted); background: #f6f8fa; gap: 8px; flex-wrap: wrap; }
.chat-input-area input { flex: 1; min-width: 200px; margin: 0; }
.chat-input-area button { margin: 0; }

/* ★ v2.2 远程陪伴面板：默认隐藏，由「👨‍👩‍👧 远程陪伴」按钮切换显示 */
#companionBox { display: none; margin-top: 24px; }

.device-selector { display: flex; flex-wrap: wrap; gap: 6px; padding: 12px; border-top: 1px solid var(--color-border-muted); background: #fff; }
.device-tag { position: relative; cursor: pointer; }
.device-tag input { position: absolute; opacity: 0; cursor: pointer; height: 0; width: 0; }
.device-tag span { display: inline-block; padding: 4px 10px; font-size: 12px; border-radius: 20px; border: 1px solid var(--color-border-default); background: #fff; color: var(--color-fg-default); transition: 0.2s; user-select: none; }
.device-tag input:checked ~ span { background: #24292f; color: #fff; border-color: #24292f; box-shadow: inset 0 1px 3px rgba(0,0,0,0.5); }

@media (max-width: 768px) {
    body { margin: 12px; }
    .input-row { flex-direction: column; align-items: stretch; }
    .input-row button { width: 100%; padding: 8px; margin-top: 4px; }
    .quick-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
    .quick-actions button { width: 100%; padding: 8px 4px; text-align: center; }
    .btn-group { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .btn-group button { width: 100%; text-align: center; padding: 8px 4px; font-size: 13px;}
    .hide-mobile { display: none !important; }
    .chat-input-area input { min-width: 100px; }
}
</style>
</head>
<body>
<h2>📱 终端设备状态同步中心</h2>
<div class="panel">
    <div class="input-row" style="margin-top: 0;">
        <input type="text" id="msgText" placeholder="输入要群发到所有手机屏幕上的文字...">
        <button class="btn-primary" onclick="sendScreenMsg('255.255.255.255')">发送群发提示</button>
    </div>
    <div class="quick-actions">
        <button class="btn-primary" onclick="sendCmd('force_report')">全局同步状态</button>
        <button onclick="showServerMessages()">查看云端留言</button>
        <button onclick="toggleChatBox()" style="color:#0969da; border-color:#0969da;">🗣️ 局域网对讲机</button>
        <!-- ★ v2.2 新增：远程陪伴按钮，与对讲机交互一致（点击展开/收起下方独立面板） -->
        <button onclick="toggleCompanionBox()" style="color:#0969da; border-color:#0969da;">👨‍👩‍👧 远程陪伴</button>
    </div>
    <details style="margin-top: 16px; border-top: 1px solid var(--color-border-muted); padding-top: 12px;">
        <summary>⚙️ 高级设置与系统工具</summary>
        <div class="btn-group" style="margin-top: 12px;">
            <button onclick="sendCmd('lock_chat')">🔒 锁定聊天</button>
            <button onclick="sendCmd('unlock_chat')">🔓 解锁聊天</button>
            <button onclick="sendCmd('set_cloud_log', '1')">☁️ 开启云端日志</button>
            <button onclick="sendCmd('set_cloud_log', '0')">☁️ 关闭云端日志</button>
            <button class="btn-danger" onclick="sendCmd('play_alarm')">全局测试音频</button>
            <button id="toggleMapBtn" onclick="toggleMap()">🗺️ 展开地图</button>
            <button onclick="showOfflineManager()">🗑️ 管理离线设备</button>
            <button onclick="sendCmd('clear_history')">清空手机历史</button>
            <button class="btn-danger" onclick="sendCmd('clear_chat')">🧹 清空手机聊天</button>
        </div>
        <div class="btn-group" style="margin-top: 12px;">
            <button onclick="sendCmd('upload_log')">📤 拉取日志(主服务器)</button>
            <button onclick="showLogs()">📋 查看手机日志</button>
            <button onclick="sendCmd('locate_once')">📍 手动定位一次</button>
            <button onclick="sendCmd('scan_wifi')">📡 手动扫描 WiFi</button>
            <button onclick="sendCmd('restart_udp')">🔄 重启 UDP 监听</button>
            <button class="btn-danger" onclick="sendCmd('test_low_battery')">🧪 模拟低电量</button>
        </div>
        <div class="input-row" style="margin-top: 12px;">
            <input type="text" id="smsNumText" placeholder="输入紧急接收短信的手机号...">
            <button onclick="sendSmsNum('255.255.255.255')">📱 设置报警号码</button>
        </div>
    </details>
</div>

<div id="chatBox">
    <div class="device-selector" id="deviceSelector">
        """ + chat_options + """
    </div>
    <div id="chatMessages"></div>
    <div class="chat-input-area">
        <input type="text" id="chatInput" placeholder="输入消息..." onkeypress="handleChatEnter(event)">
        <button class="btn-primary" onclick="sendChat()">发送</button>
    </div>
</div>

<!-- ★ v2.2 新增：远程陪伴面板独立容器，默认隐藏，与 #chatBox 平级 -->
<div id="companionBox">
    <div style="padding: 16px; background: #f0f7ff; border: 1px solid #0969da; border-radius: 6px;">
        <h3 style="margin-top: 0; color: #0969da; font-size: 16px;">👨‍👩‍👧 远程陪伴 (给老人推照片/视频)</h3>
        <!-- ★ 2026-11-18 补丁C：陪伴面板自带设备勾选区（原先勾选框只在"对讲机"面板里，推图时无处可选） -->
        <div class="device-selector" id="companionSelector"><label class="device-tag"><input type="checkbox" class="companion-cb" value="all" checked><span>所有人 (群发)</span></label></div>
        <div class="btn-group" style="margin-top: 8px;">
            <button class="btn-primary" onclick="sendCmd('companion_on')">🟢 开启陪伴模式</button>
            <button onclick="sendCmd('companion_off')">⚪ 关闭陪伴模式</button>
        </div>
        
        <!-- ★ v2.5 终极无痕镜像网关：一个可视输入框承载双模，外接幽灵隐藏文件选择器 -->
        <div class="input-row" style="margin-top: 12px; background: #fff; padding: 10px; border-radius: 6px;">
            <!-- 可视化伪装框：可显示文件名，也可直接粘贴 URL 外链 -->
            <input type="text" id="companionFileFake" placeholder="点击选择本地图片/视频，或直接在此粘贴网络 URL 链接..." style="flex: 1; color: #24292f;"
                   onclick="if(this.value.indexOf('http') !== 0) { document.getElementById('companionFileReal').click(); }"
                   onchange="if(this.value.indexOf('http') === 0) { this.style.color='#0969da'; } else { this.style.color='#24292f'; }">
            
            <!-- 幽灵隐藏真实选择器：完全不可见，只在后台提供本地文件字节流支持 -->
            <!-- ★ v2.7 完美镜像双模输入框：移除内部注释，规避 HTML 标签解析死锁 -->
<input type="file" id="companionFileReal" accept="image/*,video/*" style="display: none;" onchange="if(this.files && this.files.length > 0) { document.getElementById('companionFileFake').value = this.files[0].name; document.getElementById('companionFileFake').style.color='#24292f'; }">        
            <button class="btn-primary" onclick="uploadCompanionMedia()">🚀 推送到电视/手机</button>
        </div>

        <div style="color: var(--color-fg-muted); font-size: 12px; margin-top: 8px;">
            * 提示：推送前请先点击“开启陪伴模式”。选择照片或短视频后点击推送，老人屏幕会全屏展示。
        </div>
    </div>
</div>

<div class="table-responsive">
    <table>
        <tr>
            <th>设备ID</th>
            <th>系统 / 状态</th>
            <th>电量</th>
            <th>Wi-Fi / IP</th>
            <th>经纬度</th>
            <th class="hide-mobile">定位时间</th>
            <th>收到时间</th>
            <th class="hide-mobile">周围热点</th>
            <th style="min-width: 180px;">操作</th>
        </tr>
"""
                with state_lock:
                    devices_snapshot_html = [(k, dict(v)) for k, v in ALL_DEVICES_STATUS.items()]
                for dev_id, info in devices_snapshot_html:
                    try:
                        network = info.get("network") or {}
                        battery = info.get("battery") or {}
                        location = info.get("location") or {}
                        
                        os_ver = info.get('os_version', 'Unknown').replace("Android ", "A")
                        screen_on = "亮屏" if info.get('is_screen_on') else "息屏"

                        is_locked = info.get('chat_locked', False)
                        chat_lock_str = "<span style='color:#cf222e;'>🔒 已锁</span>" if is_locked else "<span style='color:#2da44e;'>🔓 未锁</span>"

                        last_seen = info.get('received_at', 0)
                        gap_min = (now_ms - last_seen) // 60000 if last_seen else 99999
                        if gap_min > 120:
                            offline_tag = f"<br><span style='color:#cf222e;font-weight:bold;'>🚨 失联 {gap_min // 60}h{gap_min % 60}m</span>"
                        elif gap_min > 30:
                            offline_tag = f"<br><span style='color:#bf8700;'>⏸ {gap_min} 分钟未上报</span>"
                        else:
                            offline_tag = ""

                        sys_status = f"{os_ver} <br><span style='font-size:12px;color:var(--color-fg-muted);'>{screen_on} | {chat_lock_str}</span>{offline_tag}"

                        lat = location.get('latitude')
                        lng = location.get('longitude')
                        age_s = location.get('age_s')
                        if lat is not None and lng is not None:
                            coord = f"<a href='https://uri.amap.com/marker?position={lng},{lat}' target='_blank'>{lat:.6f}, {lng:.6f}</a>"
                            if age_s is not None and age_s >= 0:
                                if age_s >= 1800:
                                    coord += f"<br><span style='color:#cf222e;font-size:12px;font-weight:bold;'>🚨 旧 {age_s // 60}min</span>"
                                elif age_s >= 300:
                                    coord += f"<br><span style='color:#bf8700;font-size:12px;'>⚠️ 旧 {age_s // 60}min</span>"
                                elif age_s >= 60:
                                    coord += f"<br><span style='color:#57606a;font-size:12px;'>旧 {age_s // 60}min</span>"
                        else:
                            coord = "<span style='color:var(--color-fg-muted);'>无定位</span>"

                        ssid = network.get('wifi_ssid', '?')
                        local_ip = network.get("local_ip", "0.0.0.0")
                        source_ip = (info.get("source_ip") or info.get("udp_source_ip") or local_ip)
                        
                        # ★ 修复：如果源IP是回环、代理或内网IP，不作为有效展示/控制IP
                        is_proxy_ip = False
                        if source_ip in ("127.0.0.1", "0.0.0.0") or source_ip.startswith("172.16.") or source_ip.startswith("172.17."):
                            is_proxy_ip = True
                            
                        if is_proxy_ip or source_ip == local_ip or not source_ip:
                            # 如果是代理IP，或者两者一致，直接展示手机自报的 local_ip
                            ip_display = f"<span title='PC 视角: {source_ip}' style='color:var(--color-fg-muted);'>{local_ip}</span>"
                        else:
                            ip_display = f"<span title='PC 视角: {source_ip}  |  手机自报: {local_ip}' style='color:#0969da;'>{source_ip} ⚠️</span>"
                            
                        wifi_info = f"{ssid}<br><span style='font-size:12px;'>{ip_display}</span>"

                        nearby = info.get('nearby_wifi') or []
                        ap_list_html = ""
                        if nearby:
                            ap_list_html = "<details><summary><b>" + str(len(nearby)) + " 个</b></summary><ul>"
                            for ap in nearby:
                                ap_list_html += f"<li>{ap.get('ssid')} ({ap.get('rssi')} dBm)</li>"
                            ap_list_html += "</ul></details>"
                        else:
                            ap_list_html = "<span style='color:var(--color-fg-muted);'>0 个</span>"

                        _bv_raw = str(info.get("build_version", "") or "旧版")
                        _pv_raw = str(info.get("protocol_version", 1) or 1)
                        _bv = html_lib.escape(_bv_raw[:32], quote=True)
                        _pv = html_lib.escape(_pv_raw[:4], quote=True)
                        # v2.0 补丁：通道健康度徽章 + dev_id 转义
                        _ch = info.get("channel") or {}
                        _badge = _channel_badge_html(_ch, gap_min)
                        html += (f"<tr><td><b>{html_lib.escape(dev_id, quote=True)}</b>"
                                 f"<br>{_badge}<span style='font-size:11px;color:#8c959f'>{_bv} · v{_pv}</span></td>")
                        html += f"<td>{sys_status}</td>"
                        html += f"<td>{battery.get('level', '?')}%</td>"
                        html += f"<td>{wifi_info}</td>"
                        html += f"<td>{coord}</td>"
                        html += f"<td class='hide-mobile'>{format_loc_time(location.get('time', 0))}</td>"
                        html += f"<td>{format_loc_time(info.get('received_at', 0))}</td>"
                        html += f"<td class='hide-mobile'>{ap_list_html}</td>"

                        html += f"<td>"
                        # ★ 修复：控制按钮永远优先使用手机真实的局域网 IP (local_ip)
                        btn_ip = local_ip if local_ip and local_ip != "0.0.0.0" else source_ip
                        html += f"<button class='btn-sm' onclick=\"sendCmd('force_report', '', '{btn_ip}')\">同步</button>"
                        html += f"<button class='btn-sm' onclick=\"sendScreenMsg('{btn_ip}')\">提示</button>"
                        html += f"<button class='btn-sm btn-danger' onclick=\"sendCmd('play_alarm', '', '{btn_ip}')\">响铃</button>"

                        if is_locked:
                            html += f"<button class='btn-sm btn-warning' onclick=\"sendCmd('unlock_chat', '', '{btn_ip}')\">🔓 解锁</button>"
                        else:
                            html += f"<button class='btn-sm' onclick=\"sendCmd('lock_chat', '', '{btn_ip}')\">🔒 锁定</button>"

                        html += f"<button class='btn-sm' onclick=\"showTrack('{dev_id}')\">轨迹</button>"
                        html += f"<button class='btn-sm' onclick=\"showCloudLogs('{dev_id}')\">📄 日志</button>"
                        html += f"</td></tr>"
                    except Exception as e:
                        with print_lock:
                            print(f"⚠️ [HTML] 渲染设备 {dev_id} 异常: {e}")
                        html += f"<tr><td colspan='9' style='color:#cf222e;'>设备 {dev_id} 数据异常，已跳过显示 ({e})</td></tr>"

                if not ALL_DEVICES_STATUS:
                    html += "<tr><td colspan='9' align='center' style='padding:40px;color:var(--color-fg-muted);'>暂无设备上报</td></tr>"

                html += """</table></div>
<div id="mapContainer">
    <div id="map"></div>
</div>
<div id="msgModal">
    <div id="msgContent">
        <div class="modal-header">
            <h3 id="modalTitle">📜 云端留言备份</h3>
            <span class="close-btn" style="cursor:pointer" onclick="document.getElementById('msgModal').style.display='none'">✖</span>
        </div>
        <div class="modal-body" id="msgList"></div>
    </div>
</div>
<div id="toast">提示信息</div>
<script>
var map = null;
var markersLayer = null;
var polyline = null;
var isMapVisible = false;
var isChatVisible = false;
var isCompanionVisible = false;  // ★ v2.2 远程陪伴面板展开状态
var _offlineDevices = [];
// ★ FIX-1：控制面鉴权令牌，由服务端渲染时替换 __ADMIN_TOKEN__ 注入
var ADMIN_TOKEN = "__ADMIN_TOKEN__";

var TIANDITU_KEY = "e669a4b8bc516a2d6e0104f746069b5a"; // 填入你的天地图 Key
var currentMapType = "tianditu";

var PI = 3.1415926535897932384626;
var _a = 6378245.0;
var _ee = 0.00669342162296594323;

function outOfChina(lng, lat) {
    return (lng < 72.004 || lng > 137.8347) || (lat < 0.8293 || lat > 55.8271);
}
function transformLat(lng, lat) {
    var ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
    ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
    ret += (20.0 * Math.sin(lat * PI) + 40.0 * Math.sin(lat / 3.0 * PI)) * 2.0 / 3.0;
    ret += (160.0 * Math.sin(lat / 12.0 * PI) + 320 * Math.sin(lat * PI / 30.0)) * 2.0 / 3.0;
    return ret;
}
function transformLng(lng, lat) {
    var ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
    ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
    ret += (20.0 * Math.sin(lng * PI) + 40.0 * Math.sin(lng / 3.0 * PI)) * 2.0 / 3.0;
    ret += (150.0 * Math.sin(lng / 12.0 * PI) + 300.0 * Math.sin(lng / 30.0 * PI)) * 2.0 / 3.0;
    return ret;
}
function wgs84togcj02(lng, lat) {
    if (outOfChina(lng, lat)) return [lng, lat];
    var dlat = transformLat(lng - 105.0, lat - 35.0);
    var dlng = transformLng(lng - 105.0, lat - 35.0);
    var radlat = lat / 180.0 * PI;
    var magic = Math.sin(radlat);
    magic = 1 - _ee * magic * magic;
    var sqrtmagic = Math.sqrt(magic);
    dlat = (dlat * 180.0) / ((_a * (1 - _ee)) / (magic * sqrtmagic) * PI);
    dlng = (dlng * 180.0) / (_a / sqrtmagic * Math.cos(radlat) * PI);
    return [lng + dlng, lat + dlat];
}

function initMap() {
    map = L.map('map', { center: [39.9, 116.4], zoom: 13, zoomControl: true });
    var layers = {};
    var defaultLayer = null;

    // 1. 天地图 (WGS-84，无偏移)
    if (TIANDITU_KEY) {
        var tdtUrl = function(layer) {
            return 'https://t{s}.tianditu.gov.cn/' + layer + '_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=' + layer + '&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=' + TIANDITU_KEY;
        };
        var tdtGroup = L.layerGroup([
            L.tileLayer(tdtUrl('vec'), { subdomains: '01234567', maxZoom: 18 }),
            L.tileLayer(tdtUrl('cva'), { subdomains: '01234567', maxZoom: 18 })
        ]);
        layers['1. 天地图（无偏移）'] = tdtGroup;
        defaultLayer = tdtGroup;
        currentMapType = "tianditu";
    }

    // 2. 高德地图 (GCJ-02，需自动纠偏)
    var amapVec = L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
        subdomains: '1234', 
        maxZoom: 18,
        attribution: '© 高德'
    });
    layers['2. 高德矢量（已纠偏）'] = amapVec;
    
    // 如果没填天地图 Key，默认使用高德（保证国内访问速度）
    if (!defaultLayer) {
        defaultLayer = amapVec;
        currentMapType = "amap";
    }

    // 3. 谷歌地图 (.com 国际版，WGS-84，无偏移，需网络支持)
    var googleVec = L.tileLayer('https://mt{s}.google.com/vt/lyrs=m&hl=zh-CN&gl=cn&x={x}&y={y}&z={z}', {
        subdomains: '0123', 
        maxZoom: 18,
        attribution: '© Google'
    });
    layers['3. 谷歌地图（无偏移）'] = googleVec;

    // 把默认图层加到地图上
    defaultLayer.addTo(map);

    // 右上角图层切换控件
    L.control.layers(layers, null, { position: 'topright', collapsed: false }).addTo(map);

    // 监听图层切换事件
    map.on('baselayerchange', function(e) {
        if (e.name.indexOf('高德') !== -1) {
            currentMapType = "amap";
        } else if (e.name.indexOf('谷歌') !== -1) {
            currentMapType = "google";
        } else {
            currentMapType = "tianditu";
        }
        
        // 切换底图时，自动重绘轨迹并根据新的底图类型重新计算坐标
        if (window._currentTrackData) {
            renderTrack(window._currentTrackData);
        }
    });

    markersLayer = L.layerGroup().addTo(map);
    setTimeout(function() { map.invalidateSize(); }, 100);
}

function toggleMap() {
    var container = document.getElementById('mapContainer');
    var btn = document.getElementById('toggleMapBtn');
    if (isMapVisible) {
        container.style.display = 'none';
        btn.innerHTML = '🗺️ 展开地图';
    } else {
        container.style.display = 'block';
        btn.innerHTML = '⬆️ 收起地图';
        if (!map) {
            requestAnimationFrame(function() { initMap(); });
        } else {
            setTimeout(function() { map.invalidateSize(); }, 100);
        }
    }
    isMapVisible = !isMapVisible;
}

function showToast(msg) {
    var toast = document.getElementById("toast");
    toast.innerText = msg;
    toast.className = "show";
    setTimeout(function(){ toast.className = toast.className.replace("show", ""); }, 3000);
}

function toggleChatBox() {
    var chatBox = document.getElementById('chatBox');
    isChatVisible = !isChatVisible;
    if (isChatVisible) {
        chatBox.style.display = 'flex';
        fetchChatHistory();
    } else {
        chatBox.style.display = 'none';
    }
}

// ★ v2.2 新增：远程陪伴面板展开/收起（与对讲机交互一致）
// ★ 2026-11-18 补丁C：设备列表独立拉取，不依赖"对讲机"面板是否展开（fetchChatHistory 未展开会直接 return）
function fetchDeviceTargets() {
    fetch('/api/chat_history')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        // ★ FIX-5：两个面板各自独立渲染，不再共用一份 tagHtml
        renderSelectors(data.devices || []);
    }).catch(function(e) {});
}

function toggleCompanionBox() {
    var box = document.getElementById('companionBox');
    isCompanionVisible = !isCompanionVisible;
    box.style.display = isCompanionVisible ? 'block' : 'none';
    if (isCompanionVisible) fetchDeviceTargets();
}

function handleChatEnter(e) { if (e.key === 'Enter') sendChat(); }

function sendChat() {
    var msg = document.getElementById('chatInput').value.trim();
    if(!msg) return;
    var selectedIps = readChecked(SEL_CHAT);
    if(selectedIps.length === 0) return showToast("请至少选择一个发送目标！");
    var targetParam = selectedIps.indexOf("all") !== -1 ? "all" : selectedIps;
    document.getElementById('chatInput').value = "";
    
    fetch('/api/control', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({token: ADMIN_TOKEN, action: 'chat_msg', payload: msg, target_ip: targetParam})
    }).then(function() {
        setTimeout(fetchChatHistory, 300);
    });
}

function fetchChatHistory() {
    if (!isChatVisible) return;
    fetch('/api/chat_history')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var chatDiv = document.getElementById('chatMessages');
        var html = "";
        data.messages.forEach(function(item) {
            var senderColor = item.sender.indexOf("Web_Console") !== -1 ? "#cf222e" : "#0969da";
            html += '<div class="chat-msg">' +
                '<span class="chat-time">' + escapeHtml(item.time) + '</span>' +
                '<span class="chat-sender" style="color:' + senderColor + '">[' + escapeHtml(item.sender) + ']</span>' +
                '<span class="chat-content">' + escapeHtml(item.content) + '</span>' +
            '</div>';
        });
        if (chatDiv.innerHTML !== html) {
            chatDiv.innerHTML = html;
            chatDiv.scrollTop = chatDiv.scrollHeight;
        }
        // ★ FIX-5：原来这里把同一份 tagHtml 同时写进 #deviceSelector 和 #companionSelector，
        //   两个面板的勾选态被强行同步；现在按各自 class 独立渲染。
        renderSelectors(data.devices || []);
    }).catch(function(e) {});
    if (isChatVisible) setTimeout(fetchChatHistory, 2000);
}

function showTrack(devId) {
    if (!isMapVisible) toggleMap();
    fetch('/api/history?device_id=' + encodeURIComponent(devId))
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.length === 0) return showToast("暂无该设备的轨迹数据");
        window._currentTrackData = data.reverse();
        setTimeout(function() {
            renderTrack(window._currentTrackData);
        }, 250);
    });
}

function renderTrack(data) {
    if (!map || !markersLayer) return;
    markersLayer.clearLayers();
    if (polyline) map.removeLayer(polyline);
    
    var latlngs = [];
    data.forEach(function(row, index) {
        var lat = row[0], lng = row[1];
        
        // 核心逻辑：只有高德地图需要 GCJ-02 纠偏；天地图和谷歌地图直接用原坐标
        if (currentMapType === "amap") {
            var converted = wgs84togcj02(lng, lat);
            lng = converted[0];
            lat = converted[1];
        }
        
        latlngs.push([lat, lng]);
        if (index === data.length - 1) {
            L.marker([lat, lng]).addTo(markersLayer).bindPopup("最新位置").openPopup();
            map.setView([lat, lng], 16);
        }
    });
    polyline = L.polyline(latlngs, {color: '#0969da', weight: 4}).addTo(map);
    map.invalidateSize();
}

function showCloudLogs(devId) {
    fetch('/api/cloud_logs?device_id=' + encodeURIComponent(devId))
    .then(function(res) { return res.json(); })
    .then(function(info) {
        var logs = (info && info.cloud_logs) ? info.cloud_logs : "该设备尚未上传日志。\\n请先在【高级设置】中开启「云端日志」，并在手机端等待下一次上报。";
        var logTime = (info && info.cloud_logs_time) ? new Date(info.cloud_logs_time).toLocaleString() : "未知";
        document.getElementById('modalTitle').innerText = "📄 运行日志 - " + devId;
        // ★ 修复 XSS 注入：对 logs 做实体转义
        var safeLogs = String(logs).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
        document.getElementById('msgList').innerHTML = 
            '<div style="color:#57606a; margin-bottom: 10px; font-size: 13px;">最近快照时间: ' + logTime + '</div>' +
            '<pre style="background: #f6f8fa; padding: 12px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; border: 1px solid #d0d7de; color: #24292f; max-height: 400px; overflow-y: auto;">' + safeLogs + '</pre>';
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取日志失败: " + e.message); });
}

function showLogs() {
    fetch('/api/logs')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var listDiv = document.getElementById('msgList');
        document.getElementById('modalTitle').innerText = "📋 手机上传日志列表";
        if (data.length === 0) {
            listDiv.innerHTML = "<p style='color:var(--color-fg-muted);'>还没有上传过日志。请点「📤 拉取日志」让手机上传。</p>";
        } else {
            var html = "";
            data.forEach(function(f) {
                var sizeKB = (f.size / 1024).toFixed(1);
                var time = new Date(f.mtime).toLocaleString();
                html += '<div style="border-bottom:1px solid var(--color-border-muted); padding:12px 0;">' +
                    '<div style="color:var(--color-fg-muted); font-size:12px; margin-bottom:6px;">' + time + ' | ' + sizeKB + 'KB</div>' +
                    '<a href="/api/download_log?name=' + encodeURIComponent(f.filename) + '" target="_blank" style="color:#0969da; font-weight:600;">' + f.filename + '</a>' +
                '</div>';
            });
            listDiv.innerHTML = html;
        }
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取日志列表失败: " + e.message); });
}

// ★ FIX-5 2026-11-18：对讲机面板与远程陪伴面板此前共用 class="target-cb"，
//   且 fetchDeviceTargets / fetchChatHistory 把同一份 tagHtml 写进两个容器，
//   导致"在陪伴面板只勾老人A"会同时改掉对讲机面板的勾选，反之亦然。
//   现在拆成两套 class + 两份独立选中态，各面板自渲染、自读取。
var SEL_CHAT = 'target-cb';       // 对讲机面板 #deviceSelector
var SEL_COMPANION = 'companion-cb'; // 远程陪伴面板 #companionSelector

function readChecked(cls) {
    var seen = {}, out = [];
    var cbs = document.querySelectorAll('.' + cls + ':checked');
    for (var i = 0; i < cbs.length; i++) {
        var v = cbs[i].value;
        if (!seen[v]) { seen[v] = 1; out.push(v); }
    }
    return out;
}

function buildSelectorHtml(devices, currentChecked, cls) {
    var tagHtml = '<label class="device-tag"><input type="checkbox" class="' + cls + '" value="all" ' + (currentChecked.indexOf('all') !== -1 || currentChecked.length === 0 ? 'checked' : '') + '><span>所有人 (群发)</span></label>';
    devices.forEach(function(dev) {
        var isChecked = currentChecked.indexOf(dev.ip) !== -1 ? 'checked' : '';
        tagHtml += '<label class="device-tag"><input type="checkbox" class="' + cls + '" value="' + escapeHtml(dev.ip) + '" ' + isChecked + '><span>' + escapeHtml(dev.id) + '</span></label>';
    });
    return tagHtml;
}

// 两个面板分别用自己的 class 读回选中态再各自重绘：
// 2 秒一轮的轮询只刷新"本面板自己"的勾选，不会把另一个面板的取消状态冲掉。
function renderSelectors(devices) {
    var el1 = document.getElementById('deviceSelector');
    if (el1) {
        var h1 = buildSelectorHtml(devices, readChecked(SEL_CHAT), SEL_CHAT);
        if (el1.innerHTML !== h1) el1.innerHTML = h1;
    }
    var el2 = document.getElementById('companionSelector');
    if (el2) {
        var h2 = buildSelectorHtml(devices, readChecked(SEL_COMPANION), SEL_COMPANION);
        if (el2.innerHTML !== h2) el2.innerHTML = h2;
    }
}

// ★ 2026-11-18 补丁C：统一从"设备勾选框"取目标（与对讲机 sendChat 复用同一套 selector）
function getSelectedTarget(cls) {
    var out = readChecked(cls || SEL_CHAT);
    if (out.length === 0) return null;
    return out.indexOf("all") !== -1 ? "all" : out;
}
// 推送类指令取目标；未勾选任何设备时回退全推（保持老行为，不会把功能改坏）
function mediaTarget() {
    var t = getSelectedTarget(SEL_COMPANION);
    return t ? t : "all";
}

function sendCmd(action, payload, target_ip) {
    if (payload === undefined) payload = "";
    if (target_ip === undefined) target_ip = mediaTarget();
    
    fetch('/api/control', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({token: ADMIN_TOKEN, action: action, payload: payload, target_ip: target_ip})
    })
    .then(function(res) {
        if (!res.ok) throw new Error('HTTP error! status: ' + res.status);
        return res.text();
    })
    .then(function(text) {
        var data = JSON.parse(text);
        if(data.status === 'ok') {
            var msg = data.msg || "✅ 指令发送成功";
            showToast(msg);
            if (action === 'clean_device' || action === 'clean_offline') {
                if (document.getElementById('msgModal').style.display === 'block') {
                    setTimeout(showOfflineManager, 300);
                }
            } else if (action !== "chat_msg" && action !== "upload_log") {
                setTimeout(function() { location.reload(); }, 1500);
            }
        } else {
            showToast("❌ 失败: " + (data.msg || "未知错误"));
        }
    }).catch(function(e) { showToast("❌ 请求失败: " + e.message); });
}

function sendScreenMsg(target_ip) {
    var msg = document.getElementById('msgText').value;
    if(!msg) { showToast("请先在输入框输入文字！"); return; }
    sendCmd('custom_msg', msg, target_ip);
}

function sendSmsNum(target_ip) {
    var num = document.getElementById('smsNumText').value.trim();
    if(!num) { showToast("请先在输入框输入手机号！"); return; }
    sendCmd('set_sms_num', num, target_ip);
}

function showServerMessages() {
    fetch('/api/messages')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var listDiv = document.getElementById('msgList');
        document.getElementById('modalTitle').innerText = "📜 云端留言备份";
        if(data.length === 0) {
            listDiv.innerHTML = "<p style='color:var(--color-fg-muted);'>服务器还没有记录哦~</p>";
        } else {
            var html = "";
            data.forEach(function(item) {
                html += '<div style="border-bottom:1px solid var(--color-border-muted); padding:12px 0;">' +
                            '<div style="color:var(--color-fg-muted); font-size:12px; margin-bottom:6px;">[' + item[1] + '] - ' + item[0] + '</div>' +
                            '<div>' + escapeHtml(item[2]) + '</div>' +
                         '</div>';
            });
            listDiv.innerHTML = html;
        }
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取云端数据失败: " + e.message); });
}

function showOfflineManager() {
    fetch('/api/cmd_status')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var threshold = 30 * 60;
        var offline = [];
        for (var devId in data) {
            var d = data[devId];
            var ago = d.last_report_ago_s;
            if (ago === -1 || ago > threshold) { offline.push({ id: devId, info: d }); }
        }
        if (offline.length === 0) { showToast("当前没有离线设备（>30 分钟未活动）"); return; }
        
        offline.sort(function(a, b) {
            var aa = a.info.last_report_ago_s === -1 ? 999999999 : a.info.last_report_ago_s;
            var bb = b.info.last_report_ago_s === -1 ? 999999999 : b.info.last_report_ago_s;
            return bb - aa;
        });
        _offlineDevices = offline;

        var html = '<div style="background:#fff8c5;border:1px solid #d4a72c;border-radius:6px;padding:10px 12px;margin-bottom:16px;font-size:13px;color:#6b5900;">' +
            '⚠️ <b>删除操作只从当前列表移除，不影响历史数据</b>（reports / history / messages 表全部保留）。<br>' +
            '手机重新上报后会自动回到列表。当前展示标准：<b>' + (threshold/60) + ' 分钟</b>未活动。' +
        '</div>';

        for (var i = 0; i < offline.length; i++) {
            var dev = offline[i];
            var info = dev.info;
            var ago = info.last_report_ago_s;
            var ago_str;
            if (ago === -1) ago_str = "从未上报";
            else if (ago > 86400) ago_str = Math.floor(ago/86400) + " 天前";
            else if (ago > 3600) ago_str = Math.floor(ago/3600) + " 小时前";
            else ago_str = Math.floor(ago/60) + " 分钟前";

            var ipStr = info.ip || "<span style='color:#8c959f;'>未知</span>";
            var battStr;
            if (info.battery === null || info.battery === undefined) { battStr = "<span style='color:#8c959f;'>未上报</span>"; }
            else { battStr = info.battery + "%" + (info.is_charging ? " ⚡" : ""); }
            
            var locStr;
            if (info.location && info.location.lat != null) {
                var loc = info.location;
                var ageTxt = loc.age_s != null ? ' (' + Math.floor(loc.age_s/60) + ' 分钟前)' : "";
                locStr = '<a href="https://uri.amap.com/marker?position=' + loc.lng + ',' + loc.lat + '" target="_blank" style="color:#0969da;">' + loc.lat.toFixed(5) + ', ' + loc.lng.toFixed(5) + '</a>' + ageTxt;
            } else { locStr = "<span style='color:#8c959f;'>无定位</span>"; }
            
            var wifiStr = info.wifi_ssid && info.wifi_ssid !== "UNKNOWN" ? info.wifi_ssid + " (" + (info.network_type || "?") + ")" : "<span style='color:#8c959f;'>无 WiFi</span>";
            var queueStr = info.pending > 0 ? '<span style="color:#bf8700;">📬 ' + info.pending + ' 条指令待执行</span>' : "";

            html += '<div style="border:1px solid var(--color-border-default);border-radius:6px;padding:12px 16px;margin-bottom:10px;background:#fafbfc;">' +
                '<div style="font-weight:600;font-size:15px;margin-bottom:6px;word-break:break-all;">' + escapeHtml(dev.id) + '</div>' +
                '<table style="font-size:13px;border:none;min-width:0;width:100%;">' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;width:80px;">最后活动</td><td style="padding:3px 0;border:none;">' + ago_str + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">上次 IP</td><td style="padding:3px 0;border:none;">' + ipStr + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">上次电量</td><td style="padding:3px 0;border:none;">' + battStr + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">上次定位</td><td style="padding:3px 0;border:none;">' + locStr + '</td></tr>' +
                    '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">WiFi</td><td style="padding:3px 0;border:none;">' + wifiStr + '</td></tr>' +
                    (queueStr ? '<tr><td style="padding:3px 8px 3px 0;color:#57606a;border:none;">队列</td><td style="padding:3px 0;border:none;">' + queueStr + '</td></tr>' : '') +
                '</table>' +
                '<div style="margin-top:10px;text-align:right;">' +
                    '<button class="btn-danger" onclick="cleanSingleDeviceByIndex(' + i + ')" style="padding:5px 16px;">🗑️ 删除此设备</button>' +
                '</div>' +
            '</div>';
        }

        html += '<div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--color-border-muted);text-align:right;">' +
            '<button class="btn-danger" onclick="confirmCleanAllOffline()" style="padding:6px 20px;">🗑️ 清理全部 >2h 离线设备</button>' +
        '</div>';

        document.getElementById('modalTitle').innerText = '🗑️ 管理离线设备（' + offline.length + ' 台）';
        document.getElementById('msgList').innerHTML = html;
        document.getElementById('msgModal').style.display = 'block';
    }).catch(function(e) { showToast("拉取设备状态失败: " + e.message); });
}

function cleanSingleDeviceByIndex(idx) {
    var dev = _offlineDevices[idx];
    if (!dev) return;
    var info = dev.info;
    var detailLines = ["设备ID: " + dev.id, "", "上次 IP: " + (info.ip || "未知")];
    if (info.battery !== null && info.battery !== undefined) detailLines.push("上次电量: " + info.battery + "%" + (info.is_charging ? " (充电中)" : ""));
    if (info.location && info.location.lat != null) detailLines.push("上次定位: " + info.location.lat.toFixed(5) + ", " + info.location.lng.toFixed(5));
    if (info.wifi_ssid && info.wifi_ssid !== "UNKNOWN") detailLines.push("WiFi: " + info.wifi_ssid);
    var ago = info.last_report_ago_s;
    if (ago === -1) detailLines.push("最后活动: 从未上报");
    else if (ago > 3600) detailLines.push("最后活动: " + Math.floor(ago/3600) + " 小时前");
    else detailLines.push("最后活动: " + Math.floor(ago/60) + " 分钟前");
    detailLines.push("", "⚠️ 只从列表移除，历史数据完整保留。", "手机重新上报后会自动回到列表。");

    if (!confirm(detailLines.join("\\n"))) return;
    sendCmd('clean_device', dev.id);
}

function confirmCleanAllOffline() {
    if (!confirm("确认清理所有【超过 2 小时未活动】的设备？\\n\\n注意：\\n- 只从列表移除，历史数据完整保留\\n- 30 分钟~2 小时之间的离线设备不会被清理\\n- 如需清理较新的离线设备，请用单设备删除")) return;
    sendCmd('clean_offline');
}

// ★ v2.1 新增：远程陪伴媒体上传（自动识别图片/视频）
// ★ v2.4 终端双模网关：零提示、全自动无痕分流转发
// ★ v2.5 镜像双通道路由：彻底解决服务端误加载与死锁卡死
// ★ v2.6 镜像五通道路由：零提示、智能识别（图/影/音/网/纯文本）无痕转发
function uploadCompanionMedia() {
    var fakeInput = document.getElementById('companionFileFake');
    var realInput = document.getElementById('companionFileReal');
    
    var inputVal = fakeInput.value ? fakeInput.value.trim() : "";
    
    // 智能断路器：严格判定当前框内是网络外链还是本地文件名
    var isRemoteUrl = inputVal.indexOf("http://") === 0 || inputVal.indexOf("https://") === 0;

    // 保底拦截：两路皆空，则判定为“纯文本大字报模式”
    var isPureText = !realInput.files.length && !isRemoteUrl && inputVal.length > 0;

    if (!realInput.files.length && !isRemoteUrl && !isPureText) { 
        showToast("请选择文件、粘贴 URL 外链，或直接输入纯文本大字报内容！"); 
        return; 
    }

    if (isPureText) {
        // ============================================================
        // 【通道 C】纯文本大字报 — 自动转换为标准的 text 标签指令发走
        // ============================================================
        showToast("正在通过云端路由直接同步大字报文本...");
        fetch('/api/control', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({token: ADMIN_TOKEN, action: 'custom_msg', payload: inputVal, target_ip: mediaTarget()})
        })
        .then(function(res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        }).then(function(data) {
            if (data.status === 'ok') {
                showToast("🚀 " + (data.msg || "纯文本大字报指令已无痕同步！"));
                fakeInput.value = ""; realInput.value = "";
            } else {
                // ★ FIX-4 2026-11-18：旧版只有 ok 分支、整条 Promise 链无 .catch，
                //   status=error 或 HTTP 5xx 时点了毫无反应 —— 这才是"大字报失效"的真实高频原因。
                showToast("❌ 大字报下发失败: " + (data.msg || "未知错误"));
            }
        }).catch(function(err) { showToast("❌ 大字报请求异常: " + err.message); });
    }

    if (isRemoteUrl) {
        // ============================================================
        // 【通道 B】网络外链直推逻辑 — 智能感知图/影/音/网，彻底阻断文件上传
        // ============================================================
        var mediaUrl = inputVal;
        var lowerUrl = mediaUrl.toLowerCase();
        
        var action = "push_photo"; // 默认归图
        
        // 视频流特征识别（含直播源 m3u8）
        var isVideo = lowerUrl.indexOf('.mp4') !== -1 || lowerUrl.indexOf('.webm') !== -1 || 
                      lowerUrl.indexOf('.m3u8') !== -1 || lowerUrl.indexOf('live') !== -1 || lowerUrl.indexOf('stream') !== -1;
                      
        // 🎵 音频流特征识别（网络电台、相声、MP3 戏曲）
        var isAudio = lowerUrl.indexOf('.mp3') !== -1 || lowerUrl.indexOf('.wma') !== -1 || 
                      lowerUrl.indexOf('.aac') !== -1 || lowerUrl.indexOf('audio') !== -1 || lowerUrl.indexOf('fm') !== -1;
        
        if (isVideo) { action = 'push_video'; }
        else if (isAudio) { action = 'push_audio'; } // ★ v2.6 新增音频信令
        else if (lowerUrl.indexOf('.jpg') === -1 && lowerUrl.indexOf('.png') === -1 && lowerUrl.indexOf('.gif') === -1 && lowerUrl.indexOf('.jpeg') === -1) {
            // ★ v2.6 新增全屏网页 H5 路由：既不是视频、不是音频、不是常见图片，直接划归为网页挂载
            action = 'push_web'; 
        }
        
        showToast("正在通过智能网关进行外链分流推送 [" + action + "]...");
        
        fetch('/api/control', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({token: ADMIN_TOKEN, action: action, payload: mediaUrl, target_ip: mediaTarget()})
        })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.status === 'ok') {
                if (!isCompanionVisible) toggleCompanionBox();
                showToast("🚀 外链直推指令已成功同步！");
                fakeInput.value = ""; realInput.value = "";
                fakeInput.type = "text";
            } else {
                showToast("❌ 外链推送失败: " + (data.msg || "未知错误"));
            }
        })
        .catch(function(err) { showToast("❌ 请求异常: " + err.message); });

    } else {
        // ============================================================
        // 【通道 A】原生本地文件流通道 — 保持高保真转存
        // ============================================================
        var file = realInput.files[0];
        // ★ 2026-11-18 修复：部分浏览器/微信内核 file.type 为空串，会把视频误判成图片；补后缀判型
        var fileName = (file.name || '').toLowerCase();
        var isVideo = (file.type || '').indexOf('video') === 0 ||
                      /\\.(mp4|webm|mkv|mov|avi|m3u8)$/.test(fileName);
        var isAudio = (file.type || '').indexOf('audio') === 0 ||
                      /\\.(mp3|aac|m4a|wav|ogg|wma)$/.test(fileName);
        var action = isVideo ? 'push_video' : (isAudio ? 'push_audio' : 'push_photo');

        // ★ 2026-11-18 提示：HEIC/RAW 等手机私有格式安卓无法解码，推送前就给家属明确反馈
        if (/\\.(heic|heif|dng|cr2|nef|arw)$/.test(fileName)) {
            showToast("❌ " + file.name + " 是 iPhone/相机原始格式，手机端无法解码，请先转成 JPG 再推");
            return;
        }

        showToast("正在上传本地媒体文件至服务器，请稍候...");
        
        fetch('/api/upload_media?filename=' + encodeURIComponent(file.name), {
            method: 'POST',
            body: file
        })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.status === 'ok') {
                // ★ 2026-11-18 修复：URL 主机若是回环/局域网探测失败地址，手机必然取不到图，当场告警
                if (data.url && (data.url.indexOf('http://127.') === 0 || data.url.indexOf('http://localhost') === 0 || data.url.indexOf('http://0.0.0.0') === 0 || data.url.indexOf('http://172.17.') === 0)) {
                    showToast("⚠️ 生成的地址 " + data.url + " 手机大概率访问不到，请用局域网 IP 打开控制台后重试");
                }
                showToast("✅ 服务器转存完毕，正在同步下发信令...");
                return fetch('/api/control', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({token: ADMIN_TOKEN, action: action, payload: data.url, target_ip: mediaTarget()})
                });
            } else {
                throw new Error(data.msg || "服务器本地存储失败");
            }
        })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.status === 'ok') {
                if (!isCompanionVisible) toggleCompanionBox();
                showToast("🚀 媒体同步指令已下发！");
                fakeInput.value = ""; realInput.value = "";
                fakeInput.type = "text";
            } else {
                showToast("❌ 推送失败: " + (data.msg || "未知错误"));
            }
        })
        .catch(function(err) { showToast("❌ 发生故障: " + err.message); });
    }
}



function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
        return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
}
</script>
</body></html>"""
                # ★ FIX-1：把服务端令牌注入前端（用 replace 而非 f-string，避免 HTML 里大量 {} 冲突）
                html = html.replace("__ADMIN_TOKEN__", html_lib.escape(AUTH_TOKEN, quote=True))
                html_bytes = html.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(html_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + html_bytes)

            except Exception as e:
                with print_lock:
                    print(f"❌ GET / (Web控制台) 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nWeb console generation error.")
                except Exception: pass

        else:
            client_conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

    except Exception as e:
        with print_lock:
            print(f"❌ handle_client 顶层异常: {e}")
    finally:
        try: client_conn.close()
        except Exception: pass




def health_server():
    """独立端口 8081，不受业务线程池影响。"""
    import socket as _s
    hs = _s.socket(_s.AF_INET, _s.SOCK_STREAM)
    hs.setsockopt(_s.SOL_SOCKET, _s.SO_REUSEADDR, 1)
    try:
        hs.bind(('0.0.0.0', 8081))
    except Exception as e:
        with print_lock:
            print(f"⚠️ health 端口 8081 绑定失败: {e}")
        return
    hs.listen(16)
    with print_lock:
        print("💚 health 独立端口已启动: http://0.0.0.0:8081/health")
    while True:
        conn = None
        try:
            conn, _ = hs.accept()
            conn.settimeout(2.0)
            try: conn.recv(2048)
            except Exception: pass
            body = b'{"status":"ok","port":8081}'
            conn.sendall(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: '
                         + str(len(body)).encode() + b'\r\nConnection: close\r\n\r\n' + body)
        except Exception:
            pass
        finally:
            if conn:
                try: conn.close()
                except Exception: pass


def start_server():
    threading.Thread(target=health_server, daemon=True).start()
    threading.Thread(target=udp_listener, daemon=True).start()
    threading.Thread(target=offline_detector, daemon=True).start()
    threading.Thread(target=cmd_cleaner, daemon=True).start()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: server.bind(('0.0.0.0', PORT))
    except Exception as e:
        print(f"❌ 端口 {PORT} 绑定失败: {e}")
        return

    server.listen(50)
    with print_lock:
        print("=" * 50)
        print(f"[SV] server2.py {BUILD_VERSION} (协议 v{PROTOCOL_VERSION})")
        print(f"🚀 设备同步服务已启动: http://{get_local_ip()}:{PORT}")
        print(f"📁 日志上传目录: {os.path.abspath(UPLOAD_LOG_DIR)}")
        print(f"📁 媒体上传目录: {os.path.abspath(UPLOAD_MEDIA_DIR)}")
        print(f"🚨 失联检测: 每 5 分钟扫描一次，>2 小时未上报告警")
        print(f"📋 HTTP 下行队列: TTL={CMD_TTL_SECONDS}s, 上限={CMD_MAX_PER_DEVICE}/设备")
        if USING_DEFAULT_CREDENTIALS:
            print("🚨 [安全] 仍在使用出厂默认口令 your_private_secret_key！"
                  "请设置环境变量 DF_AUTH_TOKEN（可选 DF_RESCUE_SECRET）后重启服务。")
        if not CONTROL_AUTH_ENABLED:
            print("🚨 [安全] 控制面鉴权已关闭(DF_CONTROL_AUTH=0)：/api/control 任何人可调用，切勿暴露公网。")
        print(f"🗑️  离线设备清理: 弹窗展示>30分钟未活动, 批量清理阈值={CLEAN_OFFLINE_THRESHOLD_MS // 3600000}小时")
        try:
            conn_chk = sqlite3.connect('track_history.db', timeout=5)
            report_cnt = conn_chk.execute("SELECT COUNT(*) FROM reports").fetchone()[0]
            dev_cnt = conn_chk.execute("SELECT COUNT(DISTINCT device_id) FROM reports").fetchone()[0]
            db_size = os.path.getsize('track_history.db') / (1024 * 1024)
            conn_chk.close()
            print(f"📊 上报数据库: {report_cnt} 条 / {dev_cnt} 台设备 / {db_size:.1f}MB")
            print(f"   保留策略: raw_json={REPORTS_KEEP_RAW_DAYS}天, nearby={REPORTS_KEEP_NEARBY_DAYS}天")
        except Exception:
            print("📊 上报数据库: 首次启动，等待上报")
        print("=" * 50)

    # ★★★ 核心补丁：创建最大 15 个工作线程的线程池 2026-11-05 13:50 ★★★ 
    # 当手机请求过来时，最多同时处理 15 个，超出的会排队，防止内存被瞬间榨干
    # 有界队列：>50 排队就直接 503，防 fd 泄漏
    global _BOUNDED_SEM
    _BOUNDED_SEM = threading.BoundedSemaphore(50)
    executor = ThreadPoolExecutor(max_workers=15)

    while True:
        try:
            conn, addr = server.accept()
            if not _BOUNDED_SEM.acquire(blocking=False):
                try:
                    conn.sendall(b'HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                except Exception: pass
                try: conn.close()
                except Exception: pass
                continue
            def _run(c, a):
                try: handle_client(c, a)
                finally: _BOUNDED_SEM.release()
            try:
                executor.submit(_run, conn, addr)
            except Exception:
                # 如果 submit 失败，必须把名额释放掉并关掉连接
                _BOUNDED_SEM.release()
                try: conn.close()
                except Exception: pass
        except KeyboardInterrupt: 
            break
        except Exception as e:
            print(f"accept 异常: {e}")
            time.sleep(1)


if __name__ == "__main__":
    start_server()
