# LAN P2P 架构对照与设计决策

更新：2026-11-24

## 本项目的边界

当前目标是 Android 与 PC 在同一局域网内，免中心聊天服务、快速发现、直接发送文本消息，并在丢包、设备离线、IP 变化、进程重启和短暂断网后可靠恢复。

这不是离线灾害 Mesh 网络，也不是手机-PC 全功能协同平台，更不是当前阶段的跨互联网 IM。外部项目的设计必须先经过这个场景边界筛选。

## 外部项目的可借鉴点

### NectarLink

参考：[thtbee/nectarlink](https://github.com/thtbee/nectarlink)

- 值得吸收：协议版本与能力协商、控制流和大数据流分离、连接抽象、文件完整性校验与断点续传、架构决策记录。
- 不直接采用：把 iroh/QUIC、relay、设备配对和双向 TLS 整体替换进当前 LAN UDP 核心。它解决的是手机-PC 多功能连接与跨网络协同，复杂度和目标不同。
- 本项目应用：当前 v2 消息保持小型 UDP 数据报；将来新增图片/文件时另设流式传输路径，不把大文件塞入聊天数据报。

### Knit

参考：[getknit/knit](https://github.com/getknit/knit)

- 值得吸收：传输层接口边界、消息 ID 去重、持久化消息生命周期、有限重试、明确的存储转发状态，以及对广播风暴和资源预算的重视。
- 不直接采用：Wi-Fi Aware + BLE 双无线 Mesh、TTL 泛洪、多跳 custody、周期性 digest 同步。当前 LAN 场景是一跳、固定接收目标；引入这些机制会扩大状态空间和耗电面。
- 本项目应用：继续以 SQLite outbox/inbox、固定收件人快照、逐目标 ACK 和幂等接收为可靠性基础。

### 飞秋安卓版

参考：[zyqg/feiq-android](https://github.com/zyqg/feiq-android)

- 值得吸收：真实 IPMsg/飞秋互操作、UDP 发现与 TCP 文件传输分工、网络切换后重新广播，以及对 AP 隔离和 Windows 防火墙限制的明确处理。
- 不直接采用：把兼容旧 IPMsg 的端口、命令字和历史语义强行并入当前 v2 协议。兼容层与内部可靠投递账本应保持隔离。
- 本项目应用：未来若需要 IPMsg 互通，单独建设适配层，不污染内部消息状态机。

### LanChat 与 KouChat

参考：[zyqg/LanChat](https://github.com/zyqg/LanChat)、[KouChat](https://github.com/blurpy/kouchat)

- 值得吸收：无中心服务、自动发现、点对点连接的简洁使用体验。
- 不把“能发现、能发送”视为可靠性证据；UDP 广播、TCP 连接本身都不能代替消息持久化、接收确认、重复抑制和恢复测试。

## 已采纳的设计决策

### D0：跨端协议解析必须使用严格 UTF-8

- Python 和 Android 都必须拒绝非法 UTF-8 数据报，不允许某一端静默替换非法字节后继续解析。
- 这样可以保持跨端解析一致，并避免无效字节在 JSON/HMAC 规范化前被不同运行时以不同方式处理。
- Android 使用标准库 CharsetDecoder 的 REPORT 策略；不引入第三方依赖。
- v2 UDP 接收必须把原始数据报字节切片直接交给解码器；不得先转成 String 再重新编码，否则非法 UTF-8 可能在严格解码前已被替换。旧版字符串控制命令继续走独立兼容路径。

### D1：发送尝试、发送成功、接收确认是三个不同事实

- 发送尝试记录用于消耗重试预算和安排退避。
- 只有 socket 发送调用成功返回后，才记录该消息目标的成功发送 endpoint。
- ACK 必须来自已验证的实时路由，或与该消息成功发送 endpoint 匹配。
- Android 已按此规则执行；本轮将 Python 三个服务统一到相同语义，并用进程内锁封闭“UDP 已发送、成功 endpoint 尚未落账”的快速 ACK 竞态。
- 旧数据库只做加列迁移，保留原有 endpoint 路由候选。

### D2：保持逐目标可靠投递，不退回广播式“尽力而为”

- 首次发现窗口只用于建立有界收件人快照。
- 一旦目标快照冻结，重试只针对尚未 ACK 的目标。
- 一个目标 ACK 不得替代其他目标 ACK；重复消息必须幂等。
- 不以广播成功、UDP sendto 返回或在线状态冒充最终送达。

### D3：暂不更换传输协议

- 当前局域网文本消息使用小型认证 UDP 数据报，发现和消息控制保持低开销。
- 先把现有 UDP 的路由、ACK、持久化、重试和网络恢复做扎实。
- 若未来跨网络或大文件需求成立，再以独立接口评估 QUIC/TCP 等传输；不得为了技术新颖提前增加依赖。

### D4：Mesh 与跨网络能力延后

- 多跳转发、存储转发 custody、BLE/Wi-Fi Aware 并非当前 LAN 单跳场景的必要条件。
- 只有在产品明确要求离开同一 LAN 仍可通信时，才重新评估这些机制。

### D5：UDP 接收循环只做轻量认证与有界分发

- 接收循环不直接执行 SQLite、ACK 落账或消息展示工作。
- chat.ack 进入独立 ACK 队列；peer.hello/peer.ack 进入独立 FIFO 握手队列；chat.message 进入独立 FIFO 消息队列。
- 每个队列有明确容量和固定 worker；队列满时拒绝当前数据报，由原 message_id 重试、重复 ACK 和 nonce challenge 恢复。
- 不允许无限队列，也不以丢弃持久化账本或提前 ACK 换取吞吐。
- Android 已使用独立 IO 执行器；本轮将同一原则用于三个 Python 服务，并保持各自端口、身份、数据库 scope 和业务处理函数不变。

## 验收边界

外部项目的 README、架构文档和源码只能提供设计参考，不构成本项目的验收证据。本项目仍以自身 CI、SQLite 故障测试、Android/PC 实际互通、网络切换和连续时延/送达率测试为准。

### D6：当前共享密钥是兼容机制，不是设备级安全身份

- 当前 Android APK 将设备 HTTP token 与 LAN `RESCUE_SECRET` 固定编译进应用；Python 服务默认沿用同一出厂值。服务端代码注释也明确说明该出厂值已写入存量 APK，因此不能直接在服务端单边轮换。
- HMAC 可检测数据报被篡改，并证明发送方持有该共享值；但该值可从公开源码/APK 提取，不能证明唯一设备身份，也不提供消息机密性。当前 LAN v2 消息正文不是端到端加密。
- 本轮不修改/轮换该值：它同时影响旧 UDP 控制指令、设备上报和现有 APK 兼容，贸然更换会造成存量设备失联。
- 后续安全阶段应与旧控制口令隔离，设计版本化 LAN 身份：每台设备/服务独立密钥或公私钥、用户确认的配对/信任建立、nonce challenge 绑定密钥身份、可撤销/轮换；如需保密再采用经认证的 AEAD 会话加密。
- v2 可靠性阶段不得把当前 HMAC 描述成 E2E 加密或强设备身份认证；该风险必须在部署边界中明确说明。


### D7：跨端 peer ID 必须是合法 Unicode 标量序列

- Python 的严格 UTF-8 编码会拒绝孤立 UTF-16 surrogate；Java String 的 UTF-8 编码默认可能将其替换为问号字节。若只比较编码后的长度，Android 可能接受 Python 拒绝的身份。
- Android 在 UTF-8 长度计算前显式校验 surrogate pair；合法 supplementary 字符继续允许，孤立高/低 surrogate 必须拒绝。
- 两端还必须一致拒绝 C0/C1 控制字符，并按 UTF-8 字节数而非 UTF-16 字符数执行 128 字节上限。
- 该规则仅用于身份字符串；不对用户消息正文做字符集限制，不改变既有合法身份的格式。


### D8：HMAC 规范化前递归验证全部 JSON 字符串

- 只校验 peer ID 不足以保证跨端规范化一致；孤立 UTF-16 surrogate 也可能出现在消息正文、显示元数据、数组元素或 JSON 对象键中。
- Python 和 Android 必须在 HMAC 规范化前递归检查所有字符串值与对象键，拒绝不能严格编码为 UTF-8 的孤立 surrogate。
- 合法 supplementary Unicode 字符（例如 emoji）继续支持；不对合法正文字符做额外限制。
- Python 编码端与解码端、Android 编码端与解码端必须使用同一边界，避免仅一侧拒绝或替换后继续认证。


### D9：四种 v2 消息类型必须在编解码边界统一字段语义

- 通用 HMAC 信封校验不足以保证业务语义一致。若把错误 JSON 类型交给 Android `optString` 或 Python `str()`，对象/数组等值可能被转换成不同文本，形成跨端行为分叉。
- `peer.hello` 必须携带非空 `nonce`；`peer.ack` 必须携带非空 `reply_nonce`；`chat.message` 必须携带非空 `message_id`、非空 `target_peer_id` 和字符串正文；`chat.ack` 必须携带对应消息/目标 ID 且状态为 `stored`。
- 可选字段也必须约束类型：挑战标志为布尔、能力为字符串数组、端口为合法整数、时间戳为非负整数、展示/服务元数据为字符串。时间戳仍可选，避免把当前未依赖的字段升级成兼容性要求。
- 两端编码和解码均在 HMAC 验证前执行同一类型级 schema；ACK 是否能落账仍由发送者、目标、message_id、接收人快照和成功发送 endpoint/已验证路由共同决定，schema 校验不能替代状态机证明。


### D10：恢复事件必须保留原始作用域

- 网络/监听器重建是全局 LAN 路径事件，可以按全局策略重新开启所有符合冷却条件的耗尽目标。
- 单个 peer 的 nonce challenge 成功只证明该 peer 的新路由，不得重开其他 peer 的重试预算。
- Android 使用独立 peer event set、按 peer_id 的 SQLite 恢复更新和独立 cooldown timer；Python 三服务使用 peer-scoped recovery set 与相同的持久化冷却截止查询。
- 事件在 socket 不可用、存储扫描失败、路由暂缺或执行器繁忙时必须保留；不能因“没有立即可发送的 endpoint”而静默丢弃。
- 冷却 timer 必须携带原始 peer_id。peer-scoped event 不得复用会重开全部目标的 global recovery timer。

### D11：发送尝试登记与 UDP send 错误必须分开处理

- `mark_outgoing_attempt` 成功提交后，有限发送预算已经消耗；后续 UDP send 或成功 endpoint 落账失败，应交给持久化退避，不得再次 force 重开预算。
- 若 attempt admission 本身因存储错误失败，发送尝试尚未被可靠登记，原恢复事件必须保留并延迟重试。
- Python 三服务恢复入口默认普通补发（`recovery_event=False`）；只有经过认证的 peer recovery worker 显式传递恢复许可。
- 这一区分既防止 SQLite 暂时故障丢失恢复事件，也避免将一次发送失败误判为新的网络恢复事件而反复重置预算。


### D9：API 21-22 的 LAN 路径必须保守绑定默认 Wi-Fi

- API 23 之前不能使用当前实现的 Network.bindSocket(DatagramSocket) 方式绑定次级 Wi-Fi 网络，因此不能仅凭设备存在 Wi-Fi 接口就认为默认 socket 可用于 LAN。
- API 21-22 的 LAN 快路径只在系统默认网络确实为 Wi-Fi、且可取得有效子网广播地址时放行；默认路由切换时撤销旧 peer 路由并重绑 listener。
- 旧系统网络代数加入 Wi-Fi BSSID、Network ID 与 DHCP 地址/掩码/网关，覆盖同子网重连但 AP 或 DHCP 路径变化的场景。
- API 23+ 继续以实际 socket-bound Wi-Fi Network 的 LinkProperties 指纹为 LAN 路径权威；单纯 cellular/default Internet route 切换不得误撤销仍可用的 Wi-Fi LAN peer。


### D12：文件传输是独立任务，不是放大的聊天消息

- 每次文件传输使用独立 `transfer_id`；不得复用 `message_id`，不得把文件块塞入 `chat.message` 或扩大当前 1200-byte UDP 数据报。
- Offer 只携带元数据：transfer_id、发送/目标 peer、显示文件名、大小、整文件 SHA-256、创建时间和 MIME 提示。文件名永远不是接收端路径。
- 接收端必须显式 Accept；Accept 前不发送文件数据。拒绝、取消和过期是终态，不得因重试或重复控制帧复活。
- 文件数据使用独立流式连接（首选 TCP），控制面与现有 peer 身份/路由复用但任务账本独立；连接失败可重建，不改变文字消息状态。
- 默认 chunk size 256 KiB，可配置 4 KiB–1 MiB；默认并行块 4、活动任务 4、单文件上限 2 GiB。两端均须执行硬上限与磁盘配额，协商值只能收紧本地限制，不能放宽。
- 每块按 transfer_id + index 唯一定位，必须验证精确长度和 chunk SHA-256。重复块只有在内容摘要一致时才幂等成功；同 index 不同内容必须失败并保留诊断。
- 接收端先写入由应用生成的安全临时块文件，完成 fsync/摘要核验后再提交 chunk ledger；重启恢复时必须复核 ledger 对应的块文件存在、长度和摘要，不能只相信数据库进度。
- 所有块齐备后按序组装到接收端临时文件，验证整文件 SHA-256，成功后才原子 rename 到用户确认的目标位置；校验失败不能报告完成。
- Resume 交换缺块索引或位图，不重传已验证块；Cancel/cleanup 只影响该 transfer_id，不删除聊天消息或其他传输任务。
- 当前 `lan_p2p_transfer.py` 仅实现纯领域校验与状态迁移，不包含 socket、SQLite、文件系统或 UI；后续阶段必须分层实现，不能把该模块描述成已可用的文件传输功能。
