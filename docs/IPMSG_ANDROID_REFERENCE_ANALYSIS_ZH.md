# IPMsg / Android 局域网通信参考研究与 V2 适配结论

> 研究目的：借鉴成熟 LAN 即时通信的机制，改进本项目 V2 的发现、消息送达、重试和状态管理；不替换 V2 协议，不照搬旧 Android 实现，也不处理本轮范围之外的保活问题。

## 1. 研究对象与证据等级

### 1.1 用户提供的 Android APK

主参考文件：`IPMessenger Clone! 1.1.11.apk`

- APK SHA-256：`94d43d0b381c74ec5b2e38c5ecf97df8f61f073cbab93b54eb8b9e555ce48f0f`
- APK MD5：`2c96ecd3b8f9dae457321421e6085007`
- 包名线索：`jp.co.cyberbeing.ipclone`
- DEX 中可见的类/符号：
  - `IpMessengerService`、`IpMessengerService$IpMessengerBinder`
  - `ServiceController`、`IPMessengerServiceConnection`
  - `IpMessengerReceiver`
  - `IPMessenger`、`IPMessenger$ChildThread`
  - `Send`、`Receive`
  - `USERLIST_ACTION`、`getUserList`
  - `IPMSG_BR_ENTRY`、`IPMSG_ANSENTRY`、`IPMSG_BR_EXIT`
  - `IPMSG_SENDMSG`、`IPMSG_RECVMSG`、`IPMSG_READMSG`、`IPMSG_ANSREADMSG`
  - `DatagramSocket`、`DatagramPacket`、`ExecutorService`、`BindException`

**证据边界：**当前环境已检查 APK ZIP、DEX 字符串、类/方法符号和签名证书，但没有可用的 JADX/Apktool，尚未完成该 APK 的 Java 方法体级反编译。因此，上述类名和命令常量是二进制直接证据；具体线程时序、重试策略和异常分支不能仅凭符号推断。后续取得方法级反编译结果后再补充，不把推测写成事实。

**签名提示：**本次上传文件的签名证书主题为通用 Android 测试证书，而不是应用商店资料列出的 CyberBeing 开发者证书；文件 MD5 也与公开 APK 目录所列 MD5 不同。这可能是导出或重新签名造成的，不足以证明文件有恶意行为，但意味着本次样本不能仅凭文件名认定为开发者原始签名发行包。研究其代码结构不受影响。

### 1.2 上一个 APK：`IPMSG.apk`

补充样本，DEX 中可见：

- `IPMPack`、`IPMRecv`、`IPMSend`
- `IPMProxy`、`IPMProxy$Listen`、`IPMProxy$Accept`、`IPMProxy$KeepAlive`
- `BroadcastSend`、`NormalSend`
- `DatagramSocket`、`ServerSocket`、`Socket`
- `IPMSG_BR_ENTRY`、`IPMSG_ANSENTRY`、`IPMSG_RECVMSG` 等协议常量

它适合补充研究代理/监听/广播和文件传输职责划分；不把它的旧 Android 生命周期实现带入本项目。

### 1.3 公开协议与可读源码

交叉验证采用：

- IP Messenger 官方协议规范：`https://github.com/shirouzu/ipmsg/blob/master/prot-eng.txt`
- 官方较新协议说明：`https://github.com/shirouzu/ipmsg/blob/master/protocol.txt`
- 官方 Windows 参考源码：`https://github.com/shirouzu/ipmsg`
- 独立开源 Android 飞秋实现：`https://github.com/zyqg/feiq-android`

这几项用于核对协议语义和可读实现，不冒充为用户上传 APK 的源代码。

## 2. 成熟 IPMsg 的关键机制

### 2.1 成员发现是独立流程

传统流程：

1. 新成员广播 `BR_ENTRY`。
2. 已在线成员把新成员加入成员列表，并向其单播 `ANSENTRY`。
3. 新成员通过多个 `ANSENTRY` 建立当前成员视图。
4. `BR_EXIT` 通知成员离开；昵称/状态变化另有成员通知。

官方 Windows 实现还对成员响应加入随机延迟，以避免大量成员同时响应造成瞬时突发。

**可借鉴：**发现流程应有自己的生命周期和成员状态，不应每次发送聊天消息时才临时猜测目标地址。

**不照搬：**传统 BR_ENTRY/ANSENTRY 本身不是强身份认证。本项目继续使用 V2 的随机 challenge、认证响应、稳定 peer ID 和 endpoint 验证；IP/SSID/CIDR 只作路由线索，不作为可达或身份证明。

### 2.2 送达确认与阅读确认是两种语义

IPMsg 的主要语义：

