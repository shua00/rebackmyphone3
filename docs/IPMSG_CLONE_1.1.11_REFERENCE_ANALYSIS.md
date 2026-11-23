# IPMessenger Clone! 1.1.11 APK 静态参考分析

分析对象：用户提供的 `IPMessenger Clone! 1.1.11.apk`

## 1. 分析范围与证据边界

本记录来自 APK 的 binary AndroidManifest 与 `classes.dex` 静态提取，包括类/方法引用、常量、部分 DEX 指令和调用关系。APK 文件大小约 119,864 bytes，DEX 解压后约 204,140 bytes。

本记录不是运行时抓包报告。未在真实设备上启动该 APK，也未动态验证广播范围、重试行为、加密选项或不同 Android 版本下的网络表现。对未能由指令级调用关系直接证明的行为，明确标记为推断或待验证。

## 2. APK 与实际服务入口

- 包名：`jp.co.cyberbeing.ipclone`
- 版本：`1.1.11`（versionCode 10）
- Manifest：minSdk 3，targetSdk 10。
- Manifest 权限中可见 `INTERNET`、`VIBRATE`；未见现代 Android 网络状态/多播锁权限声明。
- 服务入口：`IpMessengerService`。
- 实际调用链：`IpMessengerService.onStart()` 创建 `ServiceController`；`ServiceController` 构造函数创建 `core.Controller`；`core.Controller` 再创建并启动 `core.IPMessenger`。
- DEX 同时存在 `jp.co.cyberbeing.ipclone.IPMessenger` 与 `jp.co.cyberbeing.ipclone.core.IPMessenger` 等重复类。当前服务调用链指向 `core` 版本；不能把重复类误认为两套同时运行的独立传输服务。

## 3. 协议常量与消息格式

`core.Constants` 中可直接读取到：

| 常量 | 值 |
|---|---:|
| `PORT` | 2425 |
| `BUFSIZE` | 8192 |
| `PROTOCOL_VER` | 1 |
| `IPMSG_BR_ENTRY` | 1 |
| `IPMSG_BR_EXIT` | 2 |
| `IPMSG_ANSENTRY` | 3 |
| `IPMSG_SENDMSG` | 32 |
| `IPMSG_RECVMSG` | 33 |
| `IPMSG_READMSG` | 48 |
| `IPMSG_DELMSG` | 49 |
| `IPMSG_ANSREADMSG` | 50 |
| `IPMSG_BROADCASTOPT` | 1024 |
| `IPMSG_READCHECKOPT` | 1048576 |

`makeTelegram(int, String)` 的 DEX 指令显示它按冒号分隔追加协议版本常量 `1`、`Date.getTime()/1000` 的秒级时间值、`userName`、`hostName`、命令整数和正文。因此这是旧版 IPMSG 风格的文本信封，不是 LAN P2P v2 JSON envelope。

## 4. 发现与 UDP 生命周期

- `core.IPMessenger.initiate()` 创建绑定到 UDP 2425 的 `DatagramSocket`。
- `login()` 使用 `BR_ENTRY | BROADCASTOPT`（1025）构造报文，并调用 `broadcastMsg()`。
- `logout()` 使用 `BR_EXIT | BROADCASTOPT`（1026）。
- `broadcastMsg()` 使用 `255.255.255.255` 作为广播目标。
- 接收循环调用 `DatagramSocket.receive()`，使用 8192 字节接收缓冲区；每收到一个数据报，就创建并启动一个 `IPMessenger$ChildThread` 解析该包。
- 发送路径将字符串编码为 GBK，创建目标 UDP 数据报，并启动 `IPMessenger$1` 工作线程执行发送。

该实现可供参考的点是：成员发现命令和消息命令分离，广播发现与已知目标消息发送有独立入口。它的广播地址、端口和 8192 字节缓冲区属于旧协议部署约束，不能直接移植到当前 v2 设计。

## 5. 发送、接收确认与阅读通知

