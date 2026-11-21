"""
============================================================
server2.py — 全功能同步服务（v2 协议稳定版）
------------------------------------------------------------
构建批次 : 20261122-P2P-r3
协议版本 : v2-only / v2.7 LAN P2P peer discovery and per-peer ACK
最后修改 : 2026-11-22（v2-only 信令；消息 ACK 闭环；跨进程重启持久化去重）
------------------------------------------------------------
本轮改动 : v2-only；custom_msg 仅发 FIND_ME_FLOAT，chat_msg 仅发带 message_id 的 FIND_ME_CHAT；响应 Android peer challenge 与消息 ACK；每次进程启动生成唯一会话 peer ID。
改动记录（倒序，仅列最近 3 条，更早见 CHANGELOG.txt）:
  2026-11-21 09:30  v2.5.13-r1 重建：服务端严格以 v2.5.12 为基线，增加网络前缀/接口/传输状态落库、command_feedback 持久化与幂等处理；修正 Cloudflare HTTP source_ip 不得作为设备 UDP 目标；兼容旧 executed_ids。
  2026-11-21 09:15  v2.5.12 设备管理整合：统一离线阈值并支持持久化自定义；设备列表增加在线/离线/异常筛选；在线/离线设备均可删除；待执行指令数直接跳转到该设备队列；删除前阻止遗留待确认指令；时间格式人类可读；保留最后有效 IP 与定位采集时间；增加删除审计。
  2026-11-21 00:45  v2.5.11 管理台细节优化：①设备维护卡片自动收起无操作项的空卡片，按钮统一网格对齐；②二级导航去除左侧竖线并提高导航字号；③云端留言与指令队列改为页面内展示，不再弹出通用模态框；④地图保留原始默认中心与缩放，进入地图不自动跳转设备位置，悬浮定位按钮仅在用户点击时定位到最近上报设备，手机端同步显示；⑤不修改 API、数据库及 Android 协议
  2026-11-21 02:30  v2.5.10 地图与页面视觉整理：①实时地图增加设备最新上报位置圆点标记与悬浮定位按钮，定位到最近上报设备；②地图显示全部有有效坐标设备并支持点击查看设备/定位时间；③陪护、对讲、地图、定位、留言和队列页面统一中性卡片样式，弱化彩色描边与高饱和按钮；④移除页面内装饰性 emoji 图标，保留导航与必要状态信息；⑤不使用管理端浏览器 GPS，不改变设备定位授权、API、数据库及 Android 协议
  2026-11-21 01:00  v2.5.9 手机端导航重构：①手机端顶部精简栏+侧滑抽屉，取消常驻整块导航；②增加底部常用入口；③抽屉遮罩、ESC/菜单点击关闭与页面切换自动关闭；④桌面端维持左侧导航；⑤保留原页面、业务函数、API、数据库与 Android 协议
  2026-11-21 00:05  v2.5.7 管理台二级导航：①对讲机、远程陪护、云端留言、指令队列拆为独立工作页；②实时地图与定位管理分开；③定位/维护页共享设备范围选择器；④切换页面自动收起非当前沟通面板，复用原 API 和业务函数
  2026-11-20 23:30  v2.5.5 管理台结构升级：①借鉴 OpenWrt LuCI 分区导航，增加左侧主导航与独立工作区；②仪表盘、设备管理、陪护沟通、设备维护、地图分区；③运动健康增加明确的规划占位页，不启动采集；④保留原有 DOM、按钮函数、API 与 v2 协议，采用前端渐进式重组
  2026-11-20 23:00  v2.5.4 前端脚本解析修复：①修正媒体 URL 协议正则中的斜杠转义；②修正媒体文件扩展名正则中的点号转义；③修正域名路径正则；④避免单个正则语法错误导致整段脚本中止、按钮函数全部未定义
  2026-11-20 22:30  v2.5.3 管理台统一操作框架：①高级设置新增独立设备范围选择器，不再借用陪伴面板勾选状态；
                    ②新增 sendAdminCmd 统一入口，单设备/多设备共用参数与结果处理；③通用 sendCmd 默认目标改为显式全局，陪伴操作明确使用陪伴选择器；
                    ④设备选择器初次加载与刷新独立渲染，保留对讲机/陪伴原有选择状态；⑤保留原有 API、协议与业务按钮
  2026-11-20 22:00  v2.5.2 P0/P1 稳定性加固：①[P0] reports/history 落库成功后才确认 accepted，重复 queue_id 按已接收处理；
                    ②[P0] HTTP 下行指令写入 SQLite，服务重启后恢复未过期队列；③[P0] HTTP 头/请求体长度校验、拒绝不支持的 chunked；
                    ④[P1] report_batch 强制单设备归属，避免跨设备混批和指令 ACK 错位；⑤[P1] reports + history 同事务提交；
                    ⑥[P1] health 增加 DB/UDP 状态，迁移与索引错误可见
  2026-11-20 21:10  v2.5.1 一致性修复：①[致命] POST /api/report_batch 处理完穿透再执行一遍 POST /api/report
                    （补 return + if→elif，消除子串抢匹配）；②[C1] history 表时间基准两端统一为客户端 timestamp；
                    ③[B2] reports 新增 queue_id 列 + history 四元组判重，按客户端 _queue_id 做补发幂等去重；
                    ④[低危2] _queue_id 不再泄漏进 raw_json / ext_json
  2026-11-20 13:40  v2.5.0 闭环增强：新增 POST /api/report_batch 批量上报落库；新增「📬 指令队列」查看/单条撤回/清空；新增 GET /api/companion_status 陪伴状态与历史明细查询
  2026-11-19 23:15  v2.4.4 健壮落库：纠正 POST /api/upload_media 降级防御中未定义变量的引用，掐断偶发 500 引发的线程憋死通道
  2026-11-19 23:05  v2.4.3 流网关升级：uploadCompanionMedia 挂载多路强感知断路器，支持域名与原生流媒体协议(FLV/rtmp/rtsp等)自适应补全
  2026-11-19 22:30  v2.4.1 嗅探闭环：MIME_BY_EXT 拓宽多媒体映射，强力并入 sniff_media_ext 物理魔数校验，阻断因伪造后缀导致手机原生解码闪退
  2026-11-19 21:55  v2.4.0 终极IP中枢：_detect_local_ip 统一字符串返回，引入公网优先级探测与 LOCAL 上一行物理 IP 提取，完美适配 Win/Armbian/Termux 物理断网
  2026-11-19 21:47  v2.3.0 性能解锁：解开写锁内不可重入的锁嵌套；撤销高并发下高负载的内聚 VACUUM 动作，断绝 I/O 阻塞
  2026-11-19 21:30  v2.2.2 并发加固：save_report/save_history 与 POST /api/message 统一并入全局物理写锁防止死锁
  2026-11-19 21:15  v2.2.1 口令隔离：彻底拆分设备口令与控制面鉴权变量，新增 DF_CONTROL_TOKEN，完美保持旧 APK 兼容性
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
import uuid
import sqlite3
import gzip
import os
import re
import html as html_lib  # ★ 2026-11-07 [P4.7 XSS]
import faulthandler
import signal
import ipaddress
from urllib.parse import urlparse, parse_qs
# ★ 新增ThreadPoolExecutor 补丁： 2026-11-05 13:50
from concurrent.futures import ThreadPoolExecutor

"""
============================================================
DeviceFinder server2.py — 全功能同步服务（终极稳健版）
============================================================
版本发布 : 2026-11-20
核心架构 : 原生 Socket + 多线程 (ThreadPoolExecutor)  |  本轮复核: 2026-11-19 server2_fixed_4
============================================================

【一、核心功能概览】
- reports 表完整持久化（含扩展字段 ext_json、SIM 状态等）
- HTTP 下行队列（pending_commands 双通道下发）
- UDP 监听 + 聊天室去重
- 设备管理（统一在线/离线/异常视图、可配置离线阈值、单台/批量删除与审计）
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
MANAGE_OFFLINE_THRESHOLD_S = CLEAN_OFFLINE_THRESHOLD_MS // 1000  # 设备管理统一离线阈值：2 小时

PORT = 8080
# ★ 2026-11-20 [P0] HTTP 请求边界：请求头与请求体分别限额，避免慢速/超大请求长期占用 worker。
HTTP_MAX_HEADER_BYTES = 64 * 1024
HTTP_MAX_BODY_BYTES = 32 * 1024 * 1024  # 媒体上传仍可用，统一上限 32MB
UDP_LISTENING = False
# ★★ FIX-1B 2026-11-19：把"设备口令"与"控制口令"彻底拆开。
#   FIX-1 的坑：DF_AUTH_TOKEN 一个变量同时管三处——
#     ① 设备上报（/api/report、/api/message、/api/upload_log）
#     ② UDP 信令里的 RESCUE_SECRET（手机端逐包校验第二段，不对就丢包）
#     ③ 新增的控制面鉴权（/api/control）
#   现场存量 APK 把出厂值 your_private_secret_key 硬编码在包里，而服务端 config 下发
#   字段里根本没有 token（只有 report_interval_seconds / accuracy_mode / next_server_url
#   / next_config_url / hide_icon），即不存在"把新口令推给手机"的通道。
#   所以 ①② 一旦改动 = 全部旧设备瞬间 403 + UDP 信令静默失效（就是你遇到的现象）。
#   真正该轮换的只有 ③ —— 本行给它新增独立变量 DF_CONTROL_TOKEN。
DEFAULT_DEVICE_TOKEN = "your_private_secret_key"
# ① 设备侧 HTTP 上报口令：请保持出厂值，除非你已把所有手机升到新 APK
AUTH_TOKEN = (os.environ.get("DF_DEVICE_TOKEN") or os.environ.get("DF_AUTH_TOKEN")
              or DEFAULT_DEVICE_TOKEN)
# ② UDP 密钥：不再继承 AUTH_TOKEN，只认 DF_RESCUE_SECRET，默认出厂值
RESCUE_SECRET = os.environ.get("DF_RESCUE_SECRET") or DEFAULT_DEVICE_TOKEN
# ③ 控制面孔令：唯一应该设置的变量。未设置时回退到设备口令（兼容旧部署，行为不变）
CONTROL_TOKEN = os.environ.get("DF_CONTROL_TOKEN") or AUTH_TOKEN
CONTROL_TOKEN_FROM_ENV = bool(os.environ.get("DF_CONTROL_TOKEN"))
# 控制面鉴权开关，默认开启；DF_CONTROL_AUTH=0 仅作紧急回退用
CONTROL_AUTH_ENABLED = (os.environ.get("DF_CONTROL_AUTH", "1") != "0")
# 可选：/api/control 来源 IP 前缀白名单（逗号分隔，如 "192.168.,10.0."），空=不限。
#   设备口令是写进源码的公开值，这道防线才真正把控制面从"整个内网"收到"管理员那台机"。
CONTROL_IP_ALLOWLIST = [x.strip() for x in os.environ.get("DF_CONTROL_ALLOW_IPS", "").split(",") if x.strip()]
# 告警口径改针对"控制面孔令"，不再诱导你去改设备口令
USING_DEFAULT_CREDENTIALS = (CONTROL_TOKEN == DEFAULT_DEVICE_TOKEN)
DEVICE_TOKEN_IS_DEFAULT = (AUTH_TOKEN == DEFAULT_DEVICE_TOKEN and RESCUE_SECRET == DEFAULT_DEVICE_TOKEN)

def _claim_chat_message(sender, message_id):
    """跨进程/重启的消息幂等；仅在持久化成功（新消息或重复消息）后确认。"""
    global CHAT_DEDUP_OPS
    key = str(message_id or "")
    if not key:
        return True
    now = time.time()
    try:
        with CHAT_DEDUP_LOCK:
            conn = sqlite3.connect(CHAT_DEDUP_DB, timeout=3)
            try:
                conn.execute("PRAGMA busy_timeout=3000")
                conn.execute("""CREATE TABLE IF NOT EXISTS received_chat_ids (
                    sender TEXT NOT NULL,
                    message_key TEXT NOT NULL,
                    received_at REAL NOT NULL,
                    PRIMARY KEY(sender, message_key)
                )""")
                cur = conn.execute(
                    "INSERT OR IGNORE INTO received_chat_ids(sender,message_key,received_at) VALUES(?,?,?)",
                    (str(sender), key, now))
                CHAT_DEDUP_OPS += 1
                if CHAT_DEDUP_OPS % 128 == 0:
                    conn.execute("DELETE FROM received_chat_ids WHERE received_at < ?", (now - 7 * 86400,))
                    conn.execute("""DELETE FROM received_chat_ids
                        WHERE rowid IN (SELECT rowid FROM received_chat_ids
                        ORDER BY received_at DESC LIMIT -1 OFFSET 10000)""")
                conn.commit()
                return cur.rowcount == 1
            finally:
                conn.close()
    except Exception as e:
        # 持久化失败不能确认新消息已可靠接收；返回 None 让接收端暂不 ACK。
        with print_lock:
            print(f"⚠️ 聊天持久化去重失败，本条消息暂不确认: {type(e).__name__}")
        return None

# ===== 版本 =====
BUILD_VERSION = "20261122-1200-r3"
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
CHAT_DEDUP_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_dedup.sqlite3")
CHAT_DEDUP_LOCK = threading.Lock()
CHAT_DEDUP_OPS = 0

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
    ("network_subnet_prefix",  "TEXT"),
    ("network_interface",      "TEXT"),
    ("network_transport",      "TEXT"),
    ("network_validated",      "INTEGER"),
    ("network_changed_at",     "INTEGER"),
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
    ("push_web_url",           "TEXT"),    # 当前全屏常驻的网页/H5 看板 URL 地址

    # ★ 2026-11-20 [B2/低危2] 新增：客户端离线队列幂等键（原混在 ext_json / raw_json 里白占截断预算，
    #   现独立成列并建索引，用于「整批重发」场景的前向去重）。init_db 会自动 ALTER TABLE 补列。
    ("queue_id",               "TEXT")
]