- `SENDMSG + SENDCHECKOPT`：请求接收确认。
- `RECVMSG`：接收端确认收到原 packet number。
- `READMSG` / `ANSREADMSG`：封筒/阅读确认相关流程。

**可借鉴：**“对端收到/持久化”不能等同于“用户已读”。协议和 UI 应分别表达。

**本项目决策：**V2 的 `chat.ack` 表示已通过认证并完成接收端持久化，不表示用户已读。未来如需已读回执，应增加独立消息语义，不能复用 `chat.ack`，更不能与原始留言悬浮提醒混为一谈。

### 2.3 UDP 消息、TCP 文件传输分层

传统 IPMsg 使用 UDP 发送消息和控制报文，TCP 传输文件/目录内容。

**可借鉴：**控制面与大数据传输分离，避免大文件或图片分块挤占短消息 ACK 通道。

**本项目决策：**当前先把文字对讲可靠性做稳；未来图片/文件/语音走独立传输任务和进度状态，不把大负载塞进当前短消息 ACK 队列。

### 2.4 有界重试，而非永久定时重发

官方协议允许在确认超时后重发，但具体次数/间隔由实现决定。官方 Windows 参考实现的发送状态机具有重试上限；达到上限后，不会永久后台重发，而是进入延迟/人工重试处理路径。

**可借鉴：**有限自动重试、保留未完成消息、让后续恢复动作有明确入口。

**本项目决策：**采用 SQLite 持久化的有限重试 epoch。每轮最多 8 次自动发送（包含首次发送）；耗尽后状态为 `retry_paused`，消息和未 ACK 目标仍保留。真实网络恢复、监听重新绑定或 peer route 再次验证事件可在每条消息 60 秒冷却约束下重新开启一轮有限预算。定时器不再对耗尽消息无限轮询。

## 3. Android 实现的交叉参考

### 3.1 用户提供的 Clone APK

这次进一步解析了 DEX 的 `class_data`、`code_item` 和方法调用索引，已不只是列出类名，而是还原了核心方法调用关系（仍不是完整 Java 源码反编译）：

- `Controller` 实际创建的是 `jp.co.cyberbeing.ipclone.core.IPMessenger`。
- `IPMessenger.initiate()` 创建 `DatagramSocket(in_port)`；核心对象保存 socket、端口、成员 HashMap 和身份字段。
- `IPMessenger.run()` 是 UDP 接收循环：创建接收 `DatagramPacket`、调用 `DatagramSocket.receive()`，随后为收到的包创建 `IPMessenger$ChildThread` 并启动。
- `IPMessenger$ChildThread.run()` 读取包数据、来源 IP 和端口，解析报文，再调用成员增删、消息接收/打开和发送等处理入口。
- `IPMessenger.send()` 构造目标 `DatagramPacket`，再创建并启动 `IPMessenger$1`；该子线程的 `run()` 最终调用 `DatagramSocket.send()`。
- `broadcastMsg()` 使用 `255.255.255.255`；login/logout/absence 走广播，sendMsg/readMsg 走指定目标发送。
- `IpMessengerService` 持有 userList；`onCreate()` 注册接收广播，`onBind()` 返回 Binder，`onStart()` 创建 `ServiceController` 并处理 `BindException`（端口占用），成员列表变化通过 Intent 广播通知界面，`onDestroy()` 执行 logout。
- `ServiceController` 使用 `Handler.post()` 将成员和消息回调投递给服务/界面层。

**真正值得借鉴：**接收通道、协议引擎、Android Service、Binder 和 UI 通知有明确边界；成员列表变化通过事件通知，而不是让 UI 直接管理 UDP socket。

**不应照搬的地方：**这个 APK 的核心收包路径对每个 UDP 包启动一个 ChildThread，发送路径也为每个 UDP 包启动一个 Thread。它是轻量旧实现，不是现代高并发/高可靠模型；突发包可能造成线程数量膨胀。当前 V2 保留单线程有界 FIFO 发送队列和独立 IO ACK 处理，不复制 thread-per-packet。当前 DEX 方法调用图也没有显示与 V2 相当的 SQLite 持久化 Outbox/Inbox 或逐设备持久化重试账本；不能把传统 IPMsg 的 packet number ACK 等同于 V2 的持久化 ACK。

DEX 中虽然存在 `ExecutorService` 字符串/类型引用，但核心 `IPMessenger` 的调用图实际使用 Thread/ChildThread；不能仅凭全局符号认定它用于协议收发。

**证据边界：**以上是 DEX 方法调用关系和分支/循环结构分析，不是逐行 Java 还原；具体每个命令分支的完整条件仍需 JADX/Smali 级反编译后再确认。

