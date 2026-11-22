# Android IPMsg APK 逆向分析与 V2 借鉴结论

状态：核心通信结构与调用关系已完成静态分析；完整 DEX 控制流还原尚未完成。
分支：`feature/lan-p2p-v2-only`

## 1. 分析对象与方法

本次直接检查了工作目录中的两份 APK：

| 文件 | 大小 | DEX | 可见实现 |
|---|---:|---:|---|
| `IPMSG.apk` | 229,671 bytes | 86,348 bytes | `com.ipmsg` / `ipmsg`，Java IP Messenger 1.35 风格 |
| `IPMessenger Clone! 1.1.11.apk` | 119,864 bytes | 204,140 bytes | `jp.co.cyberbeing.ipclone`，core/service/UI 分层 |

使用 ZIP/资源清单、DEX 字符串池、类/方法/字段表及方法调用引用做静态分析，并读取了第一份 APK 的 `ipmsg/resources.properties`。这不是完整 JADX 源码反编译；不能据此声称所有分支、异常处理、重试数值及运行时行为都已还原。

## 2. IPMSG.apk：核心结构

主要类及职责：

- `ipmsg.IPMRecv`：独立接收线程，`run()` 调用 `DatagramSocket.receive()`，构造 `IPMPack/IPMComEvent`，向注册的 `IPMComListener` 分发。
- `ipmsg.IPMSend`：通过 `DatagramSocket.send()` 向 `IPMAddress` 指定的 IP/端口发包。
- `ipmsg.BroadcastSend`：独立广播线程，包含 `255.255.255.255` 和 sleep/重复发送逻辑。
- `ipmsg.NormalSend`：独立普通消息发送线程，带 `receiveReply()` 和监听器注册/移除。
- `ipmsg.IPMsg`：协议/成员核心，包含 `entry()`、`exit()`、`refreshList()`、`receive()`、`sendMsg()`、`sendReadMsg()`、`sendDeleteMsg()`。
- `ipmsg.IPMPack`：报文编码/解码，包含命令、用户、主机、序列号、正文和文件元数据。
- `ipmsg.IPMProxy`：另有 TCP `ServerSocket/Socket` accept、listen、keep-alive、重连线程。
- `ipmsg.RecvFile/SocketSendFile`：文件载荷使用 TCP Socket，与 UDP 消息控制路径分开。

APK 内的 `ipmsg/resources.properties` 明确写有 `ports=2425`、`receiveMax=50`、`version=Java Version, 1.35`、`proxyBroadcastAll=false`。

成员发现命令常量包括 `IPMSG_BR_ENTRY`、`IPMSG_ANSENTRY`、`IPMSG_BR_EXIT`、`IPMSG_BR_ISGETLIST`、`IPMSG_ANSLIST`。成员表使用 `Hashtable`；`makeKey()` 综合来源地址、用户和主机，`IPMPack.compare()` 比较用户、主机、序列号和命令。它不是只靠单一 IP 或显示名维护成员。

消息命令表包括 `IPMSG_SENDMSG`、`IPMSG_RECVMSG`、`IPMSG_READMSG`、`IPMSG_DELMSG`。有独立 `sendReadMsg()` 和普通发送线程 reply listener，说明接收确认、阅读通知、删除通知是不同语义。

`IPMPack.packed()/unpacked()` 使用冒号分隔字段、行分隔和 GBK 编码。这适合传统 IPMsg 兼容，但不应替换本项目 V2 的 JSON/HMAC 信封、稳定 peer ID 和 SQLite 逐目标投递账本。

## 3. IPMessenger Clone! 1.1.11.apk：核心结构

可见独立的：

- `service.IpMessengerService`：Android Service，维护成员列表，提供 Binder，并广播成员列表/接收事件。
- `service.ServiceController`、`core.Controller`：连接 UI/service 与通信核心。
- `core.IPMessenger`：UDP 收发核心，维护 `HashMap`，提供 `login/logout/broadcastMsg/sendMsg/readMsg/receiveMsg/addMember/removeMember`。
- `core.IPMessenger$ChildThread`：接收循环收到数据报后派生子线程处理。
- `service.IpMessengerReceiver`：通过 BroadcastReceiver 处理通知、振动和消息提醒。

