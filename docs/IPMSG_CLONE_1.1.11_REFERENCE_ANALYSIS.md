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

`readMsg()` 使用 `READMSG` 并带有 `READCHECKOPT` 位。接收端会在 `SENDMSG` 分支发出 `RECVMSG`，并在 `READMSG` 分支构造 `ANSREADMSG` 应答；但进一步解码 sparse-switch 后确认，核心接收分发器没有 33/50 两个入站分支（详见10.6），因此不能称其存在完整的 ACK/已读应答消费路径。协议常量和应答发送动作仍表明它区分“收到消息”与“用户阅读”两种语义。

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


## 10. 深入核查：接收分发、数据报解析与运行状态

### 10.1 接收端到通知端的实际调用链

进一步追踪 `core.IPMessenger$ChildThread.run()`、`core.IPMessenger.receiveMsg()`、`service.ServiceController.receiveMsg()` 及其 Runnable，可确认消息进入应用层后的路径：

1. UDP 接收循环收到数据报后创建 `ChildThread`。
2. `ChildThread` 解析信封并将业务消息交给 `core.IPMessenger.receiveMsg()`。
3. `receiveMsg()` 转交给抽象控制器的 `receiveMsg()` 回调。
4. 当前服务控制器将回调封装为 `ServiceController$3`，通过 `Handler.post()` 投递。
5. Runnable 创建 `IPMessenger Service Receiver ACTION` 广播，携带消息、发送者用户名、主机名及 secret 标志。
6. 动态注册的 `IpMessengerReceiver` 收到广播后执行振动和系统通知展示。

这证明网络接收与通知展示之间有服务层分发边界；通知广播不是 UDP ACK，也不是持久化记录。

### 10.2 数据报有效长度处理存在缺口

- `ChildThread` 只调用 `DatagramPacket.getData()`，再用 GBK 构造字符串。
- 对整个 DEX 的方法引用检查没有发现 `DatagramPacket.getLength()`。
- 当前解析因此没有显式限制到本次数据报的有效字节长度，而是依赖接收缓冲区内容和后续 `trim()`。
- 这会把尾部填充字节也交给字符串解码，并可能把合法尾随空白一并裁掉；它不是可靠的长度边界处理方式。

这是明确的旧实现弱点。新实现应始终使用 packet offset 与有效 length 构造解码范围，并在协议解析前检查长度和编码错误。

### 10.3 接收状态和成员状态没有发现持久化账本

- 当前网络接收分发链最终转为内存回调、Handler 消息和系统通知；该链路未发现数据库写入或文件持久化调用。
- DEX 方法引用中没有 `SQLiteDatabase` 或 `SQLiteOpenHelper`；没有发现与 Outbox/Inbox 等价的消息持久化表实现。
- 成员状态使用 `HashMap`，服务层另有 `ArrayList` 用户列表；`onCreate()` 初始化列表，`refreshuserList()` 清空后重新 login。
- APK 确实使用 `SharedPreferences`，但可见用途包括用户/群组/铃声/secret 等设置；不能据此认定消息正文或接收确认有持久化。
- APK 中出现的文件流和广告组件不能作为消息历史持久化的证据。

结论限定为：在已提取的 DEX 和已追踪的服务消息路径中，没有找到可靠消息账本或重启恢复证据；不把“未发现”扩大为对所有运行时行为的绝对否定。

### 10.4 UDP 生命周期与重试边界

- `core.IPMessenger.run()` 是阻塞式 `DatagramSocket.receive()` 循环；退出时关闭 socket。
- DEX 中没有发现 `DatagramSocket.setSoTimeout()` 调用，接收循环没有基于 socket timeout 的周期性唤醒机制。
- 发送工作线程仍只调用一次 `DatagramSocket.send()`；异常路径打印 IOException，没有 ACK 超时重试状态机。
- `Timer` 引用属于广告组件，不能视为聊天传输重试器。

### 10.5 对 LAN P2P v2 的具体借鉴与反例

1. 保留接收核心与 UI/通知层解耦的边界，但 v2 的 ACK 必须由 Inbox 持久化成功后产生，不能以 Handler 投递或通知展示代替。
2. 保留“接收确认”和“已读通知”两种业务语义；前者确认 durable storage，后者应是独立的可选事件。
3. 不复用旧实现的整缓冲区解码方式；按 UDP datagram 的 offset/length 严格解析。
4. 不复用内存 HashMap/ArrayList 作为可靠设备/消息账本；重启后必须由 SQLite 恢复未完成状态。
5. 不复用单次 UDP send；维持逐 peer ACK、持久化退避、幂等去重和受控重试。

## 11. 分析进度与剩余边界

本轮已把静态核查从协议/线程入口推进到消息回调和系统通知路径，并确认数据报有效长度处理缺口、内存态成员列表以及缺少可见的 SQLite 消息账本。仍未完成：

- 对 `ChildThread.run()` 每个命令分支逐条做完整控制流还原，尤其是 `RECVMSG`、`READMSG`、`ANSREADMSG` 的全部分支条件与调用先后。
- 进一步确认旧协议的成员键组成、重复消息处理及删除/离线语义。
- 动态抓包验证广播、端口、ACK 和阅读通知的实际报文。

本分析仍为 APK 静态逆向，不代表运行时验证；只用于借鉴设计原则，不改变当前 LAN P2P v2 的验收矩阵和原有功能边界。


### 10.6 接收分发器的命令表复核

对 `core.IPMessenger$ChildThread.run()` 中的 sparse-switch payload 做了进一步解码。它先将信封 command 字段解析为整数并取低 8 位，再按以下 6 个 key 分派：

| command key | 对应命令 | 分支用途 |
|---:|---|---|
| 1 | `BR_ENTRY` | 新成员进入，更新内存成员映射并回发成员应答 |
| 2 | `BR_EXIT` | 从内存成员映射移除成员并通知控制器 |
| 3 | `ANSENTRY` | 更新成员信息 |
| 4 | `BR_ABSENCE` | 更新成员状态/信息 |
| 32 | `SENDMSG` | 处理收到的消息；分支中可见向发送端发出 command 33（`RECVMSG`）的路径，再调用消息接收回调 |
| 48 | `READMSG` | 处理阅读通知请求，构造应答并调用 `openMsg` 回调 |

关键反例：该接收分发器的 switch key 列表中没有 33（`RECVMSG`）或 50（`ANSREADMSG`）。在已还原的该分发器中，这两个值不会进入上述六个业务分支，而落入默认路径。因而不能把常量定义、发送 ACK 的代码或命令名称存在，误当作发送方确实消费 ACK 并维护送达/已读状态的证据。

当前静态证据支持更严格的结论：接收方会发出旧协议的收到确认；但在核心接收分发器中没有找到对应的 `RECVMSG` 接收处理分支，也没有找到 `ANSREADMSG` 的消费分支。完整 UI 侧是否另有独立路径仍需逐项排除，但没有看到可与 v2 Outbox ACK 状态机等价的确认闭环。