# ★ 修复：补上缺失的 REPORTS_INDEXES 定义（原代码在 init_db 中遍历它却从未定义，导致启动即 NameError）
REPORTS_INDEXES = [
    ("idx_reports_device_ts", "ON reports (device_id, timestamp)"),
    ("idx_reports_timestamp", "ON reports (timestamp)"),
    ("idx_reports_day_str",   "ON reports (day_str)"),
    ("idx_reports_queue_id",  "ON reports (device_id, queue_id)"),   # ★ 2026-11-20 [B2/P1] 设备维度幂等查重用
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
    # ★ 2026-11-20 [B2] 配套 save_history 幂等查重的索引，避免去重 SELECT 退化为全表扫描
    # ★ 2026-11-20 [P1] 索引创建失败不能静默吞掉：启动日志必须暴露数据库结构问题。
    c.execute("CREATE INDEX IF NOT EXISTS idx_history_device_ts ON history (device_id, timestamp)")
    c.execute('''CREATE TABLE IF NOT EXISTS messages
                 (device_id TEXT, msg_time TEXT, message TEXT, timestamp INTEGER)''')
    # ★ 2026-11-20 [P0] HTTP 下行指令持久化：进程重启后恢复未过期、未确认的指令。
    c.execute('''CREATE TABLE IF NOT EXISTS pending_commands
                 (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, action TEXT NOT NULL,
                  payload TEXT, created_at REAL NOT NULL)''')
    c.execute("CREATE INDEX IF NOT EXISTS idx_pending_commands_device ON pending_commands (device_id, created_at)")
    # ★ v2.5.13：设备执行反馈独立持久化；command_id + device_id 联合幂等，兼容旧版 executed_ids。
    c.execute('''CREATE TABLE IF NOT EXISTS command_feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT NOT NULL, device_id TEXT NOT NULL, action TEXT, status TEXT NOT NULL, detail TEXT, created_at REAL NOT NULL, updated_at REAL NOT NULL, UNIQUE(command_id, device_id))''')
    c.execute("CREATE INDEX IF NOT EXISTS idx_command_feedback_device_time ON command_feedback (device_id, updated_at DESC)")
    c.execute('''CREATE TABLE IF NOT EXISTS app_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)''')
    c.execute('''CREATE TABLE IF NOT EXISTS device_delete_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, action TEXT NOT NULL, scope TEXT NOT NULL, actor_ip TEXT, created_at INTEGER NOT NULL, result TEXT NOT NULL)''')
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
        except Exception as e:
            print(f"❌ [reports] 索引 {idx_name} 创建失败: {e}")
            raise
    # ★ 2026-11-20 [P1] 旧版 idx_reports_queue_id 只有 queue_id 单列；升级为设备+幂等键联合索引。
    try:
        queue_index_cols = [row[2] for row in c.execute("PRAGMA index_info(idx_reports_queue_id)").fetchall()]
        if queue_index_cols != ["device_id", "queue_id"]:
            c.execute("DROP INDEX IF EXISTS idx_reports_queue_id")
            c.execute("CREATE INDEX idx_reports_queue_id ON reports (device_id, queue_id)")
    except Exception as e:
        print(f"❌ [reports] queue_id 联合索引迁移失败: {e}")
        raise
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


def _load_offline_threshold():
    global CLEAN_OFFLINE_THRESHOLD_MS, MANAGE_OFFLINE_THRESHOLD_S
    try:
        conn = sqlite3.connect('track_history.db', timeout=10)
        row = conn.execute("SELECT value FROM app_settings WHERE key='offline_threshold_s'").fetchone()
        conn.close()
        if row:
            seconds = int(row[0])
            if 300 <= seconds <= 604800:
                MANAGE_OFFLINE_THRESHOLD_S = seconds
                CLEAN_OFFLINE_THRESHOLD_MS = seconds * 1000
    except Exception as e:
        print(f"⚠️ [配置] 读取离线阈值失败，继续使用默认值: {e}")


def _set_offline_threshold(seconds):
    global CLEAN_OFFLINE_THRESHOLD_MS, MANAGE_OFFLINE_THRESHOLD_S
    try:
        seconds = int(seconds)
    except (TypeError, ValueError):
        return False
    if seconds < 300 or seconds > 604800:
        return False
    with db_write_lock:
        conn = sqlite3.connect('track_history.db', timeout=10)
        try:
            conn.execute("INSERT INTO app_settings(key,value) VALUES('offline_threshold_s',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (str(seconds),))
            conn.commit()
        finally:
            conn.close()
    MANAGE_OFFLINE_THRESHOLD_S = seconds
    CLEAN_OFFLINE_THRESHOLD_MS = seconds * 1000
    return True


def _record_device_delete_audit(dev_id, action, actor_ip, result):
    try:
        with db_write_lock:
            conn = sqlite3.connect('track_history.db', timeout=10)
            try:
                conn.execute("INSERT INTO device_delete_audit(device_id,action,scope,actor_ip,created_at,result) VALUES(?,?,?,?,?,?)",
                             (str(dev_id), str(action), "device_list_only", str(actor_ip or ""), int(time.time() * 1000), str(result)))
                conn.commit()
            finally:
                conn.close()
    except Exception as e:
        with print_lock:
            print(f"⚠️ [设备删除审计] 写入失败 {dev_id}: {e}")


_load_offline_threshold()


# ==================== save_report ====================

def _insert_history_with_conn(conn, dev_id, lat, lng, ts):
    """★ 2026-11-20 [P1] 在当前 reports 事务内写入轨迹点，避免两张表一成一败。"""
    if lat is None or lng is None:
        return
    dup = conn.execute(
        "SELECT 1 FROM history WHERE device_id=? AND timestamp=? AND lat=? AND lng=? LIMIT 1",
        (dev_id, ts, lat, lng)).fetchone()
    if not dup:
        conn.execute("INSERT INTO history (device_id, lat, lng, timestamp) VALUES (?, ?, ?, ?)",
                      (dev_id, lat, lng, ts))


def save_report(dev_id, data, source_ip):
    """完整上报落库；True=新写入或幂等重复（均可确认），False=确实未落库。"""
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
        network_subnet_prefix = network.get("subnet_prefix") or network.get("network_subnet_prefix") or None
        network_interface = network.get("interface_name") or network.get("network_interface") or None
        network_transport = network.get("transport") or network.get("network_transport") or None
        network_validated = _parse_bool_val(network.get("validated"))
        network_changed_at = network.get("changed_at")
        if isinstance(network_changed_at, bool) or not isinstance(network_changed_at, int) or network_changed_at <= 0:
            network_changed_at = None
        has_internet = _parse_bool_val(network.get("has_internet"))
        # 只接受格式正确的 IPv4 CIDR；不将未经验证的字符串用于路由计算。
        try:
            if network_subnet_prefix:
                network_subnet_prefix = str(ipaddress.ip_network(str(network_subnet_prefix), strict=False))
                if ipaddress.ip_network(network_subnet_prefix).version != 4:
                    network_subnet_prefix = None
        except Exception:
            network_subnet_prefix = None
        if not isinstance(network_interface, str): network_interface = None
        else: network_interface = network_interface[:64]
        if not isinstance(network_transport, str): network_transport = None
        else: network_transport = network_transport[:32]
        if isinstance(net_subtype, str): net_subtype = net_subtype[:64]
        else: net_subtype = None

        reported_net_type = network.get("network_type") or network.get("transport")
        if isinstance(reported_net_type, str) and reported_net_type.upper() in ("WIFI", "MOBILE", "ETHERNET", "OTHER", "OFFLINE"):
            net_type = reported_net_type.upper()
        elif wifi_ssid and wifi_ssid not in ("UNKNOWN", "OFF", "<unknown ssid>"):
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

        # ★ 2026-11-20 [低危2] _queue_id 是客户端离线队列的本地字段，只用于补发幂等去重，
        #   不再随 raw_json 落库（旧版把它当业务字段存进原始列，白占截断预算）
        queue_id = data.get("_queue_id")
        if not isinstance(queue_id, str) or not queue_id.strip():
            queue_id = None
        else:
            queue_id = queue_id.strip()[:64]

        raw_src = {k: v for k, v in data.items() if k != "_queue_id"} if queue_id else data
        raw_json = None
        try: raw_json = json.dumps(raw_src, ensure_ascii=False)[:REPORTS_RAW_MAX_BYTES]
        except Exception: pass

        # 1) declared_keys 增加 5 个陪伴字段，避免重复存入 ext_json
        declared_keys = {
            "device_id", "timestamp", "received_at", "battery", "network",
            "location", "nearby_wifi", "recent_locations", "is_screen_on",
            "chat_locked", "elder_mode", "os_version", "uptime_ms",
            "sim_state", "sim_operator", "ringer_mode", "volume_alarm",
            "unlock_count_since_boot", "is_screen_locked", "airplane_mode",
            "token", "last_executed_cmd_ids", "_queue_id",  # ★ 2026-11-20 [低危2] 本地幂等键不入 ext_json
            "companion_enabled", "push_photo_url", "push_video_url",
            "push_audio_url", "push_web_url"
        }
        # 放在 save_report() 构造各字段的位置（如 os_version / uptime_ms 附近）
        companion_enabled = _parse_bool_val(data.get("companion_enabled"))

        def _clean_url_str(v):
            if isinstance(v, str):
                s = v.strip()
                return s if s else None
            return None

        push_photo_url = _clean_url_str(data.get("push_photo_url"))
        push_video_url = _clean_url_str(data.get("push_video_url"))
        push_audio_url = _clean_url_str(data.get("push_audio_url"))
        push_web_url   = _clean_url_str(data.get("push_web_url"))
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
                # ★ 2026-11-20 [B2] 幂等去重：客户端批量补发在「服务端已入库但响应超时 / JSON 不可解析」
                #   时会把整批再发一遍（postReportBatchDirectly 返回 accepted=0 → 逐条降级重发），
                #   reports 此前没有任何幂等键 → 同一条记录重复入库。现按客户端入队时注入的
                #   _queue_id 前向查重，命中即跳过写入（对客户端仍按 accepted 确认，语义不变）。
                dup = None
                if queue_id:
                    dup = conn.execute("SELECT 1 FROM reports WHERE device_id=? AND queue_id=? LIMIT 1",
                                       (dev_id, queue_id)).fetchone()
                if dup:
                    # 幂等重复仍属于“服务端已接收”，并顺手补齐旧版本可能缺失的 history 点。
                    if lat is not None and lng is not None:
                        _insert_history_with_conn(conn, dev_id, lat, lng, resolve_history_ts(data, received_at))
                    conn.commit()
                    with print_lock:
                        print(f"♻️ [reports] 幂等命中，确认已接收 {dev_id} queue_id={queue_id}")
                    return True
                # 2) INSERT 语句补齐 5 列与 5 个 ?
                conn.execute("""INSERT INTO reports
                    (device_id, timestamp, received_at, day_str, hour, weekday,
                     battery_level, is_charging, battery_temp_c, battery_voltage_mv,
                     wifi_ssid, wifi_bssid, wifi_rssi, local_ip, source_ip,
                     network_type, network_subtype, network_subnet_prefix, network_interface,
                     network_transport, network_validated, network_changed_at, has_internet,
                     sim_state, sim_operator,
                     is_screen_on, is_screen_locked, airplane_mode,
                     ringer_mode, volume_alarm, unlock_count_since_boot,
                     chat_locked, elder_mode, os_version, uptime_ms,
                     lat, lng, accuracy, location_provider, location_time, location_age_s,
                     recent_locations_json,
                     nearby_wifi_count, nearby_wifi_json, raw_json, ext_json,
                     build_version, protocol_version,
                     companion_enabled, push_photo_url, push_video_url, push_audio_url, push_web_url,
                     queue_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", (
                    dev_id, ts, received_at, dt.strftime("%Y-%m-%d"), dt.hour, dt.weekday(),
                    batt_level, is_charging, batt_temp, batt_volt,
                    wifi_ssid or None, wifi_bssid, wifi_rssi, local_ip, source_ip,
                    net_type, net_subtype, network_subnet_prefix, network_interface,
                    network_transport, network_validated, network_changed_at, has_internet,
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
                    companion_enabled, push_photo_url, push_video_url, push_audio_url, push_web_url,
                    queue_id,
                ))
                # ★ 2026-11-20 [P1] reports 与 history 同一连接、同一事务提交；任一失败整体回滚。
                if lat is not None and lng is not None:
                    _insert_history_with_conn(conn, dev_id, lat, lng, resolve_history_ts(data, received_at))
                conn.commit()
                return True   # ★ 2026-11-20 [P0] 明确返回“已接收”，只有 commit 成功才允许 ACK
            finally:
                conn.close()
    except Exception as e:
        with print_lock:
            print(f"⚠️ [reports] 写入失败 {dev_id}: {e}")
        return False  # ★ 2026-11-20 [P0] 失败必须向 HTTP 层传递，禁止假成功


def cleanup_old_reports():
    """
    终极最优版数据库分阶段清理与自愈
    随用随释，安全收拢写锁，新增物理瘦身，彻底阻止 reports 表膨胀引起的写入缓慢。
    2026-11-19 21:47
    """
    try:
        now_ms = int(datetime.datetime.now().timestamp() * 1000)
        cleared_raw = cleared_nearby = 0
        
        # 🌟 核心安全设计：写锁完全内聚，timeout 设为 30 秒，防止边缘异常死等
        with db_write_lock:
            conn = sqlite3.connect('track_history.db', timeout=30)
            try:
                conn.execute("PRAGMA busy_timeout=30000")
                c = conn.cursor()
                
                # 1. 阶段清理原始报文
                if REPORTS_KEEP_RAW_DAYS > 0:
                    cutoff = now_ms - REPORTS_KEEP_RAW_DAYS * 86400 * 1000
                    c.execute("UPDATE reports SET raw_json=NULL WHERE timestamp < ? AND raw_json IS NOT NULL", (cutoff,))
                    cleared_raw = c.rowcount
                    
                # 2. 阶段清理热点报文
                if REPORTS_KEEP_NEARBY_DAYS > 0:
                    cutoff = now_ms - REPORTS_KEEP_NEARBY_DAYS * 86400 * 1000
                    c.execute("UPDATE reports SET nearby_wifi_json=NULL WHERE timestamp < ? AND nearby_wifi_json IS NOT NULL", (cutoff,))
                    cleared_nearby = c.rowcount
                conn.commit()

                # ★ FIX-A 2026-11-19：撤销"写锁内 VACUUM"。
                #   实测（176MB / 3万行 reports）：VACUUM 单次 13.7~16.1 秒，且全程持有
                #   db_write_lock；期间 save_report / save_history / POST /api/message
                #   全部排在同一把锁后面，3 个并发上报实测等待 65.8s / 71.7s / 77.5s。
                #   本服务是 ThreadPoolExecutor(max_workers=15) + BoundedSemaphore(50)，
                #   几个这样的请求就能把 worker 占满 -> 控制台与手机端上报一起卡死/503。
                #   而 UPDATE ... SET NULL 已把页面还给 freelist（实测 freelist=30000 页），
                #   后续 INSERT 会直接复用，只是文件不缩；磁盘回收请放到进程外做：
                #     0 4 * * 0 sqlite3 track_history.db "VACUUM;"   # 每周日凌晨，低峰期
                #   另注：VACUUM 不会"释放磁盘 fd"，它只是重建库文件。
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
    """v2-only：协议版本仅用于诊断，不再选择旧版信令。"""
    with state_lock:
        info = ALL_DEVICES_STATUS.get(dev_id, {})
        v = info.get("protocol_version")
        return int(v) if isinstance(v, (int, str)) and str(v).isdigit() else 1


def _build_version_aware_commands(target_devs, action_name, payload):
    """v2-only 信令生成：不再依据设备版本双发旧协议。"""
    if action_name == "custom_msg":
        return [f"FIND_ME_FLOAT:{RESCUE_SECRET}:{payload}"]
    if action_name == "chat_msg":
        return [f"FIND_ME_CHAT:{RESCUE_SECRET}:Web_Console:{uuid.uuid4()}:{payload}"]
    return []


# ==================== HTTP 下行队列 ====================

# ★ 2026-11-20 [P0] 指令队列持久化辅助函数。调用方持有 _cmd_lock，内部再取得 db_write_lock。
def _persist_command_queue_locked(dev_id):
    queue = PENDING_COMMANDS.get(dev_id, [])
    with db_write_lock:
        conn = sqlite3.connect('track_history.db', timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            conn.execute("DELETE FROM pending_commands WHERE device_id=?", (dev_id,))
            if queue:
                conn.executemany(
                    "INSERT INTO pending_commands (id, device_id, action, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                    [(c["id"], dev_id, c["type"], json.dumps(c.get("payload", ""), ensure_ascii=False), float(c["ts"])) for c in queue])
            conn.commit()
        finally:
            conn.close()


def _load_pending_commands():
    """启动时恢复未过期指令；过期记录同时从持久化表清理。"""
    now = time.time()
    loaded = {}
    with db_write_lock:
        conn = sqlite3.connect('track_history.db', timeout=10)
        try:
            rows = conn.execute("SELECT id, device_id, action, payload, created_at FROM pending_commands ORDER BY created_at, id").fetchall()
            expired_ids = []
            for cmd_id, dev_id, action, payload, created_at in rows:
                if now - float(created_at) >= cmd_ttl_for(action):
                    expired_ids.append((cmd_id,))
                    continue
                try:
                    restored_payload = json.loads(payload) if payload is not None else ""
                except Exception:
                    restored_payload = payload or ""
                loaded.setdefault(dev_id, []).append({"id": cmd_id, "type": action,
                                                       "payload": restored_payload, "ts": float(created_at)})
            if expired_ids:
                conn.executemany("DELETE FROM pending_commands WHERE id=?", expired_ids)
                conn.commit()
        finally:
            conn.close()
    with _cmd_lock:
        PENDING_COMMANDS.clear()
        PENDING_COMMANDS.update(loaded)
    total = sum(len(q) for q in loaded.values())
    if total:
        with print_lock:
            print(f"♻️ [HTTP队列] 已从数据库恢复 {total} 条待执行指令")


def enqueue_command(dev_id, action, payload, require_device=False):
    global CMD_SEQ
    if not dev_id: return
    now = time.time()
    payload_str = payload if payload is not None else ""
    with _cmd_lock:
        if require_device:
            # 与设备删除路径采用相同锁顺序（cmd -> state），避免删除后再产生兜底指令。
            with state_lock:
                if dev_id not in ALL_DEVICES_STATUS:
                    return False
        queue = PENDING_COMMANDS.setdefault(dev_id, [])
        for i, c in enumerate(queue):
            if c["type"] == action and c["payload"] == payload_str:
                queue[i] = {"id": c["id"], "type": action, "payload": payload_str, "ts": now}
                _persist_command_queue_locked(dev_id)
                with print_lock:
                    print(f"📋 [HTTP队列] {dev_id} ~{action}({c['id']}) 去重（延后过期）")
                return True
        CMD_SEQ += 1
        cmd_id = f"c_{int(now)}_{CMD_SEQ}"
        dropped = 0
        while len(queue) >= CMD_MAX_PER_DEVICE:
            queue.pop(0); dropped += 1
        queue.append({"id": cmd_id, "type": action, "payload": payload_str, "ts": now})
        _persist_command_queue_locked(dev_id)
    with print_lock:
        if dropped:
            print(f"📋 [HTTP队列] {dev_id} +{action}({cmd_id}) 队列满丢弃 {dropped} 条旧指令")
        else:
            print(f"📋 [HTTP队列] {dev_id} +{action}({cmd_id})")
    return True


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
        _persist_command_queue_locked(dev_id)
    if removed > 0:
        with print_lock:
            print(f"✅ [HTTP队列] {dev_id} 已确认执行 {removed} 条")


def process_command_feedback(dev_id, feedback_items):
    """保存设备执行反馈；accepted/uncertain/最终结果状态可结束重复下发，写入反馈表后从待执行队列移除。

    accepted 表示设备端处理入口已接受，不等同于异步媒体/定位任务已最终完成；
    后续可由更细粒度的 completed 反馈扩展。旧版客户端仍走 executed_ids。
    """
    if not isinstance(feedback_items, list) or not feedback_items:
        return 0
    accepted_statuses = {"received", "executing", "retry_wait", "accepted", "uncertain", "succeeded", "failed"}
    # accepted/uncertain 可结束重复下发，但后续异步回调仍可把状态细化为 succeeded/failed。
    # succeeded/failed 才是最终结果，终态之间不允许被迟到的旧反馈互相覆盖。
    terminal_statuses = {"accepted", "uncertain", "succeeded", "failed"}
    valid = []
    now = time.time()
    for item in feedback_items[:100]:
        if not isinstance(item, dict):
            continue
        command_id = item.get("command_id") or item.get("id")
        status = item.get("status")
        if not isinstance(command_id, str) or not command_id or len(command_id) > 128:
            continue
        if status not in accepted_statuses:
            continue
        action = item.get("action") if isinstance(item.get("action"), str) else None
        detail = item.get("detail") if isinstance(item.get("detail"), str) else ""
        valid.append((command_id, dev_id, action, status, detail[:500], now, now))
    if not valid:
        return 0
    # 先持久化反馈，再清理队列；任何 DB 失败都保留原队列等待下次上报。
    with db_write_lock:
        conn = sqlite3.connect('track_history.db', timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            conn.executemany("""INSERT INTO command_feedback
                (command_id, device_id, action, status, detail, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(command_id, device_id) DO UPDATE SET
                  action=COALESCE(excluded.action, command_feedback.action),
                  status=CASE
                    WHEN command_feedback.status IN ('succeeded','failed') THEN command_feedback.status
                    WHEN command_feedback.status IN ('accepted','uncertain') AND excluded.status IN ('received','executing','retry_wait','accepted','uncertain')
                      THEN command_feedback.status
                    ELSE excluded.status END,
                  detail=CASE
                    WHEN command_feedback.status IN ('succeeded','failed') THEN command_feedback.detail
                    WHEN command_feedback.status IN ('accepted','uncertain') AND excluded.status IN ('received','executing','retry_wait','accepted','uncertain')
                      THEN command_feedback.detail
                    ELSE excluded.detail END,
                  updated_at=CASE
                    WHEN command_feedback.status IN ('succeeded','failed') THEN command_feedback.updated_at
                    WHEN command_feedback.status IN ('accepted','uncertain') AND excluded.status IN ('received','executing','retry_wait','accepted','uncertain')
                      THEN command_feedback.updated_at
                    ELSE excluded.updated_at END""", valid)
            conn.commit()
        finally:
            conn.close()
    terminal_ids = {row[0] for row in valid if row[3] in terminal_statuses}
    if terminal_ids:
        with _cmd_lock:
            queue = PENDING_COMMANDS.get(dev_id, [])
            new_queue = [c for c in queue if c.get("id") not in terminal_ids]
            if len(new_queue) != len(queue):
                if new_queue: PENDING_COMMANDS[dev_id] = new_queue
                else: PENDING_COMMANDS.pop(dev_id, None)
                _persist_command_queue_locked(dev_id)
    return len(valid)


def get_recent_command_feedback(dev_id, limit=20):
    try:
        with db_write_lock:
            conn = sqlite3.connect('track_history.db', timeout=5)
            try:
                rows = conn.execute("""SELECT command_id, action, status, detail, updated_at
                    FROM command_feedback WHERE device_id=? ORDER BY updated_at DESC LIMIT ?""",
                    (dev_id, max(1, min(int(limit), 50)))).fetchall()
            finally:
                conn.close()
        return [{"id": r[0], "type": r[1] or "", "status": r[2], "detail": r[3] or "",
                 "updated_at": r[4]} for r in rows]
    except Exception as e:
        with print_lock: print(f"⚠️ [指令反馈] 查询失败 {dev_id}: {e}")
        return []


def get_pending_commands(dev_id):
    now = time.time()
    with _cmd_lock:
        queue = PENDING_COMMANDS.get(dev_id, [])
        if not queue: return []
        valid = [c for c in queue if now - c["ts"] < cmd_ttl_for(c.get("type", ""))]
        if len(valid) != len(queue):
            if valid: PENDING_COMMANDS[dev_id] = valid
            else: PENDING_COMMANDS.pop(dev_id, None)
            _persist_command_queue_locked(dev_id)
        return [dict(c) for c in valid]


def _delete_device_if_queue_empty(dev_id):
    """在队列锁内检查并删除设备列表记录，避免检查队列后又并发入队。"""
    now = time.time()
    with _cmd_lock:
        queue = PENDING_COMMANDS.get(dev_id, [])
        valid = [c for c in queue if now - c["ts"] < cmd_ttl_for(c.get("type", ""))]
        if len(valid) != len(queue):
            if valid: PENDING_COMMANDS[dev_id] = valid
            else: PENDING_COMMANDS.pop(dev_id, None)
            _persist_command_queue_locked(dev_id)
        if valid:
            return False, len(valid)
        with state_lock:
            existed = dev_id in ALL_DEVICES_STATUS
            ALL_DEVICES_STATUS.pop(dev_id, None)
        return existed, 0


def cleanup_expired_commands():
    now = time.time()
    with _cmd_lock:
        for dev_id in list(PENDING_COMMANDS.keys()):
            queue = PENDING_COMMANDS.get(dev_id, [])
            valid = [c for c in queue if now - c["ts"] < cmd_ttl_for(c.get("type", ""))]
            if valid: PENDING_COMMANDS[dev_id] = valid
            else: PENDING_COMMANDS.pop(dev_id, None)
            _persist_command_queue_locked(dev_id)

def clear_pending_commands(dev_id=None):
    """清空指定设备或全部设备的 HTTP 待执行指令队列，返回移除条数"""
    removed = 0
    with _cmd_lock:
        if dev_id:
            q = PENDING_COMMANDS.pop(dev_id, [])
            removed = len(q)
            _persist_command_queue_locked(dev_id)
        else:
            dev_ids = list(PENDING_COMMANDS.keys())
            for q in PENDING_COMMANDS.values():
                removed += len(q)
            PENDING_COMMANDS.clear()
            for queued_dev in dev_ids:
                _persist_command_queue_locked(queued_dev)
    return removed


def remove_command_by_id(dev_id, cmd_id):
    """按 cmd_id 撤回单条待发指令，返回是否成功移除"""
    if not dev_id or not cmd_id:
        return False
    with _cmd_lock:
        q = PENDING_COMMANDS.get(dev_id)
        if not q:
            return False
        new_q = [c for c in q if c.get("id") != cmd_id]
        if len(new_q) == len(q):
            return False
        if new_q:
            PENDING_COMMANDS[dev_id] = new_q
        else:
            PENDING_COMMANDS.pop(dev_id, None)
        _persist_command_queue_locked(dev_id)
        return True

# ★ 2026-11-20 [P0] 模块初始化完成后恢复磁盘队列；启动时 init_db 已先创建表。
_load_pending_commands()


def cmd_cleaner():
    """
    终极最优版定时清理器。
    内存与数据库函数各自内聚加锁，外部 0 锁重叠，彻底免疫多线程 AB-BA 环路死锁。
    2026-11-19 21:48
    """
    while True:
        try:
            time.sleep(3600)  # 每小时执行一次
            
            # 1. 纯内存队列剔除（内部自带 _cmd_lock 极短锁，执行时间微秒级，安全）
            cleanup_expired_commands()
            
            # 2. 数据库安全阶段清理（内部内聚 db_write_lock，不产生外面重复包裹的死锁硬伤）
            cleanup_old_reports()
            
        except Exception as e:
            with print_lock:
                print(f"⚠️ 清理异常: {e}")


# ==================== UDP 监听 ====================

def udp_listener():
    global UDP_LISTENING
    while True:
        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            udp_sock.bind(('0.0.0.0', 8888))
            UDP_LISTENING = True
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
                # v2-only：只接收 FIND_ME_CHAT
                if msg.startswith("FIND_ME_PEER_HELLO:"):
                    parts = msg.split(":", 3)
                    if len(parts) == 4 and parts[1] == RESCUE_SECRET:
                        response = f"FIND_ME_PEER_ACK:{RESCUE_SECRET}:{parts[3]}:{PEER_NODE_ID}".encode("utf-8")
                        try: udp_sock.sendto(response, addr)
                        except OSError: pass
                elif msg.startswith("FIND_ME_CHAT:"):
                    parts = msg.split(":", 4)
                    if len(parts) == 5 and parts[1] == RESCUE_SECRET:
                        sender = parts[2]
                        message_id = parts[3]
                        content = parts[4]
                        if sender in ["Web_Console", "PC_Terminal"]:
                            if message_id:
                                ack = f"FIND_ME_CHAT_ACK:{RESCUE_SECRET}:{message_id}:{PEER_NODE_ID}".encode("utf-8")
                                try: udp_sock.sendto(ack, addr)
                                except OSError: pass
                            continue
                        claim_result = _claim_chat_message(sender, message_id or content)
                        if claim_result is None:
                            continue  # 持久化失败：不 ACK，允许发送端重试
                        if message_id:
                            ack = f"FIND_ME_CHAT_ACK:{RESCUE_SECRET}:{message_id}:{PEER_NODE_ID}".encode("utf-8")
                            try: udp_sock.sendto(ack, addr)
                            except OSError: pass
                        if not claim_result:
                            continue  # 重复消息仍 ACK，但不重复展示
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
            UDP_LISTENING = False
            try: udp_sock.close()
            except Exception: pass


def offline_detector():
    """
    失联检测：每 5 分钟扫描一次，超过统一可配置阈值未上报告警并排队兜底指令。

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

                if gap_min * 60 > MANAGE_OFFLINE_THRESHOLD_S and not already_alerted:
                    should_alert = False
                    with state_lock:
                        current = ALL_DEVICES_STATUS.get(dev_id)
                        if current and current.get('received_at', 0) == last_seen and not current.get('offline_alerted', False):
                            current['offline_alerted'] = True
                            should_alert = True
                    if not should_alert:
                        continue
                    with print_lock:
                        print(f"\n🚨 【失联告警】{dev_id} 已 {gap_min // 60} 小时 {gap_min % 60} 分钟未上报！")
                    # 删除与兜底入队串行化：设备已从列表删除时，不再新建残留指令。
                    queued_a = enqueue_command(dev_id, "force_report", "", require_device=True)
                    queued_b = enqueue_command(dev_id, "restart_udp", "", require_device=True)
                    if queued_a or queued_b:
                        with print_lock:
                            print(f"📋 [兜底] 已为 {dev_id} 排队 force_report 和 restart_udp 指令")

                elif gap_min * 60 <= MANAGE_OFFLINE_THRESHOLD_S and already_alerted:
                    with state_lock:
                        if dev_id in ALL_DEVICES_STATUS:
                            ALL_DEVICES_STATUS[dev_id]['offline_alerted'] = False
                    with print_lock:
                        print(f"\n✅ 【恢复】{dev_id} 已恢复上报")
        except Exception as e:
            with print_lock:
                print(f"⚠️ 失联检测异常: {e}")



def resolve_history_ts(data, now_ms):
    """★ 2026-11-20 [C1] 修复：history 表时间基准两端统一。
    旧版单条分支用 received_at（服务端接收时刻）、批量分支用 timestamp（客户端采集时刻），
    同一条离线记录走批量还是走降级逐条，落进 history 的时间可相差数小时 → 轨迹回放时序错乱。
    统一口径：优先采用客户端 timestamp，缺失/越界才回落到服务端时刻，并兼容秒级时间戳。"""
    ts = data.get("timestamp")
    if isinstance(ts, bool) or not isinstance(ts, (int, float)):
        return int(now_ms)
    ts = int(ts)
    if 0 < ts < 10000000000:                  # 秒级时间戳兼容
        ts *= 1000
    if ts <= 0 or ts > now_ms + 86400000:     # 0 / 未来超过 1 天，视为不可信
        return int(now_ms)
    return ts


def save_history(dev_id, lat, lng, ts):
    if lat is None or lng is None: return
    with db_write_lock:
        conn = None
        try:
            conn = sqlite3.connect('track_history.db', timeout=10)
            c = conn.cursor()
            # ★ 2026-11-20 [B2] 幂等去重：客户端「服务端已入库但响应超时 / JSON 不可解析」时会整批重发，
            #   history 此前没有任何幂等键 → 写出完全重复的轨迹点。此处按
            #   (device_id, timestamp, lat, lng) 四元组全等判重：只有真正重复的「同一时刻同一坐标」
            #   才被跳过，正常移动轨迹（时刻或坐标任一不同）完全不受影响。
            dup = c.execute("SELECT 1 FROM history WHERE device_id=? AND timestamp=? AND lat=? AND lng=? LIMIT 1",
                            (dev_id, ts, lat, lng)).fetchone()
            if dup:
                return
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
    """
    终极全能全自动 IP 感知网关（0网络依赖 / 毫秒秒回 / 适应多网卡与公网任意切换）
    完全根治因断网或网络抖动引发的线程打满与 503 崩溃。
    修改时间 2026-11-19 21:39 
    """
    # 🌟 第一级纵深：高速虚拟套接字路由感知（全平台通用，完美保留原作者识别多网卡出口的优点）
    # 依次探测：内网两条最常用保留广播网段 -> 两个公共外网 IP
    # ★ FIX-D 2026-11-19：探测顺序回正为「公网 IP 优先 -> 私有广播址兜底」。
    #   私有址（192.168.255.255 / 10.255.255.255）排在前会让「命中任意直连私有路由」的
    #   网卡抢先胜出：装了 VPN（常见 10.0.0.0/8）、Docker/libvirt 网桥、多网卡机器上，
    #   选到的是隧道/虚拟网卡 IP，而手机能连通的其实是默认路由那张物理网卡。
    #   公网目标走默认路由，与原版行为一致；私有址只在断网/无默认路由时兜底。
    for target_ip, target_port in [("8.8.8.8", 80), ("114.114.114.114", 53), ("192.168.255.255", 1), ("10.255.255.255", 1)]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            # 关键设计：不设 settimeout！
            # 系统路由表畅通时，connect瞬间在 0 毫秒内由内核返回结果（不产生任何公网流量和网络延迟）；
            # 无论在 Win、Armbian 还是 Termux 下，若发生断网或无网关，内核会瞬间触发断路器抛出异常直接跳过。
            s.connect((target_ip, target_port))
            raw_ip = s.getsockname()
            s.close()
            # 严格验证并统一提取元组第 0 位的纯字符串 IP
            if isinstance(raw_ip, tuple) and raw_ip and raw_ip[0]:
                check_ip = raw_ip[0]
                if (check_ip and not check_ip.startswith("127.") and check_ip != "0.0.0.0" and not check_ip.startswith("169.254.")):
                    return check_ip
        except Exception:
            pass

    # 🌟 第二级纵深：Linux/Armbian 底层硬核文件抓取（带 Windows 与 Termux 沙箱物理双保险）
    try:
        # 保险1: 隔离 Windows 系统（Win 无此文件系统，强开会闪退）
        # 保险2: 隔离 Termux 系统（Android 沙箱无 root 权限强读此文件会触发 PermissionError 闪退）
        if os.path.exists("/proc/net/fib_trie") and not os.path.exists("/data/data/com.termux"):
            # ★ FIX-B 2026-11-19：必须取 "host LOCAL" 那一行的【上一行】才是本机主机地址。
            #   fib_trie 里第一个出现的非 127/0/169.254 地址是【网段地址】，不是本机 IP：
            #     +-- 10.193.176.0/20  <- 网段地址（旧实现误返回它）
            #        |-- 10.193.189.58 <- 本机地址
            #           /32 host LOCAL
            #   实测本机：旧实现返回 10.193.176.0（错），本实现返回 10.193.189.58（对）。
            #   错值会连带污染 get_subnet_broadcast()（算出 10.193.176.255，/20 真实广播址
            #   应为 10.193.191.255 -> UDP 信令静默丢失）、smart_send 的 local_prefix
            #   （本网段设备被误判为 remote）与媒体 URL 兜底。
            with open("/proc/net/fib_trie", "r") as f:
                lines = f.readlines()
            for i, line in enumerate(lines):
                if "LOCAL" in line and i > 0:
                    m = re.search(r"(\d+\.\d+\.\d+\.\d+)", lines[i - 1])
                    if m:
                        ip = m.group(1)
                        if (ip != "0.0.0.0" and not ip.startswith("127.") and not ip.startswith("169.254.")):
                            return ip
    except Exception:
        pass

    # 🌟 第三级纵深：Termux 与 Windows 物理断网状态下的系统主机名高级反查
    try:
        # 再次通过系统特征安全拦截 Android/Termux，防止触发 Android 底层的 SELinux 权限红线引发闪退
        if not os.path.exists("/data/data/com.termux"):
            ip = socket.gethostbyname(socket.gethostname())
            if isinstance(ip, str) and not ip.startswith("127.") and ip != "0.0.0.0" and not ip.startswith("169.254."):
                return ip
    except Exception:
        pass

    # 🌟 第四级纵深：在三大平台完全不连任何网络、不插网线、不开热点状态下的终极自愈纯字符串兜底
    return "127.0.0.1"

def resolve_device_udp_route(info, server_ip):
    """返回 (LAN目标, UDP实测目标)。CIDR 仅用于诊断；握手验证完成前不据此启用 LAN 单播。"""
    info = info if isinstance(info, dict) else {}
    # 只有 UDP listener 实际观察到的来源地址才可作为单播候选；绝不使用 HTTP source_ip，
    # 因为 Cloudflare Tunnel/反向代理地址并不是设备可回连地址。CIDR 仅在管理台展示。
    udp_ip = info.get("udp_source_ip")
    try:
        udp_obj = ipaddress.ip_address(str(udp_ip))
        if udp_obj.version == 4 and not (udp_obj.is_loopback or udp_obj.is_link_local
                                         or udp_obj.is_multicast or udp_obj.is_unspecified):
            return None, str(udp_obj)
    except Exception:
        pass
    return None, None


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
    if gap_min is not None and gap_min > (MANAGE_OFFLINE_THRESHOLD_S // 60):
        return f"<span title='失联超过 {MANAGE_OFFLINE_THRESHOLD_S // 3600} 小时' style='color:#cf222e;font-size:13px;'>🔴 </span>"
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


BATCH_REPORT_MAX_ITEMS = 100  # 2026-11-20 POST /api/report_batch 单批最大条数，防大包拖慢 worker


def handle_client(client_conn, client_addr):
    try:
        client_conn.settimeout(3.0)
        try:
            first_chunk = client_conn.recv(65536)
        except socket.timeout:
            return
        if not first_chunk: return

        client_conn.settimeout(10.0)
        raw = bytearray(first_chunk)
        # ★ 2026-11-20 [P0] 请求头单独限长；不完整头或超长头直接拒绝，避免无界拼接。
        while b"\r\n\r\n" not in raw:
            if len(raw) > HTTP_MAX_HEADER_BYTES:
                client_conn.sendall(b"HTTP/1.1 431 Request Header Fields Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            chunk = client_conn.recv(8192)
            if not chunk: break
            raw.extend(chunk)
        if b"\r\n\r\n" not in raw:
            client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        header_part, _, body_part = bytes(raw).partition(b"\r\n\r\n")
        if len(header_part) > HTTP_MAX_HEADER_BYTES:
            client_conn.sendall(b"HTTP/1.1 431 Request Header Fields Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        header_text = header_part.decode('iso-8859-1', errors='replace')
        headers = {}
        for line in header_text.split("\r\n")[1:]:
            if not line: continue
            if ":" not in line:
                client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            key, value = line.split(":", 1)
            key = key.strip().lower()
            value = value.strip()
            if key in headers and key in ("content-length", "transfer-encoding"):
                client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            headers[key] = value

        # ★ 2026-11-20 [P0] 原生 Socket 入口明确只接收 Content-Length；拒绝未实现的 chunked，禁止误解析半截数据。
        transfer_encoding = headers.get("transfer-encoding", "").lower()
        if transfer_encoding:
            client_conn.sendall(b"HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        content_length = 0
        if "content-length" in headers:
            raw_length = headers["content-length"]
            if not raw_length.isdigit():
                client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            content_length = int(raw_length)
        if content_length > HTTP_MAX_BODY_BYTES:
            client_conn.sendall(b"HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        body_part = bytearray(body_part)
        if len(body_part) > content_length:
            body_part = body_part[:content_length]
        while len(body_part) < content_length:
            try:
                chunk = client_conn.recv(min(65536, content_length - len(body_part)))
            except socket.timeout:
                client_conn.sendall(b"HTTP/1.1 408 Request Timeout\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            if not chunk: break
            body_part.extend(chunk)
        BODY_TRUNCATED = (len(body_part) != content_length)
        if BODY_TRUNCATED:
            client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        body_part = bytes(body_part)
        if not header_text: return

        if header_text.startswith("OPTIONS"):
            client_conn.sendall(b"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return

        # ===== POST /api/report_batch =====
        # ★ 2026-11-20 批量上报入口：手机端离线补发在弱网恢复时把多条记录打包成一次 HTTP 往返。
        #   必须放在 POST /api/report 之前判定 —— 否则 "POST /api/report" 是
        #   "POST /api/report_batch" 的子串，会被单条分支抢先匹配吞掉。
        elif "POST /api/report_batch" in header_text:
            try:
                batch_data = json.loads(body_part.decode('utf-8', errors='ignore'))
                if not isinstance(batch_data, dict):
                    client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                if batch_data.get("token") != AUTH_TOKEN:
                    client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return

                reports_list = batch_data.get("reports")
                if not isinstance(reports_list, list):
                    client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return

                # 单批上限保护：超限直接 400，避免一次性包体过大拖慢 worker
                if len(reports_list) > BATCH_REPORT_MAX_ITEMS:
                    err_bytes = json.dumps({"status": "error", "msg": "单批上限 %d 条" % BATCH_REPORT_MAX_ITEMS}, ensure_ascii=False).encode('utf-8')
                    client_conn.sendall(
                        ("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(err_bytes)).encode('utf-8') + err_bytes)
                    return

                now_ms = int(datetime.datetime.now().timestamp() * 1000)
                batch_dev_id = batch_data.get("device_id")
                if not batch_dev_id and reports_list and isinstance(reports_list[0], dict):
                    batch_dev_id = reports_list[0].get("device_id")
                batch_dev_id = batch_dev_id or "unknown_device"
                if not isinstance(batch_dev_id, str) or not batch_dev_id.strip():
                    client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                batch_dev_id = batch_dev_id.strip()
                accepted = 0
                feedback_accepted_total = 0
                all_executed_ids = []

                # ★ 2026-11-20 20:32 严格按客户端发送顺序前缀入库：遇到首条无效记录立即 break，
                #   确保 accepted_is_prefix=True 时客户端按前 N 条删除与服务端入库 100% 一一对应
                for item in reports_list:
                    if not isinstance(item, dict):
                        break
                    item_token = item.get("token") or batch_data.get("token")
                    if item_token != AUTH_TOKEN:
                        break

                    dev_id = item.get("device_id") or batch_dev_id
                    # ★ 2026-11-20 [P1] 一个 batch 只允许一个设备；避免跨设备写状态/错确认指令。
                    if not isinstance(dev_id, str) or dev_id.strip() != batch_dev_id:
                        break
                    if item.get("network") is None: item["network"] = {}
                    if item.get("battery") is None: item["battery"] = {}
                    if item.get("location") is None: item["location"] = {}
                    if item.get("nearby_wifi") is None: item["nearby_wifi"] = []

                    executed_ids = item.pop("last_executed_cmd_ids", [])
                    if not isinstance(executed_ids, list):
                        executed_ids = []
                    command_feedback = item.pop("command_feedback", [])
                    if not isinstance(command_feedback, list):
                        command_feedback = []

                    item["received_at"] = now_ms

                    # v2.0 补丁：通道健康度判定（服务端算，客户端不填）
                    ch = item.get("channel") or {}
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
                        item["channel"] = ch

                    # ★ 2026-11-20 [P0] 必须先确认数据库事务成功；失败即停止前缀，客户端保留本条及后续队列。
                    if not save_report(dev_id, item, client_addr[0]):
                        break

                    with state_lock:
                        if dev_id not in ALL_DEVICES_STATUS:
                            ALL_DEVICES_STATUS[dev_id] = {}
                        prev_info = dict(ALL_DEVICES_STATUS[dev_id])
                        prev_udp_ip = prev_info.get("udp_source_ip")
                        if "cloud_logs" in item:
                            prev_info["cloud_logs"] = item["cloud_logs"]
                            prev_info["cloud_logs_time"] = item["received_at"]
                        elif "cloud_logs" in prev_info:
                            item["cloud_logs"] = prev_info["cloud_logs"]
                            item["cloud_logs_time"] = prev_info.get("cloud_logs_time", 0)
                        item["source_ip"] = client_addr[0]
                        item["local_ip"] = (item.get("network") or {}).get("local_ip", "")
                        # HTTP client_addr 可能是 Cloudflare Tunnel/反向代理地址，不作为设备 last_valid_ip。
                        # 设备地址优先采用客户端明确上报的 local_ip；其次保留 UDP listener 实测来源地址。
                        incoming_ips = [item.get("local_ip"), prev_udp_ip, prev_info.get("last_valid_ip")]
                        valid_ip = next((x for x in incoming_ips if isinstance(x, str) and x
                                         and not x.startswith(("127.", "169.254.")) and x != "0.0.0.0"), None)
                        if valid_ip:
                            item["last_valid_ip"] = valid_ip
                        # ★ 2026-11-07 [P4.4]
                        item["build_version"] = item.get("build_version") or "旧版"
                        item["protocol_version"] = item.get("protocol_version") or 1
                        if prev_udp_ip:
                            item["udp_source_ip"] = prev_udp_ip
                        if prev_info.get("last_chat"):
                            item["last_chat"] = prev_info["last_chat"]
                        ALL_DEVICES_STATUS[dev_id] = item
                    all_executed_ids.extend(executed_ids)
                    if command_feedback:
                        feedback_accepted_total += process_command_feedback(batch_dev_id, command_feedback)
                    accepted += 1

                if all_executed_ids:
                    process_executed_commands(batch_dev_id, all_executed_ids)

                pending = get_pending_commands(batch_dev_id)
                next_poll = 30 if pending else 0
                response_body = json.dumps({
                    "status": "success",
                    "accepted": accepted,
                    "accepted_is_prefix": True,
                    "feedback_accepted": feedback_accepted_total,
                    "config": GLOBAL_DEFAULT_CONFIG,
                    "pending_commands": pending,
                    "next_poll_seconds": next_poll
                }, ensure_ascii=False)

                response_bytes = response_body.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {len(response_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + response_bytes)
                # ★ 2026-11-20 [致命 B0] 修复：批量分支处理完毕必须显式 return。
                #   旧版此处与 except 分支均无 return，而 "POST /api/report" 恰好是
                #   "POST /api/report_batch" 的子串 → 每一次批量上报都会再落入下方单条分支：
                #   ① 把整个批量信封当上报体写出 1 条脏行（timestamp=now、陪伴字段全 NULL）；
                #   ② ALL_DEVICES_STATUS[dev_id] 被信封覆盖，位置/电量/信道健康度全部丢失；
                #   ③ 同一 TCP 连接写出第二个 HTTP 响应（客户端只读第一个，第二个触发 BrokenPipe）。
                return
            except Exception as e:
                with print_lock: print(f"❌ POST /api/report_batch 异常: {e}")
                try: client_conn.sendall(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except Exception: pass

        # ===== POST /api/report =====
        # ★ 2026-11-20 [致命 B0] 修复：if → elif，让单条分支与上方批量分支同属一条 if/elif 链，
        #   从根上消除「子串抢匹配 + 分支穿透」两类隐患（上一处 return 作为双保险保留）。
        elif "POST /api/report" in header_text:
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
                if not isinstance(executed_ids, list):
                    executed_ids = []
                command_feedback = data.pop("command_feedback", [])
                if not isinstance(command_feedback, list):
                    command_feedback = []
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

                # 保留旧版 cloud_logs 延续展示语义：先从状态快照补齐，再提交上报数据。
                with state_lock:
                    prev_info = dict(ALL_DEVICES_STATUS.get(dev_id, {}))
                if "cloud_logs" not in data and "cloud_logs" in prev_info:
                    data["cloud_logs"] = prev_info["cloud_logs"]
                    data["cloud_logs_time"] = prev_info.get("cloud_logs_time", 0)

                # ★ 2026-11-20 [P0/P1] 数据库事务成功后才更新在线状态、确认客户端 ACK。
                if not save_report(dev_id, data, client_addr[0]):
                    client_conn.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                if executed_ids:
                    process_executed_commands(dev_id, executed_ids)
                feedback_accepted = process_command_feedback(dev_id, command_feedback) if command_feedback else 0

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
                    # HTTP client_addr 可能是 Cloudflare Tunnel/反向代理地址，不作为设备 last_valid_ip。
                    incoming_ips = [data.get("local_ip"), prev_udp_ip, prev_info.get("last_valid_ip")]
                    valid_ip = next((x for x in incoming_ips if isinstance(x, str) and x
                                     and not x.startswith(("127.", "169.254.")) and x != "0.0.0.0"), None)
                    if valid_ip:
                        data["last_valid_ip"] = valid_ip
                    # ★ 2026-11-07 [P4.4]
                    data["build_version"] = data.get("build_version") or "旧版"
                    data["protocol_version"] = data.get("protocol_version") or 1
                    if prev_udp_ip:
                        data["udp_source_ip"] = prev_udp_ip
                    if prev_info.get("last_chat"):
                        data["last_chat"] = prev_info["last_chat"]
                    ALL_DEVICES_STATUS[dev_id] = data

                pending = get_pending_commands(dev_id)
                next_poll = 30 if pending else 0
                response_body = json.dumps({
                    "status": "success",
                    "feedback_accepted": feedback_accepted,
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
                    ip_candidates = [info.get("last_valid_ip"), info.get("local_ip"), (info.get("network") or {}).get("local_ip"), info.get("source_ip"), info.get("udp_source_ip")]
                    ip = next((x for x in ip_candidates if isinstance(x, str) and x and not x.startswith(("127.", "169.254.")) and x != "0.0.0.0"), "")
                    battery = info.get("battery") or {}
                    batt_level = battery.get("level")
                    if batt_level == "?" or batt_level is None: batt_level = None
                    loc = info.get("location") or {}
                    loc_lat = loc.get("latitude")
                    loc_lng = loc.get("longitude")
                    loc_time = loc.get("time")
                    loc_age = max(0, int(now - (loc_time / 1000.0))) if isinstance(loc_time, (int, float)) and loc_time > 0 else (loc.get("age_s") if isinstance(loc.get("age_s"), int) else None)
                    loc_obj = None
                    if loc_lat is not None and loc_lng is not None:
                        loc_obj = {"lat": loc_lat, "lng": loc_lng, "accuracy": loc.get("accuracy"), "age_s": loc_age, "time": loc_time if isinstance(loc_time, (int, float)) else None}
                    
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
                        "network_type": net.get("network_type") or net.get("transport") or net_type,
                        "network_subnet_prefix": net.get("subnet_prefix") or net.get("network_subnet_prefix") or "",
                        "network_interface": net.get("interface_name") or "",
                        "network_validated": net.get("validated"),
                        "network_changed_at": net.get("changed_at"),
                        "location": loc_obj,
                        "commands": [{"id": c["id"], "type": c["type"], "payload": str(c.get("payload", ""))[:60], "age_s": max(0, int(now - c["ts"]))} for c in queue[:10]],
                        "recent_feedback": get_recent_command_feedback(dev_id, 10),
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

        # ===== GET /api/companion_status (按 device_id 查询 reports 表陪伴状态与历史明细) =====
        # ★ 2026-11-20 供 Web 控制台「陪伴状态与历史明细查询」面板调用。
        elif "GET /api/companion_status" in header_text:
            try:
                req_line = header_text.split("\r\n")[0]
                path_with_query = req_line.split(" ")[1] if len(req_line.split(" ")) > 1 else ""
                params = parse_qs(urlparse(path_with_query).query)
                dev_id = (params.get("device_id", [""])[0] or "").strip()
                try:
                    limit = int(params.get("limit", ["30"])[0])
                except Exception:
                    limit = 30
                limit = max(1, min(limit, 200))

                if not dev_id:
                    err_body = json.dumps({"status": "error", "msg": "缺少 device_id 参数"}, ensure_ascii=False).encode('utf-8')
                    client_conn.sendall(
                        f"HTTP/1.1 400 Bad Request\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(err_body)}\r\nConnection: close\r\n\r\n".encode('utf-8') + err_body)
                    return

                conn = sqlite3.connect('track_history.db', timeout=10)
                try:
                    conn.row_factory = sqlite3.Row
                    c = conn.cursor()
                    rows = c.execute("""
                        SELECT id, device_id, timestamp, received_at,
                               companion_enabled, push_photo_url, push_video_url,
                               push_audio_url, push_web_url,
                               battery_level, is_charging, network_type, wifi_ssid,
                               lat, lng, is_screen_on, elder_mode, build_version
                        FROM reports
                        WHERE device_id = ?
                        ORDER BY timestamp DESC, id DESC
                        LIMIT ?
                    """, (dev_id, limit)).fetchall()
                finally:
                    conn.close()

                history_items = []
                for r in rows:
                    history_items.append({
                        "id": r["id"],
                        "device_id": r["device_id"],
                        "timestamp": r["timestamp"],
                        "received_at": r["received_at"],
                        "companion_enabled": r["companion_enabled"],
                        "push_photo_url": r["push_photo_url"] or "",
                        "push_video_url": r["push_video_url"] or "",
                        "push_audio_url": r["push_audio_url"] or "",
                        "push_web_url": r["push_web_url"] or "",
                        "battery_level": r["battery_level"],
                        "is_charging": r["is_charging"],
                        "network_type": r["network_type"] or "",
                        "wifi_ssid": r["wifi_ssid"] or "",
                        "lat": r["lat"],
                        "lng": r["lng"],
                        "is_screen_on": r["is_screen_on"],
                        "elder_mode": r["elder_mode"],
                        "build_version": r["build_version"] or ""
                    })

                latest_state = history_items[0] if history_items else None
                resp = json.dumps({
                    "status": "ok",
                    "device_id": dev_id,
                    "latest": latest_state,
                    "history": history_items
                }, ensure_ascii=False)
                resp_bytes = resp.encode('utf-8')
                client_conn.sendall(
                    f"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes)
            except Exception as e:
                with print_lock: print(f"❌ GET /api/companion_status 异常: {e}")
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
                if CONTROL_AUTH_ENABLED:
                    if CONTROL_IP_ALLOWLIST and not any(
                            str(client_addr[0]).startswith(p) for p in CONTROL_IP_ALLOWLIST):
                        with print_lock:
                            print(f"⛔ [控制面来源拦截] {client_addr[0]} 不在 DF_CONTROL_ALLOW_IPS 白名单")
                        client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        return
                    # FIX-1B：比对 CONTROL_TOKEN（不再比对设备口令 AUTH_TOKEN）
                    if cmd_data.get("token") != CONTROL_TOKEN:
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
                    except Exception:
                        local_ip = ""
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
                            lan_target, observed_udp_target = resolve_device_udp_route(info, local_ip)
                            # 未上报有效 CIDR 或不在服务端子网时，不猜测同网；跨网仅尝试 UDP listener 实测来源。
                            if lan_target and lan_target not in unicast_ips:
                                unicast_ips.append(lan_target)
                            elif observed_udp_target and observed_udp_target not in remote_ips:
                                remote_ips.append(observed_udp_target)
                        need_broadcast = (not unicast_ips) or (len(unicast_ips) > UDP_TOTAL_PACKET_BUDGET)
                        if need_broadcast:
                            sent_pkts += send_udp_broadcast(cmd_prefix, "255.255.255.255")
                        else:
                            for ip in unicast_ips:
                                sent_pkts += send_udp_broadcast(cmd_prefix, ip)
                        if remote_ips and (len(unicast_ips) + len(remote_ips)) <= UDP_TOTAL_PACKET_BUDGET:
                            for ip in remote_ips:
                                # 仅对 UDP listener 实际观测到的来源地址尝试跨网段单播；HTTP 代理地址不参与。
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
                    # v2-only：单一 CHAT 信令
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

                elif action == "set_offline_threshold":
                    if _set_offline_threshold(payload):
                        success = True
                        extra_msg = f"离线判定阈值已更新为 {MANAGE_OFFLINE_THRESHOLD_S // 3600} 小时（服务端配置已保存）" if MANAGE_OFFLINE_THRESHOLD_S % 3600 == 0 else f"离线判定阈值已更新为 {MANAGE_OFFLINE_THRESHOLD_S // 60} 分钟（服务端配置已保存）"
                    else:
                        success = False
                        extra_msg = "阈值无效：允许 5 分钟至 7 天"

                elif action == "clean_device":
                    target_dev_id = payload
                    if not isinstance(target_dev_id, str) or not target_dev_id.strip():
                        success = False; extra_msg = "无效的设备 ID"
                    else:
                        target_dev_id = target_dev_id.strip()
                        existed, pending_count = _delete_device_if_queue_empty(target_dev_id)
                        if pending_count:
                            success = False
                            extra_msg = f"{target_dev_id} 仍有 {pending_count} 条待确认指令，请先在指令管理中撤回或处理后再删除"
                        else:
                            success = existed
                            if existed:
                                _record_device_delete_audit(target_dev_id, "clean_device", client_addr[0], "success")
                            extra_msg = f"已删除 {target_dev_id}；历史数据保留，设备再次上报后可重新出现" if existed else f"设备 {target_dev_id} 不存在（可能已清理）"

                elif action == "clean_offline":
                    try:
                        now_ts = int(datetime.datetime.now().timestamp() * 1000)
                        candidates = []
                        with state_lock:
                            for k, v in list(ALL_DEVICES_STATUS.items()):
                                if now_ts - v.get('received_at', 0) > CLEAN_OFFLINE_THRESHOLD_MS:
                                    candidates.append(k)
                        to_delete = []
                        skipped_pending = 0
                        for k in candidates:
                            deleted, pending_count = _delete_device_if_queue_empty(k)
                            if pending_count:
                                skipped_pending += 1
                            elif deleted:
                                to_delete.append(k)
                        for k in to_delete:
                            _record_device_delete_audit(k, "clean_offline", client_addr[0], "success")
                        success = bool(to_delete or skipped_pending)
                        threshold_h = CLEAN_OFFLINE_THRESHOLD_MS // 3600000
                        extra_msg = f"清理了 {len(to_delete)} 台设备（>{threshold_h}小时未上报）" + (f"；另有 {skipped_pending} 台因存在待确认指令而跳过" if skipped_pending else "") if to_delete or skipped_pending else f"没有符合条件的设备（>{threshold_h}小时未上报）"
                    except Exception as e:
                        success = False; extra_msg = f"清理失败: {e}"
                # 2026-11-20 13:38 在 POST /api/control 的 action 分支中补充：
                elif action == "clear_cmd_queue":
                    target_dev = (cmd_data.get("target_device_id") or (payload if isinstance(payload, str) else "")).strip()
                    if target_dev and target_dev != "all":
                        cnt = clear_pending_commands(target_dev)
                        success = True
                        extra_msg = f"已清空设备 {target_dev} 的 {cnt} 条待发指令"
                    else:
                        cnt = clear_pending_commands(None)
                        success = True
                        extra_msg = f"已清空全部设备的 {cnt} 条待发指令"

                elif action == "cancel_cmd":
                    # payload 可传 "dev_id:cmd_id" 或 dict/单独字段 target_device_id + cmd_id
                    t_dev = (cmd_data.get("target_device_id") or "").strip()
                    c_id = (cmd_data.get("cmd_id") or "").strip()
                    if (not t_dev or not c_id) and isinstance(payload, str) and ":" in payload:
                        t_dev, c_id = payload.split(":", 1)
                    ok = remove_command_by_id(t_dev.strip(), c_id.strip())
                    success = ok
                    extra_msg = f"已撤回指令 {c_id}" if ok else "未找到对应的待发指令（可能已执行或已过期）"

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
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
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
/* ★ v2.5.3：高级设置使用独立设备选择器，避免与陪伴/对讲机选择状态串扰 */
.admin-device-selector { display:flex; flex-wrap:wrap; gap:6px; padding:10px 12px; margin-top:10px; border:1px solid var(--color-border-muted); border-radius:6px; background:#fff; }
.admin-device-selector .device-tag span { border-radius:6px; }
.admin-scope-hint { color:var(--color-fg-muted); font-size:12px; margin-top:6px; }

.device-tag { position: relative; cursor: pointer; }
.device-tag input { position: absolute; opacity: 0; cursor: pointer; height: 0; width: 0; }
.device-tag span { display: inline-block; padding: 4px 10px; font-size: 12px; border-radius: 20px; border: 1px solid var(--color-border-default); background: #fff; color: var(--color-fg-default); transition: 0.2s; user-select: none; }
.device-tag input:checked ~ span { background: #24292f; color: #fff; border-color: #24292f; box-shadow: inset 0 1px 3px rgba(0,0,0,0.5); }


/* ★ v2.5.5 管理台结构升级：借鉴 OpenWrt LuCI 的分区导航与工作区布局 */
.admin-shell { display:grid; grid-template-columns:220px minmax(0,1fr); gap:18px; align-items:start; max-width:1600px; margin:0 auto; }
.admin-sidebar { position:sticky; top:16px; background:#fff; border:1px solid var(--color-border-default); border-radius:10px; padding:12px; }
.admin-brand { font-size:14px; font-weight:700; padding:8px 10px 14px; border-bottom:1px solid var(--color-border-muted); margin-bottom:10px; }
.admin-nav-group { margin:14px 0 7px; padding:0 10px; color:var(--color-fg-muted); font-size:12px; font-weight:700; letter-spacing:.04em; }
.admin-nav { display:flex; flex-direction:column; gap:4px; }
.admin-nav button { width:100%; text-align:left; border:1px solid transparent; background:transparent; padding:10px 10px; border-radius:7px; white-space:normal; font-size:15px; }
.admin-nav button:hover { background:var(--color-canvas-subtle); }
.admin-nav button.active { color:#0969da; background:#eef5ff; border-color:#c8dcf8; font-weight:650; }
.admin-workspace { min-width:0; }
.admin-page { display:none; min-width:0; }
.admin-page.active { display:block; }
.admin-page-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin:2px 0 14px; }
.admin-page-head h3 { margin:0; font-size:19px; }
.admin-page-desc { color:var(--color-fg-muted); font-size:12px; margin-top:5px; }
.admin-page .panel { margin-bottom:16px; }
.admin-page .table-responsive { margin-top:0; }
.admin-health-placeholder { border:1px dashed #9aa4af; border-radius:10px; padding:28px; background:#fff; }
.admin-health-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin-top:16px; }
.admin-health-card { border:1px solid var(--color-border-default); border-radius:8px; padding:14px; background:var(--color-canvas-subtle); }
/* ★ v2.5.7：二级导航与独立工作页，避免沟通面板纵向堆叠 */
.admin-nav-sub { display:flex; flex-direction:column; gap:3px; margin:2px 0 8px 10px; padding-left:0; border-left:0; }
.admin-nav-sub button { font-size:14px; padding:8px 10px; color:var(--color-fg-muted); }
.admin-nav-sub button.active { color:#0969da; background:#eef5ff; border-color:#c8dcf8; }
.admin-global-scope { display:none; margin:0 0 14px; }
.admin-global-scope.active { display:block; }
.admin-subpage-intro { margin-bottom:12px; }
.admin-subpage-actions { display:flex; flex-wrap:wrap; gap:8px; }
.admin-subpage-actions button { margin:0; }
#admin-page-talk #chatBox, #admin-page-companion #companionBox { margin-top:0; }
#admin-page-map #mapContainer { margin-top:0; }
/* ★ v2.5.6：维护页卡片化，降低按钮密度并建立视觉层级 */
.admin-page#admin-page-maintenance > .panel { padding:14px 16px; }
#admin-page-maintenance details { border:0 !important; margin:0 !important; padding:0 !important; }
#admin-page-maintenance details > summary { list-style:none; cursor:default; font-size:17px; font-weight:700; padding:4px 0 12px; border-bottom:1px solid var(--color-border-muted); }
#admin-page-maintenance details > summary::-webkit-details-marker { display:none; }
.admin-scope-panel { display:flex; align-items:center; gap:16px; margin:14px 0 16px !important; padding:12px 14px !important; background:#fff !important; border:1px solid var(--color-border-default) !important; border-radius:9px; }
.admin-scope-panel > div:first-child { flex:0 0 130px; }
.admin-scope-panel .admin-scope-hint { margin-top:3px; font-size:11px; color:var(--color-fg-muted); }
.admin-scope-panel .admin-device-selector { flex:1; min-height:0; padding:0 !important; border:0 !important; background:transparent !important; }
.admin-tool-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.admin-tool-card { min-width:0; border:1px solid var(--color-border-default); border-radius:9px; padding:13px 14px; background:#fff; }
.admin-tool-card-head { display:flex; flex-direction:column; gap:3px; margin-bottom:10px; }
.admin-tool-card-head b { font-size:14px; }
.admin-tool-card-head span { color:var(--color-fg-muted); font-size:11px; }
.admin-tool-actions { display:flex; flex-wrap:wrap; gap:7px; }
.admin-tool-actions button { margin:0; min-height:34px; padding:6px 10px; font-size:12px; }
.admin-tool-card.is-empty { display:none !important; }
.admin-tool-actions { display:grid; grid-template-columns:repeat(auto-fit,minmax(112px,1fr)); align-content:start; align-items:stretch; }
.admin-tool-actions button { min-height:38px; display:flex; align-items:center; justify-content:center; text-align:center; line-height:1.35; }
.admin-sms-row { display:grid; grid-template-columns:minmax(180px,1fr) minmax(220px,2fr) auto; align-items:center; gap:10px; margin-top:14px !important; padding:12px 14px; border:1px solid var(--color-border-default); border-radius:9px; background:#fff; }
.admin-sms-title { display:flex; flex-direction:column; gap:3px; font-size:13px; }
.admin-sms-title span { color:var(--color-fg-muted); font-size:11px; }
.admin-sms-row input { min-width:0; }
@media (max-width: 1000px) { .admin-tool-grid { grid-template-columns:1fr; } }
@media (max-width: 700px) {
 .admin-scope-panel { align-items:flex-start; flex-direction:column; gap:8px; }
 .admin-scope-panel > div:first-child { flex-basis:auto; }
 .admin-sms-row { grid-template-columns:1fr; }
 .admin-sms-row button { width:100%; }
}
.admin-mobile-title { display:none; }
@media (max-width: 900px) {
  .admin-shell { grid-template-columns:1fr; gap:10px; }
  .admin-sidebar { position:static; padding:8px; }
  .admin-brand { display:none; }
  .admin-nav-group { display:none; }
  .admin-nav { flex-direction:row; overflow-x:auto; padding-bottom:3px; }
  .admin-nav button { width:auto; min-width:max-content; text-align:center; padding:8px 12px; }
  .admin-mobile-title { display:block; font-weight:700; margin:2px 0 8px; }
  .admin-health-grid { grid-template-columns:1fr; }
}

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

/* ★ v2.5.8：手机端专用布局。桌面端维持原有侧栏+工作区结构 */
@media (max-width: 600px) {
    body { margin:8px; padding-bottom:calc(12px + env(safe-area-inset-bottom)); }
    body > h2 { font-size:17px; line-height:1.35; margin:4px 2px 12px; }
    .admin-shell { display:flex; flex-direction:column; gap:10px; width:100%; }
    .admin-sidebar { position:static; width:100%; box-sizing:border-box; padding:9px; border-radius:9px; }
    .admin-brand { display:block; font-size:13px; padding:4px 5px 8px; margin-bottom:7px; }
    /* 分组标题不再隐藏；主入口和二级入口在手机上按分组排列 */
    .admin-nav { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:5px; overflow:visible; padding:0; }
    .admin-nav-group { display:block; grid-column:1 / -1; margin:8px 2px 1px; padding:0 3px; font-size:10px; }
    .admin-nav > button { width:100%; min-width:0; min-height:40px; box-sizing:border-box; padding:8px 7px; text-align:left; white-space:normal; font-size:13px; }
    .admin-nav-sub { display:grid; grid-column:1 / -1; grid-template-columns:repeat(2,minmax(0,1fr)); gap:5px; margin:0; padding:0; border:0; }
    .admin-nav-sub button { width:100%; min-width:0; min-height:38px; box-sizing:border-box; padding:7px 6px; text-align:left; white-space:normal; font-size:12px; }
    .admin-workspace { width:100%; min-width:0; }
    .admin-mobile-title { display:none; }
    .admin-page-head { margin:3px 2px 10px; }
    .admin-page-head h3 { font-size:18px; }
    .admin-page .panel, .panel { padding:11px; margin-bottom:12px; }
    .admin-page#admin-page-maintenance > .panel { padding:11px; }
    .admin-tool-grid { grid-template-columns:minmax(0,1fr); gap:9px; }
    .admin-tool-card { padding:11px; }
    .admin-tool-actions { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; }
    .admin-tool-actions button { width:100%; min-width:0; min-height:42px; padding:8px 6px; white-space:normal; line-height:1.3; font-size:12px; }
    .admin-scope-panel { padding:10px !important; gap:7px; }
    .admin-device-selector { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; width:100%; box-sizing:border-box; }
    .admin-device-selector .device-tag span { display:block; min-height:34px; box-sizing:border-box; padding:8px 7px; text-align:center; overflow-wrap:anywhere; }
    .admin-scope-panel .admin-device-selector { display:grid; }
    .admin-sms-row { display:grid; grid-template-columns:minmax(0,1fr); gap:8px; padding:10px; }
    .admin-sms-row input, input[type="text"], .input-row input[type="text"] { width:100%; min-width:0; min-height:42px; box-sizing:border-box; font-size:16px; }
    button { min-height:40px; max-width:100%; white-space:normal; }
    .admin-subpage-actions { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; }
    .admin-subpage-actions button { width:100%; min-height:42px; white-space:normal; }
    #chatBox { height:min(62vh,560px); min-height:360px; }
    #chatMessages { padding:10px; }
    .chat-input-area { padding:9px; }
    .chat-input-area input { flex:1 1 100%; min-width:0; min-height:42px; font-size:16px; }
    .chat-input-area button { flex:1 1 calc(50% - 8px); min-height:42px; }
    #companionBox { margin-top:0; }
    #mapContainer { margin-top:10px; }
    #map { height:58vh; min-height:320px; max-height:680px; }
    #msgContent { width:calc(100% - 20px); max-height:calc(100dvh - 24px - env(safe-area-inset-top) - env(safe-area-inset-bottom)); box-sizing:border-box; }
    .modal-header, .modal-body { padding:12px; }
    .table-responsive { max-width:100%; -webkit-overflow-scrolling:touch; }
    table { min-width:760px; }
    th, td { padding:9px 10px; font-size:12px; }
    #toast { min-width:0; width:calc(100% - 32px); bottom:calc(16px + env(safe-area-inset-bottom)); box-sizing:border-box; }
}
@media (min-width: 601px) and (max-width: 900px) {
    body { margin:14px; }
    .admin-shell { grid-template-columns:190px minmax(0,1fr); gap:12px; }
    .admin-sidebar { padding:9px; }
    .admin-nav button { padding:8px 7px; font-size:13px; }
}

/* ★ v2.5.9：手机端采用 LuCI 风格抽屉导航 + 底部快捷栏；桌面端侧栏保持不变 */
.admin-mobile-topbar, .admin-mobile-backdrop, .admin-mobile-bottom { display:none; }
@media (max-width: 600px) {
  html, body { width:100%; min-width:0; overflow-x:hidden; }
  body { margin:0 !important; padding:0 0 calc(68px + env(safe-area-inset-bottom)) !important; }
  body > h2 { display:none !important; }
  .admin-mobile-topbar {
    display:flex; position:sticky; top:0; z-index:1200; min-height:52px;
    align-items:center; justify-content:space-between; gap:10px;
    padding:calc(5px + env(safe-area-inset-top)) 14px 5px;
    background:#fff; border-bottom:1px solid var(--color-border-default);
    box-shadow:0 2px 8px rgba(31,41,55,.06);
  }
  .admin-mobile-topbar .mobile-brand { min-width:0; font-size:15px; font-weight:700; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .admin-mobile-topbar button { min-width:42px; min-height:40px; padding:7px 11px; font-size:18px; }
  .admin-mobile-topbar .mobile-page-name { flex:1; min-width:0; text-align:right; color:var(--color-fg-muted); font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .admin-shell { display:block !important; width:100%; margin:0; padding:12px 12px 18px; box-sizing:border-box; }
  .admin-sidebar {
    display:block !important; position:fixed !important; z-index:1400; top:0; bottom:0; left:0;
    width:84vw !important; max-width:320px; max-height:100vh; max-height:100dvh; overflow-y:auto; overscroll-behavior:contain;
    box-sizing:border-box; padding:calc(14px + env(safe-area-inset-top)) 12px calc(18px + env(safe-area-inset-bottom));
    border-radius:0 14px 14px 0; border:0; box-shadow:0 12px 40px rgba(0,0,0,.22);
    transform:translateX(-105%); visibility:hidden; transition:transform .22s ease, visibility .22s ease;
  }
  .admin-sidebar.mobile-open { transform:translateX(0); visibility:visible; }
  .admin-brand { display:block !important; font-size:14px; padding:8px 8px 13px; margin-bottom:12px; }
  .admin-nav { display:flex !important; flex-direction:column !important; gap:4px; overflow:visible !important; }
  .admin-nav-group { display:block !important; margin:15px 0 7px; padding:0 9px; font-size:12px; }
  .admin-nav > button, .admin-nav-sub button { width:100% !important; min-width:0 !important; min-height:46px; padding:10px 11px !important; text-align:left !important; font-size:15px !important; }
  .admin-nav-sub { display:flex !important; flex-direction:column; gap:3px; margin:2px 0 8px 8px; padding-left:0; border-left:0; }
  .admin-mobile-backdrop { display:block; position:fixed; inset:0; z-index:1300; background:rgba(15,23,42,.42); opacity:0; visibility:hidden; transition:opacity .2s ease,visibility .2s ease; }
  .admin-mobile-backdrop.visible { opacity:1; visibility:visible; }
  .admin-mobile-bottom {
    display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); position:fixed; z-index:1250;
    left:0; right:0; bottom:0; min-height:56px; padding:5px 5px calc(5px + env(safe-area-inset-bottom));
    background:rgba(255,255,255,.97); border-top:1px solid var(--color-border-default);
    box-shadow:0 -3px 12px rgba(31,41,55,.07); backdrop-filter:blur(12px);
  }
  .admin-mobile-bottom button { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; min-width:0; min-height:48px; padding:4px 2px; border:0; background:transparent; font-size:10px; line-height:1.2; color:var(--color-fg-muted); }
  .admin-mobile-bottom button .nav-ico { font-size:18px; line-height:1.1; }
  .admin-mobile-bottom button.active { color:#0969da; font-weight:700; background:#eef5ff; border-radius:9px; }
  .admin-workspace { width:100%; min-width:0; }
  .admin-mobile-title { display:none !important; }
  .admin-page-head { margin:1px 1px 12px; align-items:center; }
  .admin-page-head h3 { font-size:19px; }
  .admin-page-desc { line-height:1.5; }
  .admin-page .panel, .panel { border-radius:10px; }
  #map { height:calc(100dvh - 190px); min-height:340px; max-height:none; }
}
@media (prefers-reduced-motion: reduce) {
  .admin-sidebar, .admin-mobile-backdrop { transition:none !important; }
}


/* ★ v2.5.10：管理工作页统一采用运动健康页的低装饰中性视觉 */
.admin-page .panel,
#admin-page-talk #chatBox,
#admin-page-companion #companionBox,
#admin-page-map #mapContainer,
#admin-page-location .panel,
#admin-page-messages .panel,
#admin-page-queue .panel {
  border:1px solid var(--color-border-default) !important;
  border-radius:10px !important;
  background:#fff !important;
  box-shadow:none !important;
}
#admin-page-companion #companionBox > div:first-child {
  padding:16px !important; background:#fff !important;
  border:1px solid var(--color-border-default) !important; border-radius:10px !important;
}
#admin-page-companion #companionBox h3,
#admin-page-companion #companionBox [style*="color: #0969da"],
#admin-page-companion #companionBox [style*="color:#0969da"] { color:var(--color-fg-default) !important; }
#admin-page-companion #companionSelector,
#admin-page-talk .device-selector { background:var(--color-canvas-subtle) !important; border:1px solid var(--color-border-muted); border-radius:8px; }
#admin-page-companion .btn-group,
#admin-page-companion .input-row { background:transparent !important; }
#admin-page-companion button,
#admin-page-talk button,
#admin-page-messages button,
#admin-page-queue button,
#admin-page-location button,
#admin-page-map button {
  background:var(--color-btn-bg) !important; color:var(--color-fg-default) !important;
  border:1px solid var(--color-border-default) !important; box-shadow:none !important;
}
#admin-page-companion button.btn-primary,
#admin-page-talk button.btn-primary { background:#24292f !important; color:#fff !important; border-color:#24292f !important; }
#admin-page-companion .btn-danger { color:var(--color-fg-default) !important; }
#admin-page-companion input[type="text"],
#admin-page-talk input[type="text"] { border-color:var(--color-border-default) !important; box-shadow:none !important; }
#admin-page-companion [style*="border-top: 1px dashed"],
#admin-page-companion [style*="border-top:1px dashed"] { border:1px solid var(--color-border-muted) !important; border-radius:8px !important; }
#admin-page-companion #companionQueryResult { color:var(--color-fg-muted) !important; }
#admin-page-map #mapContainer { position:relative; margin-top:10px; overflow:hidden; }
#mapLocateBtn {
  position:absolute; z-index:1000; right:14px; bottom:22px; width:48px; height:48px;
  display:flex; align-items:center; justify-content:center; border-radius:50% !important;
  background:#fff !important; border:1px solid #c9d1d9 !important;
  box-shadow:0 2px 9px rgba(0,0,0,.18) !important; color:#24292f !important;
  font-size:22px; padding:0 !important; cursor:pointer; touch-action:manipulation;
}
.device-current-dot { width:18px; height:18px; border-radius:50%; background:#1677ff; border:3px solid #fff; box-shadow:0 1px 7px rgba(0,0,0,.38); }
.device-current-dot.latest { width:22px; height:22px; background:#0969da; }
.admin-page .admin-subpage-intro { background:#fff !important; border-color:var(--color-border-default) !important; }
@media (max-width:600px) {
  #admin-page-companion #companionBox > div:first-child { padding:12px !important; }
  #admin-page-companion .btn-group { display:grid; grid-template-columns:1fr 1fr; gap:8px; }
  #admin-page-companion .btn-group button { min-height:44px; white-space:normal; }
  #admin-page-companion .input-row { display:flex; flex-direction:column; align-items:stretch; }
  #admin-page-companion .input-row button { width:100%; }
  #mapLocateBtn { display:flex !important; right:12px; bottom:16px; width:50px; height:50px; z-index:1000; }
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
        <!-- ★ 2026-11-20 新增：HTTP 下行指令队列查看/清空/撤回入口 -->
        <button onclick="showCommandQueue()">📬 指令队列</button>
    </div>
    <details style="margin-top: 16px; border-top: 1px solid var(--color-border-muted); padding-top: 12px;">
        <summary>⚙️ 高级设置与系统工具</summary>
        <!-- ★ v2.5.3 管理台统一操作框架：高级设置目标与陪伴/对讲机选择器彻底隔离 -->
        <div style="margin-top:12px;padding:10px 12px;border:1px solid var(--color-border-default);border-radius:6px;background:var(--color-canvas-subtle);">
            <div style="font-weight:600;font-size:13px;">🎯 高级设置操作范围</div>
            <div class="admin-scope-hint">先选择目标设备，再执行下方设备操作。选择“全部设备”才会全局执行；此选择不会影响对讲机和远程陪伴。</div>
            <div class="admin-device-selector" id="adminDeviceSelector">
                <label class="device-tag"><input type="checkbox" class="admin-target-cb" value="all"><span>全部设备</span></label>
                <span style="font-size:12px;color:var(--color-fg-muted);">正在加载设备列表…</span>
            </div>
        </div>
        <div class="btn-group" style="margin-top: 12px;">
            <button onclick="sendAdminCmd('lock_chat')">🔒 锁定聊天</button>
            <button onclick="sendAdminCmd('unlock_chat')">🔓 解锁聊天</button>
            <button onclick="sendAdminCmd('set_cloud_log', '1')">☁️ 开启云端日志</button>
            <button onclick="sendAdminCmd('set_cloud_log', '0')">☁️ 关闭云端日志</button>
            <button class="btn-danger" onclick="sendAdminCmd('play_alarm')">🔊 选定设备测试音频</button>
            <button id="toggleMapBtn" onclick="toggleMap()">🗺️ 展开地图</button>
            <button onclick="showDeviceOfflineView()">🗑️ 查看离线设备</button>
            <button onclick="sendAdminCmd('clear_history')">清空手机历史</button>
            <button class="btn-danger" onclick="sendAdminCmd('clear_chat')">🧹 清空手机聊天</button>
        </div>
        <div class="btn-group" style="margin-top: 12px;">
            <button onclick="sendAdminCmd('upload_log')">📤 拉取日志(主服务器)</button>
            <button onclick="showLogs()">📋 查看手机日志</button>
            <button onclick="sendAdminCmd('locate_once')">📍 手动定位一次</button>
            <button onclick="sendAdminCmd('scan_wifi')">📡 手动扫描 WiFi</button>
            <button onclick="sendAdminCmd('restart_udp')">🔄 重启设备 UDP 监听</button>
            <button class="btn-danger" onclick="sendAdminCmd('test_low_battery')">🧪 模拟低电量</button>
        </div>
        <div class="input-row" style="margin-top: 12px;">
            <input type="text" id="smsNumText" placeholder="输入紧急接收短信的手机号...">
            <button onclick="sendAdminSmsNum()">📱 设置报警号码</button>
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
    <div style="padding: 16px; background: #fff; border: 1px solid #d0d7de; border-radius: 10px;">
        <h3 style="margin-top: 0; color: #24292f; font-size: 16px;">远程陪护</h3>
        <!-- ★ 2026-11-18 补丁C：陪伴面板自带设备勾选区（原先勾选框只在"对讲机"面板里，推图时无处可选） -->
        <div class="device-selector" id="companionSelector"><label class="device-tag"><input type="checkbox" class="companion-cb" value="all" checked><span>所有人 (群发)</span></label></div>
        <div class="btn-group" style="margin-top: 8px;">
            <button class="btn-primary" onclick="sendCmd('companion_on', '', mediaTarget())">开启陪护模式</button>
            <button onclick="sendCmd('companion_off', '', mediaTarget())">关闭陪护模式</button>
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
            <button class="btn-primary" onclick="uploadCompanionMedia()">推送到设备</button>
        </div>

        <div style="color: var(--color-fg-muted); font-size: 12px; margin-top: 8px;">
            * 提示：推送前请先点击“开启陪伴模式”。选择照片或短视频后点击推送，老人屏幕会全屏展示。
        </div>

        <!-- ★ 2026-11-20 新增：陪伴状态与历史明细查询（按 device_id 查询 reports 表） -->
        <div style="margin-top: 14px; padding: 10px; border: 1px solid #d8dee4; background: #fff; border-radius: 6px;">
            <div style="font-weight: 600; font-size: 13px; color: #0969da; margin-bottom: 8px;">陪护状态与历史记录</div>
            <div class="input-row" style="margin-top: 0;">
                <input type="text" id="companionQueryDevId" placeholder="输入或选择 device_id（如 Pixel_6_a1b2）...">
                <button onclick="queryCompanionByDevice()">查询陪护明细</button>
            </div>
            <div id="companionQueryResult" style="margin-top: 10px; font-size: 13px; color: var(--color-fg-default);"></div>
        </div>
    </div>
</div>

<div id="deviceManagementToolbar" class="panel" style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px;">
    <label for="deviceViewFilter">设备视图</label>
    <select id="deviceViewFilter" onchange="filterDeviceRows()" style="min-width:120px;padding:7px;border:1px solid var(--color-border-default);border-radius:6px;">
        <option value="all">全部设备</option><option value="online">在线</option><option value="offline">离线</option><option value="abnormal">异常</option>
    </select>
    <label for="offlineThresholdSelect">离线阈值</label>
    <select id="offlineThresholdSelect" style="min-width:120px;padding:7px;border:1px solid var(--color-border-default);border-radius:6px;">
        <option value="1800">30 分钟</option><option value="3600">1 小时</option><option value="7200">2 小时</option><option value="14400">4 小时</option><option value="21600">6 小时</option><option value="43200">12 小时</option>
    </select>
    <input id="offlineThresholdCustom" type="number" min="5" max="10080" step="1" placeholder="自定义分钟" style="width:112px;padding:7px;border:1px solid var(--color-border-default);border-radius:6px;">
    <button type="button" class="btn-sm" onclick="saveOfflineThreshold()">应用阈值</button>
</div>
<div class="table-responsive">
    <table id="deviceManagementTable">
        <tr>
            <th>设备ID</th>
            <th>系统 / 状态</th>
            <th>电量</th>
            <th>Wi-Fi / IP</th>
            <th>经纬度</th>
            <th class="hide-mobile">定位时间</th>
            <th>收到时间</th>
            <th>队列</th>
            <th class="hide-mobile">周围热点</th>
            <th style="min-width: 210px;">操作</th>
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
                        if gap_min > (MANAGE_OFFLINE_THRESHOLD_S // 60):
                            offline_tag = f"<br><span style='color:#cf222e;font-weight:bold;'>🚨 失联 {gap_min // 60}h{gap_min % 60}m</span>"
                        elif gap_min > 30:
                            offline_tag = f"<br><span style='color:#bf8700;'>⏸ {gap_min} 分钟未上报</span>"
                        else:
                            offline_tag = ""

                        sys_status = f"{os_ver} <br><span style='font-size:12px;color:var(--color-fg-muted);'>{screen_on} | {chat_lock_str}</span>{offline_tag}"

                        lat = location.get('latitude')
                        lng = location.get('longitude')
                        loc_time_ms = location.get('time')
                        age_s = max(0, int((now_ms - loc_time_ms) / 1000)) if isinstance(loc_time_ms, (int, float)) and loc_time_ms > 0 else location.get('age_s')
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
                        local_ip_raw = network.get("local_ip", "0.0.0.0")
                        local_ip = local_ip_raw if local_ip_raw and not local_ip_raw.startswith(("127.", "169.254.")) and local_ip_raw != "0.0.0.0" else (info.get("last_valid_ip") or "未知")
                        source_ip = (info.get("last_valid_ip") or info.get("source_ip") or info.get("udp_source_ip") or local_ip)
                        
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
                        connection_state = "offline" if gap_min > (MANAGE_OFFLINE_THRESHOLD_S // 60) else "online"
                        health_value = str(_ch.get("health") or "unknown").lower()
                        location_stale = isinstance(age_s, (int, float)) and age_s >= 1800
                        abnormal = health_value == "degraded" or location_stale or (isinstance(battery.get("level"), (int, float)) and battery.get("level") <= 15)
                        health_state = "abnormal" if abnormal else "normal"
                        html += (f"<tr data-connection-state='{connection_state}' data-health-state='{health_state}'><td><b>{html_lib.escape(dev_id, quote=True)}</b>"
                                 f"<br>{_badge}<span style='font-size:11px;color:#8c959f'>{_bv} · v{_pv}</span></td>")
                        html += f"<td>{sys_status}</td>"
                        html += f"<td>{battery.get('level', '?')}%</td>"
                        html += f"<td>{wifi_info}</td>"
                        html += f"<td>{coord}</td>"
                        html += f"<td class='hide-mobile'>{format_loc_time(location.get('time', 0))}</td>"
                        html += f"<td>{format_loc_time(info.get('received_at', 0))}</td>"
                        pending_count = len(get_pending_commands(dev_id))
                        dev_id_js = json.dumps(dev_id, ensure_ascii=False)
                        if pending_count:
                            queue_cell = f"<button type='button' class='btn-sm' style='color:#9a6700;' onclick='openDeviceQueue({html_lib.escape(dev_id_js, quote=True)})'>📬 {pending_count} 条指令待执行</button>"
                        else:
                            queue_cell = "<span style='color:var(--color-fg-muted);'>📭 0 条指令待执行</span>"
                        html += f"<td>{queue_cell}</td>"
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
                        html += f"<button class='btn-sm' onclick=\"fillCompanionDevId('{html_lib.escape(dev_id, quote=True)}')\">👨‍👩‍👧 陪伴</button>"
                        html += f"<button class='btn-sm btn-danger' onclick='confirmDeleteDevice({html_lib.escape(dev_id_js, quote=True)}, {pending_count})'>删除</button>"
                        html += f"</td></tr>"
                    except Exception as e:
                        with print_lock:
                            print(f"⚠️ [HTML] 渲染设备 {dev_id} 异常: {e}")
                        html += f"<tr><td colspan='10' style='color:#cf222e;'>设备 {dev_id} 数据异常，已跳过显示 ({e})</td></tr>"

                if not ALL_DEVICES_STATUS:
                    html += "<tr><td colspan='10' align='center' style='padding:40px;color:var(--color-fg-muted);'>暂无设备上报</td></tr>"

                html += """</table></div>
<div id="mapContainer">
    <div id="map"></div>
    <button type="button" id="mapLocateBtn" title="定位到最近上报设备" aria-label="定位到最近上报设备" onclick="locateCurrentDevice()">⌖</button>
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
var deviceMarkersLayer = null;
var polyline = null;
var ADMIN_MAP_DEVICES = __ADMIN_MAP_DEVICES__;
var _latestDeviceMarker = null;
var isMapVisible = false;
var isChatVisible = false;
var isCompanionVisible = false;  // ★ v2.2 远程陪伴面板展开状态
var _offlineDevices = [];
var OFFLINE_THRESHOLD_SECONDS = __OFFLINE_THRESHOLD_SECONDS__;

function showDeviceOfflineView() {
    showAdminPage('devices');
    var filter = document.getElementById('deviceViewFilter');
    if (filter) { filter.value = 'offline'; filterDeviceRows(); }
}

function filterDeviceRows() {
    var filter = document.getElementById('deviceViewFilter');
    var mode = filter ? filter.value : 'all';
    var rows = document.querySelectorAll('#deviceManagementTable tr[data-connection-state]');
    rows.forEach(function(row) {
        var visible = mode === 'all' || row.getAttribute('data-connection-state') === mode || (mode === 'abnormal' && row.getAttribute('data-health-state') === 'abnormal');
        row.style.display = visible ? '' : 'none';
    });
}

function saveOfflineThreshold() {
    var el = document.getElementById('offlineThresholdSelect');
    var custom = document.getElementById('offlineThresholdCustom');
    if (!el) return;
    var seconds = custom && custom.value ? Math.round(Number(custom.value) * 60) : Number(el.value);
    if (!Number.isFinite(seconds) || seconds < 300 || seconds > 604800) {
        showToast('自定义阈值需为 5 至 10080 分钟');
        return;
    }
    sendCmd('set_offline_threshold', String(seconds));
}

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
        renderDeviceMarkers(ADMIN_MAP_DEVICES || []);
        if (window._currentTrackData) {
            renderTrack(window._currentTrackData);
        }
    });

    markersLayer = L.layerGroup().addTo(map);
    deviceMarkersLayer = L.layerGroup().addTo(map);
    renderDeviceMarkers(ADMIN_MAP_DEVICES || []);
    setTimeout(function() { map.invalidateSize(); }, 100);
}

// ★ v2.5.10：设备位置标记使用手机最近一次上报坐标；不读取管理端浏览器 GPS。
function mapDeviceCoord(device) {
    var lat = Number(device.lat), lng = Number(device.lng);
    if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
    if (currentMapType === 'amap') {
        var converted = wgs84togcj02(lng, lat);
        lng = converted[0]; lat = converted[1];
    }
    return [lat, lng];
}
function renderDeviceMarkers(devices) {
    if (!map || !deviceMarkersLayer) return;
    deviceMarkersLayer.clearLayers();
    _latestDeviceMarker = null;
    var valid = [];
    (devices || []).forEach(function(device) {
        var pos = mapDeviceCoord(device);
        if (!pos) return;
        var icon = L.divIcon({className:'', html:'<div class="device-current-dot"></div>', iconSize:[24,24], iconAnchor:[12,12]});
        var marker = L.marker(pos, {icon:icon}).addTo(deviceMarkersLayer);
        var name = escapeHtml(device.device_id || '设备');
        var time = escapeHtml(device.location_time || '时间未知');
        marker.bindPopup('<b>' + name + '</b><br>最近上报位置<br>' + time);
        valid.push({marker:marker, pos:pos, received_at:Number(device.received_at)||0, device_id:device.device_id||''});
    });
    if (!valid.length) return;
    valid.sort(function(a,b){ return b.received_at-a.received_at; });
    _latestDeviceMarker = valid[0];
    // v2.5.11：绘制设备标记不改变地图中心与缩放；保持地图原始默认视野。
    // 用户主动点击悬浮定位按钮时，才跳转到最近上报设备位置。
}
function locateCurrentDevice() {
    if (!map) return showToast('地图尚未初始化');
    if (!_latestDeviceMarker) return showToast('暂无有效设备定位，请先等待手机上报位置');
    map.setView(_latestDeviceMarker.pos, Math.max(map.getZoom(), 16), {animate:true});
    _latestDeviceMarker.marker.openPopup();
}

function toggleMap() {
    if (!document.getElementById('admin-page-map') || !document.getElementById('admin-page-map').classList.contains('active')) {
        showAdminPage('map');
        return;
    }
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

function openTalkPage() {
    showAdminPage('talk');
    var panel = document.getElementById('chatBox');
    if (panel && !isChatVisible) { isChatVisible = true; panel.style.display = 'flex'; fetchChatHistory(); }
}

function toggleChatBox() {
    showAdminPage('talk');
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

function openCompanionPage() {
    showAdminPage('companion');
    var panel = document.getElementById('companionBox');
    if (panel && !isCompanionVisible) { isCompanionVisible = true; panel.style.display = 'block'; fetchDeviceTargets(); }
}

function toggleCompanionBox() {
    showAdminPage('companion');
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
    showAdminPage('map');
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
var SEL_ADMIN = 'admin-target-cb'; // ★ v2.5.3 高级设置面板独立目标选择器

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
    // ★ v2.5.3：高级设置选择器默认不勾选任何设备，避免页面初次打开就隐式全选。
    var el3 = document.getElementById('adminDeviceSelector');
    if (el3) {
        var selectedAdmin = readChecked(SEL_ADMIN);
        var h3 = '<label class="device-tag"><input type="checkbox" class="' + SEL_ADMIN + '" value="all" ' + (selectedAdmin.indexOf('all') !== -1 ? 'checked' : '') + '><span>全部设备</span></label>';
        devices.forEach(function(dev) {
            var checked = selectedAdmin.indexOf(dev.ip) !== -1 ? 'checked' : '';
            h3 += '<label class="device-tag"><input type="checkbox" class="' + SEL_ADMIN + '" value="' + escapeHtml(dev.ip) + '" ' + checked + '><span>' + escapeHtml(dev.id) + '</span></label>';
        });
        if (devices.length === 0) h3 += '<span style="font-size:12px;color:var(--color-fg-muted);">暂无已上报设备</span>';
        if (el3.innerHTML !== h3) el3.innerHTML = h3;
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
    if (target_ip === undefined) target_ip = 'all'; // ★ v2.5.3：不再隐式继承陪伴面板的设备选择状态
    
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

// ★ v2.5.3 管理台统一操作入口：单设备/多设备共用同一套目标解析与请求反馈。
// 这里传给后端的是设备 IP（与 /api/control 现有协议一致），不改变服务端接口。
function getAdminTarget() {
    var selected = readChecked(SEL_ADMIN);
    if (selected.length === 0) return null;
    return selected.indexOf('all') !== -1 ? 'all' : selected;
}

function sendAdminCmd(action, payload) {
    var target = getAdminTarget();
    if (!target) {
        showToast('请先在“高级设置操作范围”选择单台、多个或全部设备！');
        return;
    }
    sendCmd(action, payload === undefined ? '' : payload, target);
}

function sendAdminSmsNum() {
    var num = document.getElementById('smsNumText').value.trim();
    if (!num) { showToast('请先输入报警号码！'); return; }
    sendAdminCmd('set_sms_num', num);
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
    showAdminPage('messages');
    fetch('/api/messages')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var listDiv = document.getElementById('serverMessagesContent');
        if (!listDiv) return;
        listDiv.innerHTML = ''; 
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
    }).catch(function(e) { showToast("拉取云端数据失败: " + e.message); });
}

function showOfflineManager() {
    fetch('/api/cmd_status')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var threshold = OFFLINE_THRESHOLD_SECONDS;
        var offline = [];
        for (var devId in data) {
            var d = data[devId];
            var ago = d.last_report_ago_s;
            if (ago === -1 || ago > threshold) { offline.push({ id: devId, info: d }); }
        }
        if (offline.length === 0) { showToast("当前没有离线设备（超过当前离线阈值未上报）"); return; }
        
        offline.sort(function(a, b) {
            var aa = a.info.last_report_ago_s === -1 ? 999999999 : a.info.last_report_ago_s;
            var bb = b.info.last_report_ago_s === -1 ? 999999999 : b.info.last_report_ago_s;
            return bb - aa;
        });
        _offlineDevices = offline;

        var html = '<div style="background:#fff8c5;border:1px solid #d4a72c;border-radius:6px;padding:10px 12px;margin-bottom:16px;font-size:13px;color:#6b5900;">' +
            '⚠️ <b>删除操作只从当前列表移除，不影响历史数据</b>（reports / history / messages 表全部保留）。<br>' +
            '手机重新上报后会自动回到列表。当前展示标准：<b>' + (threshold/3600) + ' 小时</b>未上报。' +
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
            var queueStr = info.pending > 0 ? '<button type="button" class="btn-sm" style="color:#9a6700;" onclick="openDeviceQueue(' + JSON.stringify(dev.id).replace(/</g, '\\u003c') + ')">📬 ' + info.pending + ' 条指令待执行</button>' : "";

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
            '<button class="btn-danger" onclick="confirmCleanAllOffline()" style="padding:6px 20px;">🗑️ 清理全部超阈值离线设备</button>' +
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
    else detailLines.push("最后活动: " + formatAgeText(ago));
    detailLines.push("", "只从设备管理列表移除，历史定位与上报记录保留。", "设备再次正常上报后会重新出现在列表。");
    if (info.pending > 0) {
        detailLines.push("", "待确认指令：" + info.pending + " 条");
        if (confirm(detailLines.join("\\n") + "\\n\\n现在打开该设备的指令队列？")) openDeviceQueue(dev.id);
        return;
    }
    if (!confirm(detailLines.join("\\n"))) return;
    sendCmd('clean_device', dev.id);
}

function confirmCleanAllOffline() {
    if (!confirm("确认清理所有【超过当前离线阈值未上报】的设备？\\n\\n注意：\\n- 只从设备管理列表移除，历史数据完整保留\\n- 有待确认指令的设备会跳过，不会静默丢弃队列\\n- 设备再次正常上报后会重新出现")) return;
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
    // 🌟 2026-11-19 22:59 终极直播与流媒体智能网关特征库
    var isRemoteUrl = inputVal.indexOf("http://") === 0 || 
                  inputVal.indexOf("https://") === 0 ||
                  /^(rtmp|rtsp|srt|ws|hls):\\/\\//i.test(inputVal) ||  // 兼容原生流媒体协议
                  /(\\.(flv|mp4|m3u8|webm|ts|mkv|mov|mp3|aac))(\\?|$)/i.test(inputVal) || // 完整媒体后缀
                  /(live|stream|play|channel|tv|camera|monitor|wsSecret)/i.test(inputVal) || // 核心商业直播关键字
                  /^([a-zA-Z0-9][-a-zA-Z0-9]{0,62}\\.)+[a-zA-Z0-9][-a-zA-Z0-9]{0,62}(\\/|$)/.test(inputVal); // 纯域名开头自适应

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
        // 🌟 智能协议头防重叠补全门（已完成双反斜杠逃逸，彻底消灭 SyntaxWarning）
        if (!/^[a-zA-Z]+:\\/\\//.test(mediaUrl)) {
            // 如果没有任何协议头（纯域名或流路径开头），自动补全 http://
            mediaUrl = "http://" + mediaUrl;
        }
        
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



// ==================== [HTTP 下行指令队列：查看 / 撤回 / 清空] ====================
// ★ 2026-11-20 新增：拉取 /api/cmd_status 的 commands 明细，支持单条撤回(cancel_cmd)、
//   按设备清空与清空全部(clear_cmd_queue)，全部经由受 CONTROL_TOKEN 保护的 POST /api/control。
function openDeviceQueue(devId) {
    showCommandQueue(devId);
}

function confirmDeleteDevice(devId, pendingCount) {
    var msg = '确认从设备管理列表删除设备：' + devId + '？\\n\\n历史定位与上报记录会保留；设备再次正常上报后会重新出现在列表。';
    if (pendingCount > 0) {
        msg += '\\n\\n该设备还有 ' + pendingCount + ' 条待确认指令。请先点击队列数量进入指令管理，撤回或处理完毕后再删除。';
        if (confirm(msg + '\\n\\n现在打开该设备的指令队列？')) openDeviceQueue(devId);
        return;
    }
    if (confirm(msg)) sendCmd('clean_device', devId);
}

function formatAgeText(seconds) {
    seconds = Math.max(0, Number(seconds) || 0);
    if (seconds < 60) return seconds < 5 ? '刚刚' : Math.floor(seconds) + '秒前';
    if (seconds < 3600) return Math.floor(seconds / 60) + '分钟前';
    if (seconds < 86400) {
        var h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60);
        return h + '小时' + (m ? m + '分钟' : '') + '前';
    }
    var d = Math.floor(seconds / 86400), hh = Math.floor((seconds % 86400) / 3600);
    return d + '天' + (hh ? hh + '小时' : '') + '前';
}

function showCommandQueue(targetDevId) {
    showAdminPage('queue');
    fetch('/api/cmd_status')
    .then(function(res) { return res.json(); })
    .then(function(data) {
        var devIds = Object.keys(data || {});
        var totalPending = 0;
        var bodyHtml = '';

        if (targetDevId) devIds = devIds.filter(function(id) { return id === targetDevId; });
        devIds.forEach(function(devId) {
            var d = data[devId] || {};
            var cmds = d.commands || [];
            var feedback = d.recent_feedback || [];
            if (!cmds.length && !feedback.length && (!d.pending || d.pending <= 0)) return;
            totalPending += (d.pending || cmds.length);

            bodyHtml += '<div style="border:1px solid var(--color-border-default);border-radius:6px;padding:12px 14px;margin-bottom:12px;background:#fafbfc;">' +
                '<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:8px;">' +
                    '<b>' + escapeHtml(devId) + ' <span style="color:#57606a;font-weight:400;font-size:12px;">(' + (d.pending || cmds.length) + ' 条待发)</span></b>' +
                     (cmds.length ? '<button class="btn-sm btn-danger" data-device-id="' + escapeHtml(devId) + '" onclick="clearDeviceQueue(this.dataset.deviceId)">清空该设备队列</button>' : '') +
                '</div>' +
                '<div style="font-size:12px;color:#57606a;margin-bottom:8px;">网络：' + escapeHtml(d.network_type || '未知') +
                    (d.network_subnet_prefix ? ' · 子网 ' + escapeHtml(d.network_subnet_prefix) : '') +
                    (d.network_interface ? ' · 接口 ' + escapeHtml(d.network_interface) : '') +
                    (d.network_validated === true || d.network_validated === 1 ? ' · 已验证联网' : (d.network_validated === false || d.network_validated === 0 ? ' · 未验证联网' : '')) +
                    (d.network_changed_at ? ' · 状态变更 ' + escapeHtml(new Date(Number(d.network_changed_at)).toLocaleString()) : '') +
                '</div>';

            if (!cmds.length) {
                bodyHtml += '<div style="color:#57606a;font-size:12px;">暂无待执行指令</div>';
            } else {
                cmds.forEach(function(c) {
                    var cid = String(c.id || '');
                    var ctype = String(c.type || '');
                    var cpay = String(c.payload || '');
                    var ageTxt = formatAgeText(c.age_s || 0);
                    bodyHtml += '<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;padding:6px 0;border-top:1px dashed var(--color-border-muted);font-size:12px;">' +
                        '<div style="overflow:hidden;text-overflow:ellipsis;">' +
                            '<b>' + escapeHtml(ctype) + '</b> ' +
                            '<span style="color:#8c959f;">[' + escapeHtml(cid) + ' · ' + escapeHtml(ageTxt) + ']</span>' +
                            (cpay ? '<div style="color:#57606a;word-break:break-all;">载荷: ' + escapeHtml(cpay) + '</div>' : '') +
                        '</div>' +
                         '<button class="btn-sm" data-device-id="' + escapeHtml(devId) + '" data-command-id="' + escapeHtml(cid) + '" onclick="cancelSingleCmd(this.dataset.deviceId, this.dataset.commandId)">撤回</button>' +
                    '</div>';
                });
            }
            if (feedback.length) {
                var statusNames = {received:'设备已收到', executing:'设备处理中', retry_wait:'等待重试', accepted:'设备处理入口已接受', uncertain:'执行结果待核实（未自动重放）', succeeded:'执行成功', failed:'执行失败'};
                bodyHtml += '<div style="margin-top:8px;padding-top:8px;border-top:1px solid var(--color-border-muted);font-size:12px;">' +
                    '<b style="color:#57606a;">最近设备反馈</b>';
                feedback.slice(0, 8).forEach(function(f) {
                    var status = String(f.status || 'unknown');
                    var when = f.updated_at ? new Date(Number(f.updated_at) * 1000).toLocaleString() : '';
                    bodyHtml += '<div style="padding:5px 0;border-top:1px dashed var(--color-border-muted);">' +
                        '<b>' + escapeHtml(f.type || '指令') + '</b> · ' + escapeHtml(statusNames[status] || status) +
                        (f.id ? ' <span style="color:#8c959f;">[' + escapeHtml(f.id) + ']</span>' : '') +
                        (when ? '<span style="color:#8c959f;float:right;">' + escapeHtml(when) + '</span>' : '') +
                        (f.detail ? '<div style="color:#57606a;word-break:break-word;">' + escapeHtml(f.detail) + '</div>' : '') +
                    '</div>';
                });
                bodyHtml += '</div>';
            }
            bodyHtml += '</div>';
        });

        var headerBar = '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;">' +
            '<span style="font-size:13px;color:#57606a;">当前累计待发：<b>' + totalPending + '</b> 条</span>' +
            '<button class="btn-sm btn-danger" onclick="clearAllQueues()">🧹 清空全部队列</button>' +
        '</div>';

        var queueContent = document.getElementById('commandQueueContent');
        if (!queueContent) return;
        queueContent.innerHTML = headerBar + (bodyHtml || '<p style="color:var(--color-fg-muted);">当前没有待下发的指令。</p>');
    })
    .catch(function(e) { showToast("获取指令队列失败: " + e.message); });
}

function cancelSingleCmd(devId, cmdId) {
    fetch('/api/control', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            token: ADMIN_TOKEN,
            action: 'cancel_cmd',
            target_device_id: devId,
            cmd_id: cmdId
        })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        showToast(data.msg || (data.status === 'ok' ? '已撤回' : '撤回失败'));
        if (data.status === 'ok') setTimeout(showCommandQueue, 200);
    })
    .catch(function(e) { showToast("撤回请求失败: " + e.message); });
}

function clearDeviceQueue(devId) {
    if (!confirm("确认清空设备 [" + devId + "] 的全部待发指令？")) return;
    fetch('/api/control', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            token: ADMIN_TOKEN,
            action: 'clear_cmd_queue',
            target_device_id: devId
        })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        showToast(data.msg || (data.status === 'ok' ? '已清空' : '清空失败'));
        if (data.status === 'ok') setTimeout(showCommandQueue, 200);
    })
    .catch(function(e) { showToast("清空请求失败: " + e.message); });
}

function clearAllQueues() {
    if (!confirm("确认清空【所有设备】的待发指令队列？")) return;
    fetch('/api/control', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            token: ADMIN_TOKEN,
            action: 'clear_cmd_queue',
            target_device_id: 'all'
        })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        showToast(data.msg || (data.status === 'ok' ? '已清空全部' : '清空失败'));
        if (data.status === 'ok') setTimeout(showCommandQueue, 200);
    })
    .catch(function(e) { showToast("清空全部失败: " + e.message); });
}

// ==================== [陪伴状态与历史明细查询] ====================
// ★ 2026-11-20 新增：按 device_id 查询 reports 表陪伴开关与各推送 URL 落库明细。
function fillCompanionDevId(devId) {
    var inp = document.getElementById('companionQueryDevId');
    if (inp) { inp.value = devId || ''; }
    if (!isCompanionVisible) { toggleCompanionBox(); }
    queryCompanionByDevice();
}

function formatTsMs(ts) {
    if (!ts) return '-';
    try { return new Date(Number(ts)).toLocaleString(); }
    catch (e) { return String(ts); }
}

function queryCompanionByDevice() {
    var inp = document.getElementById('companionQueryDevId');
    var out = document.getElementById('companionQueryResult');
    if (!inp || !out) return;
    var devId = inp.value ? inp.value.trim() : '';
    if (!devId) { showToast("请输入要查询的 device_id！"); return; }

    out.innerHTML = '<div style="color:var(--color-fg-muted);">正在查询 ' + escapeHtml(devId) + ' 的陪伴状态与历史明细...</div>';

    fetch('/api/companion_status?device_id=' + encodeURIComponent(devId) + '&limit=30')
    .then(function(res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    })
    .then(function(data) {
        if (!data || data.status !== 'ok') {
            out.innerHTML = '<div style="color:#cf222e;">查询失败: ' + escapeHtml((data && data.msg) || '未知错误') + '</div>';
            return;
        }
        var latest = data.latest;
        var history = data.history || [];
        if (!latest || history.length === 0) {
            out.innerHTML = '<div style="color:var(--color-fg-muted);">reports 表中暂无设备 <b>' + escapeHtml(devId) + '</b> 的记录。</div>';
            return;
        }

        var enabledBadge = latest.companion_enabled === 1
            ? '<span style="color:#2da44e;font-weight:600;">🟢 已开启 (1)</span>'
            : (latest.companion_enabled === 0
                ? '<span style="color:#57606a;font-weight:600;">⚪ 已关闭 (0)</span>'
                : '<span style="color:#8c959f;">未知 (null)</span>');

        var html = '<div style="background:#f6f8fa;border:1px solid var(--color-border-default);border-radius:6px;padding:10px 12px;margin-bottom:10px;">' +
            '<div style="font-weight:600;margin-bottom:6px;">最新陪伴状态 — ' + escapeHtml(devId) + '（更新时间: ' + escapeHtml(formatTsMs(latest.timestamp)) + '）</div>' +
            '<div>总开关: ' + enabledBadge + ' | 网络: ' + escapeHtml(latest.network_type || '-') + (latest.wifi_ssid ? ' (' + escapeHtml(latest.wifi_ssid) + ')' : '') + ' | 电量: ' + (latest.battery_level !== null && latest.battery_level !== undefined ? latest.battery_level + '%' : '-') + '</div>' +
            '<div style="margin-top:4px;word-break:break-all;">图片URL: ' + escapeHtml(latest.push_photo_url || '-') + '</div>' +
            '<div style="word-break:break-all;">视频URL: ' + escapeHtml(latest.push_video_url || '-') + '</div>' +
            '<div style="word-break:break-all;">音频URL: ' + escapeHtml(latest.push_audio_url || '-') + '</div>' +
            '<div style="word-break:break-all;">网页URL: ' + escapeHtml(latest.push_web_url || '-') + '</div>' +
        '</div>';

        html += '<div style="overflow-x:auto;border:1px solid var(--color-border-default);border-radius:6px;">' +
            '<table style="min-width:680px;font-size:12px;">' +
            '<tr><th>上报时间</th><th>开关</th><th>图/影/音/网 URL</th><th>网络/电量</th></tr>';

        for (var i = 0; i < history.length; i++) {
            var h = history[i];
            var stTxt = h.companion_enabled === 1 ? '🟢 开' : (h.companion_enabled === 0 ? '⚪ 关' : '-');
            var urls = [];
            if (h.push_photo_url) urls.push('图: ' + h.push_photo_url);
            if (h.push_video_url) urls.push('影: ' + h.push_video_url);
            if (h.push_audio_url) urls.push('音: ' + h.push_audio_url);
            if (h.push_web_url) urls.push('网: ' + h.push_web_url);
            var urlSummary = urls.length > 0 ? urls.join(' | ') : '-';

            html += '<tr>' +
                '<td style="padding:8px 10px;">' + escapeHtml(formatTsMs(h.timestamp)) + '</td>' +
                '<td style="padding:8px 10px;">' + stTxt + '</td>' +
                '<td style="padding:8px 10px;word-break:break-all;">' + escapeHtml(urlSummary) + '</td>' +
                '<td style="padding:8px 10px;">' + escapeHtml(h.network_type || '-') + ' / ' + (h.battery_level !== null && h.battery_level !== undefined ? h.battery_level + '%' : '-') + '</td>' +
            '</tr>';
        }
        html += '</table></div>';
        out.innerHTML = html;
    })
    .catch(function(err) {
        out.innerHTML = '<div style="color:#cf222e;">查询异常: ' + escapeHtml(err.message) + '</div>';
    });
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
        return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
}


// ★ v2.5.5：管理台导航与页面分区。仅重组现有 DOM，不改变 API/协议或业务函数。
// ★ v2.5.10：清除工作页按钮/标题开头的装饰性 emoji，导航图标与消息正文不受影响。
function cleanAdminPageDecorations(page) {
    if (!page || page.getAttribute('data-clean-icons') === '1') return;
    var nodes = page.querySelectorAll('button, h3, h4, .admin-subpage-intro b, .admin-tool-card-head b');
    nodes.forEach(function(node) {
        if (node.children.length) return;
        node.textContent = node.textContent.replace(/^\\s*[\\p{Extended_Pictographic}\\uFE0F\\u200D\\u{1F1E6}-\\u{1F1FF}]+\\s*/u, '');
    });
    page.setAttribute('data-clean-icons','1');
}

function showAdminPage(pageId) {
    var pages = document.querySelectorAll('.admin-page');
    pages.forEach(function(page) { page.classList.toggle('active', page.id === 'admin-page-' + pageId); if (page.id === 'admin-page-' + pageId) cleanAdminPageDecorations(page); });
    var navs = document.querySelectorAll('[data-admin-page]');
    navs.forEach(function(btn) { btn.classList.toggle('active', btn.getAttribute('data-admin-page') === pageId); });
    // ★ v2.5.7：沟通面板互斥；离开页面即收起非当前面板。
    var chatBox = document.getElementById('chatBox');
    var companionBox = document.getElementById('companionBox');
    if (pageId !== 'talk') { if (chatBox) chatBox.style.display = 'none'; isChatVisible = false; }
    if (pageId !== 'companion') { if (companionBox) companionBox.style.display = 'none'; isCompanionVisible = false; }
    // 定位操作与维护操作共享设备范围选择器，仅在相关页面显示。
    var scope = document.getElementById('adminGlobalScope');
    if (scope) scope.classList.toggle('active', pageId === 'maintenance' || pageId === 'location');
    var titleMap = {overview:'仪表盘', devices:'设备管理', talk:'对讲机', companion:'远程陪护', messages:'云端留言', queue:'指令队列', maintenance:'设备维护', map:'实时地图', location:'定位管理', health:'运动健康（规划中）'};
    var title = document.getElementById('adminCurrentTitle');
    if (title) title.textContent = titleMap[pageId] || '管理台';
    var mobilePageName = document.getElementById('adminMobilePageName');
    if (mobilePageName) mobilePageName.textContent = titleMap[pageId] || '管理台';
    var bottomLinks = document.querySelectorAll('[data-mobile-page]');
    bottomLinks.forEach(function(btn) { btn.classList.toggle('active', btn.getAttribute('data-mobile-page') === pageId || (btn.getAttribute('data-mobile-page') === 'more' && ['overview','devices','talk','map'].indexOf(pageId) === -1)); });
    closeAdminMobileNav();
    try { sessionStorage.setItem('admin_last_page', pageId); } catch(e) {}
    if (pageId === 'map' && !map && typeof requestAnimationFrame === 'function') {
        var mapContainer = document.getElementById('mapContainer');
        if (mapContainer) { mapContainer.style.display = 'block'; isMapVisible = true; requestAnimationFrame(function(){ initMap(); }); }
    } else if (pageId === 'map' && map) {
        var mc = document.getElementById('mapContainer'); if (mc) mc.style.display = 'block';
        isMapVisible = true;
        setTimeout(function(){ if (map) map.invalidateSize(); }, 100);
    }
}

function openAdminMobileNav() {
    var sidebar = document.querySelector('.admin-sidebar');
    var backdrop = document.getElementById('adminMobileBackdrop');
    if (sidebar) sidebar.classList.add('mobile-open');
    if (backdrop) backdrop.classList.add('visible');
    var trigger = document.getElementById('adminMobileMenuButton');
    if (trigger) trigger.setAttribute('aria-expanded', 'true');
}
function closeAdminMobileNav() {
    var sidebar = document.querySelector('.admin-sidebar');
    var backdrop = document.getElementById('adminMobileBackdrop');
    if (sidebar) sidebar.classList.remove('mobile-open');
    if (backdrop) backdrop.classList.remove('visible');
    var trigger = document.getElementById('adminMobileMenuButton');
    if (trigger) trigger.setAttribute('aria-expanded', 'false');
}
function toggleAdminMobileNav() {
    var sidebar = document.querySelector('.admin-sidebar');
    if (sidebar && sidebar.classList.contains('mobile-open')) closeAdminMobileNav();
    else openAdminMobileNav();
}
function handleAdminMobileNavKey(event) {
    if (event && event.key === 'Escape') closeAdminMobileNav();
}

function initAdminLayout() {
    if (document.getElementById('adminShell')) return;
    var heading = document.querySelector('body > h2');
    var panel = document.querySelector('body > .panel');
    var chat = document.getElementById('chatBox');
    var companion = document.getElementById('companionBox');
    var table = document.querySelector('body > .table-responsive');
    var deviceToolbar = document.getElementById('deviceManagementToolbar');
    var mapContainer = document.getElementById('mapContainer');
    var modal = document.getElementById('msgModal');
    var toast = document.getElementById('toast');
    if (!heading || !panel || !table) return;

    var details = panel.querySelector('details');
    if (details) details.parentNode.removeChild(details);

    var shell = document.createElement('div'); shell.id = 'adminShell'; shell.className = 'admin-shell';
    var sidebar = document.createElement('aside'); sidebar.className = 'admin-sidebar';
    sidebar.innerHTML = '<div class="admin-brand">设备管理控制台</div>' +
      '<div class="admin-nav-group">工作台</div><nav class="admin-nav" aria-label="管理台主导航">' +
      '<button type="button" data-admin-page="overview" onclick="showAdminPage(&quot;overview&quot;)">▦　仪表盘</button>' +
      '<button type="button" data-admin-page="devices" onclick="showAdminPage(&quot;devices&quot;)">▣　设备管理</button>' +
      '<div class="admin-nav-group">陪护与沟通</div><div class="admin-nav-sub">' +
      '<button type="button" data-admin-page="talk" onclick="openTalkPage()">对讲机</button>' +
      '<button type="button" data-admin-page="companion" onclick="openCompanionPage()">远程陪护</button>' +
      '<button type="button" data-admin-page="messages" onclick="showServerMessages()">云端留言</button>' +
      '<button type="button" data-admin-page="queue" onclick="showCommandQueue()">指令队列</button></div>' +
      '<div class="admin-nav-group">地图与定位</div><div class="admin-nav-sub">' +
      '<button type="button" data-admin-page="map" onclick="showAdminPage(&quot;map&quot;)">实时地图</button>' +
      '<button type="button" data-admin-page="location" onclick="showAdminPage(&quot;location&quot;)">定位管理</button></div>' +
      '<div class="admin-nav-group">系统</div>' +
      '<button type="button" data-admin-page="maintenance" onclick="showAdminPage(&quot;maintenance&quot;)">设备维护</button>' +
      '<button type="button" data-admin-page="health" onclick="showAdminPage(&quot;health&quot;)">运动健康</button>' +
      '</nav>';
    var workspace = document.createElement('main'); workspace.className = 'admin-workspace';
    workspace.innerHTML = '<div class="admin-mobile-title">📱 设备管理控制台</div><div class="admin-page-head"><div><h3 id="adminCurrentTitle">仪表盘</h3><div class="admin-page-desc">设备状态、陪护服务与系统维护统一入口</div></div></div>';
    var pageDefs = [
      ['overview','仪表盘','系统概况与快捷操作'],
      ['devices','设备管理','查看设备在线状态、电量、网络和定位信息'],
      ['talk','对讲机','局域网语音沟通与设备选择'],
      ['companion','远程陪护','陪护模式、媒体推送与陪护记录'],
      ['messages','云端留言','查看服务器保存的留言记录'],
      ['queue','指令队列','查看待执行指令、撤回与队列清理'],
      ['map','实时地图','查看设备当前位置与地图分布'],
      ['location','定位管理','手动请求设备定位与网络辅助诊断'],
      ['maintenance','设备维护','日志、诊断、设备配置和系统工具'],
      ['health','运动健康','为后续经用户授权的运动数据功能预留独立模块']
    ];    var globalScope = document.createElement('div'); globalScope.id = 'adminGlobalScope'; globalScope.className = 'admin-global-scope';
    workspace.appendChild(globalScope);
    var pages = {};
    pageDefs.forEach(function(def){
        var section = document.createElement('section'); section.id = 'admin-page-' + def[0]; section.className = 'admin-page';
        section.setAttribute('aria-label', def[1]); pages[def[0]] = section; workspace.appendChild(section);
    });
    shell.appendChild(sidebar); shell.appendChild(workspace);
    var mobileTopbar = document.createElement('header');
    mobileTopbar.className = 'admin-mobile-topbar';
    mobileTopbar.innerHTML = '<button type="button" id="adminMobileMenuButton" aria-label="打开导航菜单" aria-expanded="false" onclick="toggleAdminMobileNav()">☰</button><div class="mobile-brand">📱 设备管理中心</div><div class="mobile-page-name" id="adminMobilePageName">仪表盘</div>';
    var backdrop = document.createElement('div'); backdrop.id = 'adminMobileBackdrop'; backdrop.className = 'admin-mobile-backdrop';
    backdrop.setAttribute('aria-hidden', 'true'); backdrop.onclick = closeAdminMobileNav;
    var mobileBottom = document.createElement('nav'); mobileBottom.className = 'admin-mobile-bottom'; mobileBottom.setAttribute('aria-label','手机快捷导航');
    mobileBottom.innerHTML = '<button type="button" data-mobile-page="overview" onclick="showAdminPage(&quot;overview&quot;)"><span class="nav-ico">▦</span><span>首页</span></button>' +
      '<button type="button" data-mobile-page="devices" onclick="showAdminPage(&quot;devices&quot;)"><span class="nav-ico">▣</span><span>设备</span></button>' +
      '<button type="button" data-mobile-page="talk" onclick="openTalkPage()"><span class="nav-ico">🗣</span><span>对讲</span></button>' +
      '<button type="button" data-mobile-page="map" onclick="showAdminPage(&quot;map&quot;)"><span class="nav-ico">🗺</span><span>地图</span></button>' +
      '<button type="button" data-mobile-page="more" onclick="toggleAdminMobileNav()"><span class="nav-ico">☰</span><span>更多</span></button>';
    heading.insertAdjacentElement('afterend', mobileTopbar);
    mobileTopbar.insertAdjacentElement('afterend', backdrop);
    backdrop.insertAdjacentElement('afterend', shell);
    document.body.appendChild(mobileBottom);
    document.addEventListener('keydown', handleAdminMobileNavKey);

    // 仪表盘保留原快捷操作与群发提示，不复制按钮或事件。
    pages.overview.appendChild(panel);
    // 高级设置独立成为维护页，仍复用原有按钮和 sendAdminCmd。
    if (details) {
        var maintenanceIntro = document.createElement('div');
        maintenanceIntro.className = 'panel';
        maintenanceIntro.innerHTML = '<b>设备维护与诊断</b><div class="admin-page-desc">先选择设备范围，再执行相应操作；离线设备的操作结果以队列状态为准。</div>';
        pages.maintenance.appendChild(maintenanceIntro);
        pages.maintenance.appendChild(details);
        details.open = true;

        // ★ v2.5.6：高级工具按任务分组，复用原按钮节点与事件，不复制业务逻辑。
        var scopeBox = details.querySelector('#adminDeviceSelector');
        var scopePanel = scopeBox ? scopeBox.parentNode : null;
        var originalGroups = Array.prototype.slice.call(details.querySelectorAll(':scope > .btn-group'));
        var smsRow = details.querySelector(':scope > .input-row');
        var toolGrid = document.createElement('div');
        toolGrid.className = 'admin-tool-grid';
        var toolDefs = [
          {id:'control', title:'设备控制', desc:'聊天权限、日志开关与设备提示'},
          {id:'location', title:'位置与网络', desc:'定位、地图与网络诊断'},
          {id:'diagnostics', title:'日志与服务诊断', desc:'设备日志、离线管理与服务维护'},
          {id:'maintenance', title:'数据维护与测试', desc:'清理操作和测试指令'}
        ];
        var toolCards = {};
        toolDefs.forEach(function(def){
          var card = document.createElement('section');
          card.className = 'admin-tool-card';
          card.innerHTML = '<div class="admin-tool-card-head"><b>' + def.title + '</b><span>' + def.desc + '</span></div><div class="admin-tool-actions"></div>';
          toolCards[def.id] = card;
          toolGrid.appendChild(card);
        });
        function putAction(id, button) {
          if (button) toolCards[id].querySelector('.admin-tool-actions').appendChild(button);
        }
        originalGroups.forEach(function(group){
          Array.prototype.slice.call(group.querySelectorAll('button')).forEach(function(button){
            var action = button.getAttribute('onclick') || '';
            if (/lock_chat|unlock_chat|set_cloud_log|play_alarm/.test(action)) putAction('control', button);
            else if (/locate_once|scan_wifi|toggleMap/.test(action)) putAction('location', button);
            else if (/upload_log|showLogs|showOfflineManager|restart_udp/.test(action)) putAction('diagnostics', button);
            else putAction('maintenance', button);
          });
          group.remove();
        });
        if (scopePanel) {
          scopePanel.className = 'admin-scope-panel';
          globalScope.appendChild(scopePanel);
        }
        // ★ v2.5.7：手动定位与 WiFi 扫描移入独立定位管理页；地图由左侧实时地图菜单承载。
        var locationPageActions = document.createElement('div');
        locationPageActions.className = 'panel admin-subpage-intro';
        locationPageActions.innerHTML = '<b>定位请求与网络辅助</b><div class="admin-page-desc">先在上方选择目标设备，再执行定位或 WiFi 诊断。定位结果以设备实际上报为准。</div><div class="admin-subpage-actions"></div>';
        var locationActions = locationPageActions.querySelector('.admin-subpage-actions');
        ['locate_once','scan_wifi'].forEach(function(token){
          var btn = toolGrid.querySelector('button[onclick*="' + token + '"]');
          if (btn) locationActions.appendChild(btn);
        });
        var locationToolCard = toolCards.location;
        if (locationToolCard && !locationToolCard.querySelector('.admin-tool-actions').children.length) locationToolCard.classList.add('is-empty');
        pages.location.appendChild(locationPageActions);
        var mapIntro = document.createElement('div'); mapIntro.className = 'panel admin-subpage-intro';
        mapIntro.innerHTML = '<b>设备实时地图</b><div class="admin-page-desc">地图页面独立展示设备位置与轨迹。点击左侧实时地图即可进入。</div>';
        var mapToggle = toolGrid.querySelector('#toggleMapBtn');
        if (mapToggle) mapIntro.appendChild(mapToggle);
        pages.map.appendChild(mapIntro);
        details.appendChild(toolGrid);
        if (smsRow) {
          smsRow.className = 'admin-sms-row';
          var smsTitle = document.createElement('div');
          smsTitle.className = 'admin-sms-title';
          smsTitle.innerHTML = '<b>紧急联系人</b><span>设置设备用于紧急短信的接收号码</span>';
          smsRow.insertBefore(smsTitle, smsRow.firstChild);
          details.appendChild(smsRow);
        }
        var summary = details.querySelector('summary');
        if (summary) summary.textContent = '设备维护工具';
    }
    if (deviceToolbar) pages.devices.appendChild(deviceToolbar);
    pages.devices.appendChild(table);
    var talkIntro = document.createElement('div'); talkIntro.className = 'panel admin-subpage-intro';
    talkIntro.innerHTML = '<b>局域网对讲机</b><div class="admin-page-desc">本页面只显示对讲机，不与远程陪护面板同时展开。设备选择独立保存。</div>';
    pages.talk.appendChild(talkIntro);
    if (chat) pages.talk.appendChild(chat);
    var companionIntro = document.createElement('div'); companionIntro.className = 'panel admin-subpage-intro';
    companionIntro.innerHTML = '<b>远程陪护</b><div class="admin-page-desc">独立管理陪护模式、媒体推送与陪护记录。</div>';
    pages.companion.appendChild(companionIntro);
    if (companion) pages.companion.appendChild(companion);
    var messagesIntro = document.createElement('div'); messagesIntro.className = 'panel admin-subpage-intro';
    messagesIntro.innerHTML = '<div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;"><div><b>云端留言</b><div class="admin-page-desc">查看服务器保存的留言记录。</div></div><button type="button" onclick="showServerMessages()">刷新</button></div>';
    pages.messages.appendChild(messagesIntro);
    var messagesContent = document.createElement('div'); messagesContent.id = 'serverMessagesContent'; messagesContent.className = 'panel admin-inline-content';
    messagesContent.innerHTML = '<p class="admin-page-desc">点击刷新加载云端留言。</p>'; pages.messages.appendChild(messagesContent);
    var queueIntro = document.createElement('div'); queueIntro.className = 'panel admin-subpage-intro';
    queueIntro.innerHTML = '<div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;"><div><b>设备指令队列</b><div class="admin-page-desc">查看离线设备待执行指令，并按设备撤回或清理。</div></div><button type="button" onclick="showCommandQueue()">刷新</button></div>';
    pages.queue.appendChild(queueIntro);
    var queueContent = document.createElement('div'); queueContent.id = 'commandQueueContent'; queueContent.className = 'panel admin-inline-content';
    queueContent.innerHTML = '<p class="admin-page-desc">点击刷新加载待执行指令。</p>'; pages.queue.appendChild(queueContent);
    if (mapContainer) { pages.map.appendChild(mapContainer); mapContainer.style.display = 'block'; }
    if (modal) document.body.appendChild(modal);
    if (toast) document.body.appendChild(toast);

    // 仪表盘保留一份设备概览入口，避免用户找不到设备列表。
    var deviceJump = document.createElement('div'); deviceJump.className = 'panel';
    deviceJump.innerHTML = '<b>设备状态</b><div class="admin-page-desc">查看当前设备列表和实时上报信息。</div><div style="margin-top:10px"><button class="btn-primary" type="button" onclick="showAdminPage(&quot;devices&quot;)">打开设备管理 →</button></div>';
    pages.overview.appendChild(deviceJump);

    var health = document.createElement('div'); health.className = 'admin-health-placeholder';
    health.innerHTML = '<h3 style="margin-top:0">运动健康模块（规划中）</h3>' +
      '<p>此处预留给后续运动健康功能。当前版本不会启动传感器采集，也不会新增手机端权限或后台任务。</p>' +
      '<div class="admin-health-grid">' +
      '<div class="admin-health-card"><b>步数与活动</b><div class="admin-page-desc">后续可展示日/周活动趋势</div></div>' +
      '<div class="admin-health-card"><b>传感器数据</b><div class="admin-page-desc">后续按授权范围接入设备数据</div></div>' +
      '<div class="admin-health-card"><b>健康数据管理</b><div class="admin-page-desc">独立存储、导出、保留周期和删除控制</div></div>' +
      '</div><p class="admin-page-desc">设计原则：明确授权、可见采集状态、可暂停/停止、独立数据管理，不影响现有陪护与找回业务。</p>';
    pages.health.appendChild(health);

    // 原页面按钮仍可用；切换面板时先进入对应分区。
    showAdminPage('overview');
}

// ★ v2.5.5：先完成页面分区，再加载设备选择器。
initAdminLayout();
(function(){
    var thresholdSelect = document.getElementById('offlineThresholdSelect');
    var thresholdCustom = document.getElementById('offlineThresholdCustom');
    if (thresholdSelect) {
        var currentThreshold = String(OFFLINE_THRESHOLD_SECONDS);
        var known = Array.prototype.some.call(thresholdSelect.options, function(o){ return o.value === currentThreshold; });
        if (known) thresholdSelect.value = currentThreshold;
        else if (thresholdCustom) thresholdCustom.value = String(Math.round(OFFLINE_THRESHOLD_SECONDS / 60));
    }
})();
// ★ v2.5.3：首屏即加载三套独立设备选择器，避免必须先展开陪伴/对讲机才能选择高级设置目标。
fetchDeviceTargets();
</script>
</body></html>"""
                # ★ FIX-1：把服务端令牌注入前端（用 replace 而非 f-string，避免 HTML 里大量 {} 冲突）
                html = html.replace("__ADMIN_TOKEN__", html_lib.escape(CONTROL_TOKEN, quote=True))  # FIX-1B: 注入控制面孔令
                html = html.replace("__OFFLINE_THRESHOLD_SECONDS__", str(MANAGE_OFFLINE_THRESHOLD_S))
                # ★ v2.5.10：将设备最新上报坐标作为地图标记数据注入页面，避免额外轮询或改变 API。
                map_devices = []
                for _dev_id, _info in devices_snapshot_html:
                    _loc = _info.get("location") or {}
                    _lat, _lng = _loc.get("latitude"), _loc.get("longitude")
                    try:
                        _lat, _lng = float(_lat), float(_lng)
                        if -90 <= _lat <= 90 and -180 <= _lng <= 180:
                            map_devices.append({"device_id": str(_dev_id), "lat": _lat, "lng": _lng,
                                                "received_at": int(_info.get("received_at") or 0),
                                                "location_time": format_loc_time(_loc.get("time", 0))})
                    except (TypeError, ValueError):
                        continue
                html = html.replace("__ADMIN_MAP_DEVICES__", json.dumps(map_devices, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/"))
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
            db_ok = False
            db_error = None
            try:
                db_conn = sqlite3.connect('track_history.db', timeout=1)
                db_conn.execute("SELECT 1").fetchone()
                db_conn.close()
                db_ok = True
            except Exception as db_exc:
                db_error = str(db_exc)[:160]
            ready = bool(db_ok and UDP_LISTENING)
            health = {"status": "ok" if ready else "degraded", "alive": True,
                      "ready": ready, "port": 8081,
                      "db_status": "ok" if db_ok else "error",
                      "udp_status": "listening" if UDP_LISTENING else "not_listening"}
            if db_error:
                health["db_error"] = db_error
            body = json.dumps(health, ensure_ascii=False).encode('utf-8')
            status_line = b'HTTP/1.1 200 OK' if ready else b'HTTP/1.1 503 Service Unavailable'
            conn.sendall(status_line + b'\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: '
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
        print(f"🚨 失联检测: 每 5 分钟扫描一次，>{MANAGE_OFFLINE_THRESHOLD_S // 60} 分钟未上报告警")
        print(f"📋 HTTP 下行队列: TTL={CMD_TTL_SECONDS}s, 上限={CMD_MAX_PER_DEVICE}/设备")
        if USING_DEFAULT_CREDENTIALS:
            print("🚨 [安全] 控制面仍在用出厂默认口令。请设 DF_CONTROL_TOKEN=<强口令> 后重启"
                  "（只影响控制台，不会踩到旧设备）。")
        elif CONTROL_TOKEN_FROM_ENV:
            print("🔐 [安全] 控制面已启用独立口令 DF_CONTROL_TOKEN；设备上报口令保持兼容值，旧 APK 不受影响。")
        else:
            print("🔐 [安全] 控制面沿用设备口令（未设 DF_CONTROL_TOKEN）。想让控制台单独用强口令，请设 DF_CONTROL_TOKEN。")
        if not DEVICE_TOKEN_IS_DEFAULT:
            print("🚨 [兼容告警] 你改动了设备口令/UDP密钥（DF_DEVICE_TOKEN / DF_AUTH_TOKEN / DF_RESCUE_SECRET）："
                  "未升级的手机会全部 403 且 UDP 信令静默丢弃。现场有存量设备时请取消这些变量后重启。")
        if CONTROL_IP_ALLOWLIST:
            print(f"🔒 [安全] /api/control 仅接受来源前缀: {', '.join(CONTROL_IP_ALLOWLIST)}")
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