### 3.2 独立开源 Android 飞秋实现

`zyqg/feiq-android` 提供可读代码，值得参考的点：

- `FeiqEngine`：协议解析、peer 列表、收发状态与 UI Flow 分离。
- `UdpChannel`：统一 UDP 监听、广播、单播。
- `MessageRepository`：消息数据与界面通知分离。
- `FeiqService`：网络回调后重建 socket 并重新广播。
- 图片分块 ACK 使用同步快速发送路径，避免每块 ACK 都排入独立协程导致突发丢失。
- 文件传输单独走 TCP。

它的公开实现也有不应照搬之处：聊天历史使用 JSON 文件；peer 列表主要以 IP 为键；普通消息 ACK 通过事件流回传，但没有本项目需要的 SQLite 逐设备 Outbox/Inbox 与完整持久化补发账本。它的价值是职责划分和协议兼容参考，不是本项目可靠投递的最终架构。

## 4. 与本项目 V2 的对照结论

| 能力 | 成熟实现的启发 | V2 应坚持的方向 |
|---|---|---|
| 设备发现 | 主动广播、成员应答、成员列表独立维护 | V2 challenge/response + 稳定 peer ID + 已验证 endpoint |
| 目标选择 | 发现后定向发送 | 固定收件人快照；只向未 ACK 的目标补发 |
| 送达确认 | packet number 对应原消息 | message ID + peer ID + 认证 ACK + SQLite 事务 |
| 阅读状态 | RECVMSG 与 READMSG 分离 | chat.ack 不冒充已读；需要时新增独立语义 |
| 重试 | 超时重发，但实现应有上限 | 每轮 8 次；暂停保留；恢复事件重新开启有限预算 |
| 接收去重 | packet number 关联消息 | receiver scope + sender peer ID + message ID 唯一键 |
| 大数据 | TCP 文件通道 | 后续独立文件/媒体传输，不挤占短消息 ACK |
| Android 分层 | Channel / Engine / Repository / Service | 逐步收敛 TrackingService 中的通信职责，但不为重构而重构 |
| 后台保活 | 有独立 Service | 本轮不处理，保留现有已基本稳定路径 |

## 5. 本轮已落地的针对性改进

1. 移除发送路径固定 `Thread.sleep(500ms)` 的发现等待。发现广播与消息发送并行推进，不再让固定等待占用 FIFO 发送路径。
2. Outbox 重试改为每轮最多 8 次自动发送；耗尽后显式进入 `retry_paused`，不删除消息或未确认目标。
3. 网络/监听/peer 验证恢复事件可重新开启一轮有限预算，并持久化 60 秒冷却时间。
4. 增加恢复事件合并标记，避免事件恰好在重试批次执行中到达而被丢掉。
5. 部分 ACK 后保留已 ACK 目标状态，后续只补发未 ACK 目标；耗尽状态不会因单个部分 ACK 被错误清成普通 pending。
6. SQLite schema 从 v4 升至 v5，采用增量迁移，不重建或清空原消息账本。
7. 最终 ACK 可能晚于 250ms latch 等待完成；在 ACK 落账线程再次检查全部目标并回收 SharedPreferences 兼容队列，防止已送达记录长期占用 50 条入队容量。

## 6. 后续审计与验收顺序

- [x] 确认当前分支 Actions 的 Python 验证与 Android 编译均通过（#285、#286、#287、#288、#289）。
- [ ] 审查所有 ACK 入口：必须匹配 message ID、发送者 peer ID、认证来源和有效 endpoint/发现窗口。
- [ ] 审查接收端顺序：先持久化 Inbox，再 ACK；重复消息不重复展示；存储失败不得 ACK。
- [ ] 验证部分 ACK、8 次耗尽、`retry_paused` 持久化、60 秒冷却、恢复事件重新开启、进程重启后的恢复。
- [ ] 补充接收日志字段：message ID、sender peer ID、duplicate、persisted、ACK 结果；不记录正文或密钥。
- [ ] 分析 FIFO 队列等待、UDP 发送、ACK 等待和端到端持久化延迟，找出实测 6–13 秒发送延迟的真正来源。
- [ ] 上述静态审计和云端构建通过后，再进行双机、多机和网络切换实测。

## 7. 明确不改变的产品约束

- 原有功能只增强不删减。
- 留言悬浮提醒与对讲消息严格分离：不同消息类型、队列、通知、协议和 UI，不互相复用。
- 不改变 Android 聊天窗口打开/隐藏行为。
- 不把 ACK 当作用户已读。
- 不把晚加入设备加入已经冻结的旧消息收件人快照。
- 本轮不改保活策略；除非后续证据证明它直接导致消息可靠性故障。