`login()/logout()` 经 `makeTelegram()` 构造报文并广播；`broadcastMsg()` 使用 `255.255.255.255`；`sendMsg()` 负责消息发送；`readMsg()` 是独立阅读通知。收包线程把数据报交给 ChildThread，再更新成员映射并通知 service/UI。

这份实现进一步证明：成员发现控制面、消息数据面、service/UI 事件分发应分层，而不是让每条消息都重新广播，或让 UDP 接收线程直接操作 UI。

从可见核心路径看，成员主要由内存 HashMap/List 管理；没有看到与本项目 SQLite Outbox/Inbox 等价的逐设备持久化投递账本。不能据此断言 APK 任何位置都没有持久化，但其可见通信核心不提供等价的崩溃恢复/有界重试模型。每包创建 ChildThread 是旧式简单做法，不应照搬成无界线程池。

## 4. 借鉴到 V2 的内容

| IPMsg 的成熟做法 | V2 应采用的增强形式 |
|---|---|
| BR_ENTRY/ANSENTRY/BR_EXIT 成员机制 | 认证的 `peer.hello/peer.ack`、稳定 peer ID、endpoint lease |
| 已知成员直接单播 | 消息创建时固定目标快照，向已验证 endpoint UDP 单播 |
| 广播承担发现职责 | 首次发现、网络变化、路由失效时广播，不让每条消息都等待发现 |
| 接收循环与 UI 分离 | 接收线程做轻量入口，认证/Inbox/ACK 走独立有界 IO executor |
| RECVMSG 与 READMSG 分离 | `chat.ack(status=stored)` 与未来 `chat.read` 分离 |
| 文件 TCP、消息 UDP | 当前文本仍走 UDP，大载荷未来独立协商 TCP/HTTP |
| 成员身份不只显示名 | V2 使用稳定 peer ID + peer type/service identity，不以 IP 合并设备 |

## 5. 不照搬的内容

1. 不降级为无认证 GBK 冒号报文；保留 V2 HMAC。
2. 不以 IP 作为设备身份；IPMsg 的 user/host 组合也不等于本项目持久化设备身份。
3. 不用内存 HashMap 替代 SQLite Outbox/Inbox、逐设备 ACK、去重和重启恢复。
4. 不用无界 ChildThread 处理全部数据报；保留有界 IO executor 和背压。
5. 不把 RECVMSG 与 READMSG 合并成“已读”。
6. 不把文件传输、远程悬浮留言和对讲混入同一消息队列。
7. 不把旧 IPMProxy keep-alive 当作当前 LAN UDP 投递的前置条件；本轮不改保活。

## 6. 对当前 V2 的直接改进

- FIFO 发送线程只负责有序持久化和 UDP dispatch，不同步等待 ACK；ACK 由独立 IO executor 落账。
- ACK 计数按 peer 去重，重复 ACK 不能消耗其他 peer 的等待槽位。
- 短 ACK 观察窗口异步收尾；迟到 ACK 仍通过持久化目标 endpoint 验证。
- 异步收尾通过发送时的 endpoint 对象快照比对，只清理本次发送所使用且期间未被重新验证替换的旧 peer route；新验证路由不会被旧 ACK grace 任务误删。
- 自动重试有限；耗尽后保留 SQLite 记录，只有真实网络/peer 恢复事件才重新开启有限预算。
- 实机仍需验证：重复包只显示一次、重复包重 ACK、部分 ACK 只补未 ACK 目标、网络切换后重新发现、分阶段发送延迟。

## 7. 结论与未完成项

两份 APK 的核心类结构、关键方法职责、主要调用关系、协议命令类别，以及第一份 APK 的端口配置已经提取，足以支持架构层面的借鉴决策。尚未完成的是所有关键方法的完整 DEX 指令级控制流还原、第二份 APK 的全部常量数值、全部超时/重试分支，以及设备抓包行为对照。

因此准确结论是：核心架构静态逆向已完成，细节级逆向未完；借鉴的是成员发现/应答、成员表、直接单播、收发与 UI 分层、接收确认与阅读通知分离、UDP 消息与 TCP 文件载荷分离，不是照搬旧协议。
