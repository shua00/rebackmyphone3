"""
============================================================
chat.py — 局域网对讲机 Web 前端（代理/独立双模，v2-only）
------------------------------------------------------------
构建批次 : 20261122-P2P-r2
协议版本 : v2-only
最后修改 : 2026-11-22（v2-only；安全文本渲染；peer challenge 与 ACK）
------------------------------------------------------------
本轮约束 : 独立模式不依赖 server2；使用带 message_id 的 v2 聊天帧，响应随机 nonce peer challenge；会话 peer ID 每次启动唯一。
改动记录（倒序，仅列最近 3 条，更早见 CHANGELOG.txt）:
  2026-11-12 22:45  v2.0 通道可视化 + v2.0.1 架构优化 + 修复版本常量
  2026-11-11 19:45  注释规范统一（无逻辑改动）
============================================================
"""

import socket
import threading
import datetime
import json
import time                                      # [F1] 子网广播重试间隔需要
import uuid
import urllib.request
import os
import sqlite3
from http.server import BaseHTTPRequestHandler, HTTPServer

# ================= 配置区 =================
WEB_PORT = 8082
UDP_DEVICE_PORT = 8888
UDP_PORT = 8890  # 独立监听端口，避免与 server2/rescue2 冲突
RESCUE_SECRET = "your_private_secret_key"  # ⚠️ 必须与手机端一致
from lan_p2p_core import load_stable_peer_id, record_peer
PEER_NODE_ID = _load_stable_peer_id(os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_history.sqlite3"), "chat.py")
SERVER2_URL = "http://127.0.0.1:8080"      # server2.py 的地址
# ==========================================

PROXY_MODE = False          # 由启动时探测决定
CHAT_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_history.sqlite3")
print_lock = threading.Lock()
_CHAT_DB_OPS = 0


def _claim_chat_message(sender, message_id, content):
    """Persist independent chat-mode message body and ID before ACK."""
    global _CHAT_DB_OPS
    key = str(message_id or content or "")
    if not key:
        return None
    now = time.time()
    display_time = datetime.datetime.now().strftime("%H:%M:%S")
    try:
        conn = sqlite3.connect(CHAT_DB, timeout=5)
        try:
            conn.execute("PRAGMA busy_timeout=5000")
            conn.execute("""CREATE TABLE IF NOT EXISTS received_chat_messages (
                receiver_scope TEXT NOT NULL, sender TEXT NOT NULL, message_key TEXT NOT NULL,
                content TEXT NOT NULL, received_at REAL NOT NULL, display_time TEXT NOT NULL,
                PRIMARY KEY(receiver_scope, sender, message_key))""")
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("""INSERT OR IGNORE INTO received_chat_messages
                (receiver_scope,sender,message_key,content,received_at,display_time)
                VALUES(?,?,?,?,?,?)""", ("chat.py", str(sender), key, str(content), now, display_time))
            _CHAT_DB_OPS += 1
            if _CHAT_DB_OPS % 128 == 0:
                conn.execute("DELETE FROM received_chat_messages WHERE receiver_scope=? AND received_at<?",
                             ("chat.py", now - 7 * 86400))
                conn.execute("""DELETE FROM received_chat_messages WHERE receiver_scope=? AND rowid IN (
                    SELECT rowid FROM received_chat_messages WHERE receiver_scope=?
                    ORDER BY received_at DESC LIMIT -1 OFFSET 10000)""", ("chat.py", "chat.py"))
            conn.commit()
            return cur.rowcount == 1
        finally:
            conn.close()
    except Exception as e:
        print(f"⚠️ 聊天消息持久化失败，本条不确认: {type(e).__name__}")
        return None


def _load_chat_history():
    try:
        conn = sqlite3.connect(CHAT_DB, timeout=5)
        try:
            conn.execute("PRAGMA busy_timeout=5000")
            conn.execute("""CREATE TABLE IF NOT EXISTS received_chat_messages (
                receiver_scope TEXT NOT NULL, sender TEXT NOT NULL, message_key TEXT NOT NULL,
                content TEXT NOT NULL, received_at REAL NOT NULL, display_time TEXT NOT NULL,
                PRIMARY KEY(receiver_scope, sender, message_key))""")
            rows = conn.execute("""SELECT display_time,sender,content FROM received_chat_messages
                WHERE receiver_scope=? ORDER BY received_at DESC LIMIT 100""", ("chat.py",)).fetchall()
            return [{"time": row[0], "sender": row[1], "content": row[2]} for row in reversed(rows)]
        finally:
            conn.close()
    except Exception as e:
        print(f"⚠️ 对讲历史恢复失败: {type(e).__name__}")
        return []


CHAT_MESSAGES = _load_chat_history()

# 极简 HTML 网页（与原来完全一致，无需改动）
HTML_PAGE = """<!DOCTYPE html>
<html><head><meta charset='utf-8'>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>局域网对讲机</title>
<style>
* { -webkit-tap-highlight-color: transparent !important; outline: none !important; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; margin: 0; background: #f6f8fa; color: #24292f; height: 100vh; display: flex; flex-direction: column; }
.header { background: #fff; border-bottom: 1px solid #d0d7de; padding: 12px 20px; font-weight: 600; font-size: 16px; text-align: center; }
#chatBox { flex: 1; display: flex; flex-direction: column; max-width: 900px; margin: 20px auto; width: 95%; background: #fff; border-radius: 8px; border: 1px solid #d0d7de; box-shadow: 0 2px 8px rgba(0,0,0,0.05); overflow: hidden; }
#chatMessages { flex: 1; overflow-y: auto; padding: 20px; font-size: 14px; line-height: 1.6; }
.chat-msg { margin-bottom: 12px; }
.chat-time { color: #8c959f; font-size: 12px; margin-right: 8px; }
.chat-sender { font-weight: 600; margin-right: 8px; }
.chat-content { background: #f0f3f6; padding: 6px 12px; border-radius: 6px; display: inline-block; word-break: break-all; }
.chat-input-area { display: flex; padding: 15px; border-top: 1px solid #d0d7de; background: #f6f8fa; gap: 10px; }
input[type="text"] { flex: 1; padding: 10px 14px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 15px; }
input[type="text"]:focus { border-color: #0969da; box-shadow: 0 0 0 3px rgba(9,105,218,0.3); }
button { padding: 10px 20px; background: #24292f; color: #fff; border: none; border-radius: 6px; font-size: 15px; font-weight: 600; cursor: pointer; }
button:hover { background: #1b1f24; }
</style>
</head>
<body>
<div class="header">🗣️ 局域网对讲机 (轻量版)</div>
<div id="chatBox">
    <div id="chatMessages"></div>
    <div class="chat-input-area">
        <input type="text" id="chatInput" placeholder="输入消息发送..." onkeypress="handleEnter(event)">
        <button onclick="sendChat()">发送</button>
    </div>
</div>
<script>
var lastLen = 0;
const myName = "Guest_" + Math.floor(Math.random() * 900 + 100);

function handleEnter(e) { if (e.key === 'Enter') sendChat(); }

async function sendChat() {
    let msg = document.getElementById('chatInput').value.trim();
    if(!msg) return;
    document.getElementById('chatInput').value = "";
    await fetch('/send', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({content: msg, sender: myName})
    });
    setTimeout(fetchChatHistory, 200);
}

async function fetchChatHistory() {
    try {
        const res = await fetch('/history');
        const data = await res.json();
        const chatDiv = document.getElementById('chatMessages');

        if (data.length === lastLen) return;
        lastLen = data.length;

        // 使用 DOM + textContent，聊天内容按纯文本显示，防止局域网成员注入 HTML/脚本。
        chatDiv.replaceChildren();
        data.forEach(item => {
            const row = document.createElement("div");
            row.className = "chat-msg";
            const time = document.createElement("span");
            time.className = "chat-time";
            time.textContent = item.time || "";
            const sender = document.createElement("span");
            sender.className = "chat-sender";
            sender.style.color = String(item.sender || "").includes("Guest") ? "#8b949e" : "#0969da";
            sender.textContent = "[" + (item.sender || "Unknown") + "]";
            const content = document.createElement("div");
            content.className = "chat-content";
            content.textContent = item.content || "";
            row.append(time, sender, content);
            chatDiv.appendChild(row);
        });
        chatDiv.scrollTop = chatDiv.scrollHeight;
    } catch(e) {}
    setTimeout(fetchChatHistory, 1500);
}
fetchChatHistory();
</script>
</body></html>"""


def is_server2_running():
    """探测 server2.py 的 Web 端口是否在线（1 秒超时）。"""
    try:
        urllib.request.urlopen(SERVER2_URL + "/api/chat_history", timeout=1)
        return True
    except Exception:
        return False


# [F1] 计算本机子网定向广播地址（与 server2.py 对齐）
def get_subnet_broadcast():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip.startswith("127.") or ip == "0.0.0.0":
            return "255.255.255.255"
        parts = ip.split(".")
        if len(parts) == 4:
            return ".".join(parts[:3]) + ".255"
    except Exception:
        pass
    return "255.255.255.255"


# [F1] 独立模式聊天广播：子网 + 全局双发，每目标 3 次
def broadcast_chat_message(payload_str):
    data = payload_str.encode('utf-8')
    subnet = get_subnet_broadcast()
    if subnet != "255.255.255.255":
        targets = [subnet, "255.255.255.255"]
    else:
        targets = ["255.255.255.255"]

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        for t in targets:
            for target_port in (UDP_DEVICE_PORT, 8887, 8889, UDP_PORT):
                for _ in range(3):
                    try:
                        sock.sendto(data, (t, target_port))
                        time.sleep(0.05)
                    except Exception:
                        pass
    finally:
        sock.close()


# UDP 监听线程：仅独立模式启动
def udp_listener():
    udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        udp_sock.bind(('0.0.0.0', UDP_PORT))
        print(f"🎧 UDP 监听已启动 (端口 {UDP_PORT})，等待对讲机消息...")
        while True:
            # [B2] 1024 → 4096，避免长消息被 UDP 层截断
            data, addr = udp_sock.recvfrom(4096)
            msg = data.decode('utf-8', errors='ignore')
            if msg.startswith("FIND_ME_PEER_HELLO:"):
                parts = msg.split(":", 3)
                if len(parts) == 4 and parts[1] == RESCUE_SECRET:
                    record_peer(CHAT_DB, "chat.py", parts[2], "android", "android-main", addr[0], 8888, "suspect")
                    response = f"FIND_ME_PEER_ACK:{RESCUE_SECRET}:{parts[3]}:{PEER_NODE_ID}".encode("utf-8")
                    try: udp_sock.sendto(response, addr)
                    except OSError: pass
            elif msg.startswith("FIND_ME_CHAT:"):
                # v2-only：聊天统一使用 FIND_ME_CHAT，不再接收旧 FIND_ME_MSG
                parts = msg.split(":", 4)
                if len(parts) == 5 and parts[1] == RESCUE_SECRET:
                    sender = parts[2]
                    message_id = parts[3]
                    content = parts[4]
                    # 保留原有 Guest_/Web_Console 过滤及其ACK语义，防止网页自身广播回环。
                    if sender.startswith("Guest_") or sender == "Web_Console":
                        if message_id:
                            ack = f"FIND_ME_CHAT_ACK:{RESCUE_SECRET}:{message_id}:{PEER_NODE_ID}".encode("utf-8")
                            try: udp_sock.sendto(ack, addr)
                            except OSError: pass
                        continue
                    claim_result = _claim_chat_message(sender, message_id, content)
                    if claim_result is not None:
                        record_peer(CHAT_DB, "chat.py", sender, "android", "android-main", addr[0], 8888, "active")
                    if claim_result is None:
                        continue  # 未持久化成功，不ACK，允许发送端重试
                    if message_id:
                        ack = f"FIND_ME_CHAT_ACK:{RESCUE_SECRET}:{message_id}:{PEER_NODE_ID}".encode("utf-8")
                        try: udp_sock.sendto(ack, addr)
                        except OSError: pass
                    if not claim_result:
                        continue  # 重复消息仍ACK，但不重复展示
                    ts = datetime.datetime.now().strftime("%H:%M:%S")
                    with print_lock:
                        print(f"\n🗣️ [对讲机] {sender} 说: {content}")
                        CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                        if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)
    except OSError as e:
        if e.errno == 98:
            print(f"❌ UDP {UDP_PORT} 被占用（可能 rescue2.py 已在运行）。")
            print(f"   本网页仍可打开，但无法接收手机消息。建议直接访问 {SERVER2_URL}/ 使用内置对讲机。")
        else:
            print(f"❌ UDP 端口 {UDP_PORT} 绑定失败: {e}")
    except Exception as e:
        print(f"❌ UDP 监听异常: {e}")


class RequestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/history':
            if PROXY_MODE:
                # 代理模式：从 server2.py 拉取
                try:
                    raw = urllib.request.urlopen(SERVER2_URL + "/api/chat_history", timeout=3).read()
                    payload = json.loads(raw)
                    out = json.dumps(payload.get("messages", []))
                except Exception:
                    out = json.dumps([])
            else:
                with print_lock:
                    out = json.dumps(CHAT_MESSAGES)
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(out.encode('utf-8'))
        else:
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(HTML_PAGE.encode('utf-8'))

    def do_POST(self):
        if self.path == '/send':
            # [F2] json.loads 加保护，非法 body 不再抛 500
            try:
                length = int(self.headers.get('Content-Length', 0))
                body = self.rfile.read(length).decode('utf-8', errors='ignore')
                data = json.loads(body) if body else {}
            except Exception:
                self.send_response(400)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(b'{"status":"bad_request"}')
                return

            sender = str(data.get("sender", "Web_Guest"))[:64]
            content = str(data.get("content", ""))
            # [F3] content 截断到 4000 字符，避免单条 UDP 包撑爆 MTU
            if len(content) > 4000:
                content = content[:4000]

            if content:
                if PROXY_MODE:
                    # 代理模式：交给 server2.py 的 /api/control 统一广播 + 记录
                    try:
                        req = urllib.request.Request(
                            SERVER2_URL + "/api/control",
                            data=json.dumps({
                                "action": "chat_msg",
                                "payload": content,
                                "target_ip": "all"
                            }).encode('utf-8'),
                            headers={"Content-Type": "application/json"})
                        urllib.request.urlopen(req, timeout=3)
                    except Exception as e:
                        print(f"❌ 代理发送失败: {e}")
                else:
                    # 独立模式：本地记录 + 子网/全局双发广播
                    ts = datetime.datetime.now().strftime("%H:%M:%S")
                    message_id = str(uuid.uuid4())
                    local_saved = _claim_chat_message(sender, message_id, content)
                    if local_saved is None:
                        print("⚠️ 本机网页消息历史持久化失败，仍按原有方式发送")
                    # 保留原来的网页即时显示行为；持久化失败不改变发送与本地显示。
                    with print_lock:
                        print(f"\n💻 [网页] {sender} 说: {content}")
                        CHAT_MESSAGES.append({"time": ts, "sender": sender, "content": content})
                        if len(CHAT_MESSAGES) > 100: CHAT_MESSAGES.pop(0)

                    payload = f"FIND_ME_CHAT:{RESCUE_SECRET}:{sender}:{message_id}:{content}"
                    # [F1] 子网 + 全局双发，每目标 3 次
                    broadcast_chat_message(payload)

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')

    def log_message(self, format, *args):
        pass


if __name__ == '__main__':
    PROXY_MODE = is_server2_running()

    print("=" * 50)
    if PROXY_MODE:
        print("🔗 检测到 server2.py 在线 → chat.py 进入【代理模式】")
        print(f"   聊天记录从 {SERVER2_URL}/api/chat_history 拉取")
        print(f"   发送走 {SERVER2_URL}/api/control (action=chat_msg)")
        print("   不再 bind UDP 8888，与 server2.py 零冲突")
        print("   提示：启动后 server2.py 若重启/掉线，本进程不会自动切换模式，需重启 chat.py")
    else:
        print("🔌 未检测到 server2.py → chat.py 进入【独立模式】")
        print(f"   自行监听 UDP {UDP_PORT}，向 8888/8889/8890 发送子网+全局广播")
        subnet = get_subnet_broadcast()
        print(f"   当前子网广播地址: {subnet if subnet != '255.255.255.255' else '(未检测到内网 IP，仅全局广播)'}")
        threading.Thread(target=udp_listener, daemon=True).start()
    print(f"👉 浏览器访问: http://127.0.0.1:{WEB_PORT}")
    print("=" * 50)

    server = HTTPServer(('0.0.0.0', WEB_PORT), RequestHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n👋 退出。")