DEX 中可见独立命令：

- `SENDMSG`：消息发送命令。
- `RECVMSG`：接收确认命令。
- `READMSG`：阅读通知请求。
- `ANSREADMSG`：阅读通知应答。

`readMsg()` 使用 `READMSG` 并带有 `READCHECKOPT` 位。接收线程也包含对 `RECVMSG` 和 `ANSREADMSG` 命令值的处理路径。由此可以确认协议语义上区分了“消息收到”和“用户阅读”两件事。

但静态命令存在不等于已证明接收确认经过事务持久化。该 APK 的相关代码没有显示与当前项目 SQLite Outbox/Inbox 等价的持久化逐接收方账本，不能把其 `RECVMSG` 直接视为当前 v2 的 durable stored ACK。

## 6. 发送可靠性与并发方式

`IPMessenger$1.run()` 的发送工作线程调用一次 `DatagramSocket.send()`；异常路径记录 IOException，没有在该工作线程内发现基于 ACK 超时的自动重发循环。应用 UI 中存在手动 resend 控件/路径，但这不等于自动可靠投递。

接收端采用“一个长期 UDP 接收循环 + 每个收到的数据报新建一个 ChildThread”的结构。它简单直接，但高包率下会产生线程创建/调度开销，也没有显示出当前项目所需的有界任务队列、逐 peer ACK 状态和持久化退避调度。

静态扫描到的 `Timer` 调用位于 AdMob 广告组件；不能把它误判为 IPMSG 聊天传输重试机制。

## 7. 安全能力边界

APK 常量中存在 `PASSWORDOPT`、`SECRETOPT`、`SECRETEXOPT` 等旧协议选项。但在当前提取的消息传输调用路径中，没有发现 HMAC、`javax.crypto.Mac` 或 `Cipher` 相关实现；可见的 `MessageDigest` 调用属于 AdMob 的 MD5 逻辑。

因此不能把旧协议的 secret/password 选项当作与当前 LAN P2P v2 共享密钥 HMAC、nonce challenge 和来源 endpoint 校验等价的安全证明。是否存在未覆盖的第三方/运行时加密路径仍需动态验证，当前不作更强断言。

## 8. 对当前 LAN P2P v2 的借鉴结论

### 值得保留的设计思想

1. 成员发现、消息发送、消息接收确认、阅读通知应有独立语义和处理入口。
2. 收到消息确认与已读通知不能混为一谈。当前 v2 的 `chat.ack` 只代表 Inbox 持久化；未来若增加已读状态，应使用独立事件和独立持久化状态。
3. 已知目标的消息发送不应每条都重新做全网广播发现；应复用经 nonce challenge 验证的路由，并在网络路径变化时失效。

### 不应照搬的实现

1. 不移植 IPMSG v1 冒号文本协议或 GBK 编码；继续使用统一 v2 JSON/HMAC envelope。
2. 不把 8192 字节缓冲区或大 UDP 数据报上限带入当前协议；继续保持 1200 字节上限，并将大载荷留给后续独立传输层。
3. 不采用每个 UDP 数据报都新建无界线程的方式；继续使用现有有界执行器/快速 ACK 路径。
4. 不用一次性 `send()` 或 UI 手动 resend 替代持久化 Outbox、逐目标 ACK、有界退避和重启恢复。
5. 不以旧协议的 username/hostname/IP 替代稳定 peer_id 和临时 endpoint 分离。

## 9. 当前分析状态

已完成 Manifest、协议常量、实际服务调用链、UDP 端口/缓冲区、核心信封格式、广播发现入口、发送线程和接收线程模型的静态核查。未完成的部分包括完整逐分支 DEX 控制流复核、所有消息命令分支的端到端调用确认、设备运行时抓包，以及不同目标版本/网络条件下的动态行为验证。

本分析只作为设计借鉴，不改变 LAN P2P v2 的协议、验收标准或原有功能边界。
