# 统一网络感知与自适应路由架构

状态：**项目级 P0 架构原则；分阶段实施。**
适用范围：Android、Python 端及未来 Rescue2 / Server2 控制面。当前 LAN P2P v2 的 IPv4 STA/AP 基础工作不因本文件而中断。macOS 不在当前范围。

## 1. 架构目标

设备不应依赖固定网络假设或单一传输通道，而应感知当前网络环境、目标设备和经过验证的通信路径，按通信需求选择合适路径，并在路径失效/恢复时有界切换。

统一原则：

- **先感知，再决策，再传输**；发现到接口、地址或网段不等于端到端可达。
- **候选路径与可信路径分离**；只有在具体路径上完成身份认证及双向可达验证后，才可作为可信路由。
- **路径级隔离**；认证、端点、socket、listener generation、租约和失败状态均绑定具体路径，不能跨路径借用授权。
- **按目标和通信类型决策**；同一设备的聊天、文件、实时指令等可有不同传输策略，但共享底层感知事实。
- **简单网络快路径优先**；单一普通路由器下两台或多台 Android/Python 设备只使用必要 listener 和已验证 route，不因未来多路径能力增加周期扫描、无效探测、每包枚举或冗余发送。
- **有界故障恢复**；首选路径失败、超时或被明确失效后，才尝试有限备用路径；禁止无界 fan-out。
- **状态可解释、可诊断**；记录稳定错误码、路径变更和决策原因，不记录密钥、聊天正文或文件内容。

## 2. 分层职责

### 2.1 Network Awareness（网络感知）

收集并维护：
- 本地网络/接口生命周期：IPv4 地址、接口、Android Network 绑定信息、STA/SoftAP 等适配器身份。
- 目标设备候选信息：设备身份、发现来源、端点、最后观察时间。
- 路径事实：本地路径 fingerprint、远端 endpoint、socket/listener generation、路径状态。
- 验证事实：认证 peer exchange、双向 reachability 结果、验证时间和有效期。
- 质量事实：实际消息 RTT、ACK 成功/超时、丢包/重试和近期失败；只有有足够观测时才形成质量判断。
- 生命周期事件：出现、变更、失效、恢复、过期。

感知必须事件驱动优先；必要的验证/刷新应有节流、退避和租约，不能对每个 peer 高频轮询。

### 2.2 Route Registry（路径与端点登记）

维护 peer × path 维度的路由绑定。至少包含：
- peer identity；
- path fingerprint；
- remote endpoint；
- 精确 listener/socket 引用及 generation；
- authenticated verification time、expiry/lease；
- 最近成功/失败与有限质量统计。

候选端点不能直接升级为可信 route。一个路径上的认证不得扩散到另一条路径。旧版不含 path 身份的持久化 endpoint 只允许在明确的单路径兼容条件下使用，不得在多路径时作为跨路径凭据。

### 2.3 Adaptive Route Selector（自适应路径选择）

当前代码已引入 `LanAdaptiveRouteSelector`：单 Listener 使用 peer+preferredPath 的 O(1) 查询；多 Listener 才创建候选列表；显式首选路径优先，否则按最近认证时间选择。备用候选 API 仅在调用方主动请求时构造，最多返回 2 条。**这只是路由选择第一阶段，不代表质量评分或发送失败后的自动回退闭环已经完成。**

输入：目标 peer、通信类型/时效性、当前 fresh authenticated route bindings、路径健康事实及发送预算。

输出：一个首选 route binding 和有界备用候选（仅在需要时构造）。

决策约束：
- 稳定单路径时 O(1) 直接使用已验证 binding，不创建多路径重试计划。
- 多路径时优先考虑端到端验证有效性，其次结合近期 RTT、成功率、失败/超时和资源成本；不能仅按网段、接口优先级或 socket bind 成功推断可达性。
- 普通消息一次只走一条首选路径；只有失败/超时/显式失效后才有限回退。
- 选择结果必须携带 path/socket/generation 快照；异步任务发送前复核 binding 仍 current。
- 不允许因路径质量评分而绕过认证、消息收件人快照、ACK 来源校验或安全策略。

### 2.4 Transport Adapters（传输适配器）

适配器负责网络特定的发现、绑定、路径事件、可达性验证支持和错误分类；共享核心负责身份、路由租约、选择、可靠投递、去重和诊断。

当前实施范围：
- IPv4 普通路由器 LAN：两台或多台 Android/Python 设备，Android↔Android、Android↔Python、Python↔Android。
- IPv4 Android STA/SoftAP 及复杂拓扑：包括 STA+AP 同时存在的多设备环境，三台设备任意两台均需双向可达验收。

未来兼容边界（不代表当前实施）：
- P1：USB、有线、便携路由器、Mesh、VPN、多网卡、Guest/客户端隔离、多子网。
- P2：IPv6、Wi-Fi Direct 等可选适配器；不得成为 IPv4 核心依赖。

## 3. 统一路由策略与 Rescue2 / Server2

未来 Rescue2 / Server2 指令发送应复用同一网络感知事实和路由决策边界，但保留独立传输实现：

- 目标设备存在 fresh、authenticated、双向可达的 LAN UDP route 时，优先使用 LAN UDP 即时下发。
- 没有可用 LAN UDP route、目标在 LAN 外或 LAN 路径暂时不可达时，按指令策略使用 Server2 HTTP 通道。
- LAN UDP 与 HTTP 是两条独立可选通道，不以 IP 网段相同作为 LAN 可达证明。
- 切换通道必须保留同一 commandId/operationId；接收端执行账本必须幂等，重复命令返回已有状态，不重复执行有副作用操作。
- 明确区分 queued、sent、received/accepted、executing、succeeded、failed、expired；HTTP accepted 不能被误报为命令已执行。
- 具备 deadline/TTL 的即时指令过期后不得因网络恢复而盲目补执行；允许离线排队的命令必须由各命令类型显式声明。
- 通道回退和恢复均须有界、可观测；避免 UDP 与 HTTP 同时无控制地重复执行。

这部分是**P0 架构目标与后续 TODO**，不是当前 LAN P2P v2 批次要实现的 Rescue2/Server2 功能。当前不得为此改造或扩建已封存的 Rescue2 新诊断模块，也不得改变旧版批量日志拉取能力。

## 4. 共享与隔离边界

共享：网络感知事件、path fingerprint 语义、认证可达性事实、route registry/lease、路径选择接口、错误分类、限量诊断字段。

隔离：聊天消息协议与 Inbox/Outbox、文件传输协议与 Worker/存储、Rescue2 命令执行账本、Server2 HTTP API、语音媒体数据面。共享路由能力不意味着共享业务队列、数据库或副作用执行器。

## 5. P0 分阶段实施与验收

### P0-A：架构契约与当前 IPv4 基础（现在）
- [x] 建立统一网络感知与自适应路由架构原则。
- [ ] 完成 LAN 聊天发送端从 peer-wide endpoint 迁移到 path-scoped RouteBinding。
- [ ] 多 listener 生命周期、generation、socket ownership 与失效清理按路径隔离。
- [ ] STA/AP 双路径分别认证、双向验证；三台设备任意两台双向通信。
- [ ] 普通路由器多 peer 快路径回归，不引入常驻扫描/探测或冗余 fan-out。

### P0-B：共享感知/决策接口
- [ ] 定义跨 Android/Python 可对齐的 path state、route lease、failure reason 和 route decision 语义。
- [ ] 将网络变化事件、路径认证、可达性和 route lifecycle 从业务发送逻辑中明确分层。
- [ ] 建立 selector 的单路径快路径、多路径有界回退和异步 binding 复核测试。
- [ ] 统一诊断字段与导出语义，保持 bounded、redacted、可关闭/剥离。

### P0-C：Rescue2 / Server2 指令路由接入（LAN P2P v2 真机验收后再排期）
- [ ] Rescue2/Server2 共享目标设备网络感知结果，避免各自用网段/IP 猜测 LAN 状态。
- [ ] LAN 内优先 UDP 即时指令，LAN 外或 LAN route 不可用时使用 HTTP。
- [ ] commandId/operationId 跨通道稳定，设备端持久化幂等执行账本。
- [ ] 指令 TTL、允许离线补发的类型、接收/执行状态和结果回传。
- [ ] UDP ACK 丢失后 HTTP 回退不得重复执行；路径恢复后平滑回归 UDP。
- [ ] 保留 Rescue2 旧版 pull_logs_multi() 和现有 Shell/ADB/Root 能力边界。

### P0 验收矩阵

| 场景 | 必须证明 |
|---|---|
| 普通路由器，多台 Android | 每个 peer 双向消息、ACK、重试与并发；单路径开销不随 peer 数量引入无关扫描 |
| 普通路由器，Android + Python | Android→Python、Python→Android 双向认证与消息送达 |
| STA/AP + Router 三设备 | 三组设备组合任意两台双向可达；断开/恢复任一链路后只清理对应路径 |
| 多路径 peer | 正常发送只选一条最佳已验证路径；首选失效后有限回退；ACK 只能由合法路径/端点完成 |
| 网络切换/恢复 | 路径变化触发定向失效和重验证；旧 generation 的异步任务不能污染新路径 |
| UDP→HTTP 命令回退（后续） | 同一 commandId 幂等；ACK 丢失不重复执行；过期命令不补执行 |
| 性能 | 记录 listener/worker 数、idle CPU/wakeup、内存、包数、RTT、重试、恢复时间；阈值以代表性真机测量确定 |

自动化、CI、APK 构建、模拟集成与真机验收必须分栏记录；任何前者通过均不能替代真机结果。

## 6. 当前明确不做

- 不暂停或重排当前 LAN P2P v2 IPv4 STA/AP 基础工作。
- 不在当前批次实现 Rescue2/Server2 UDP/HTTP 智能切换。
- 不因“统一”而把聊天、文件、命令和语音塞进同一个业务协议、队列或数据库。
- 不对每个接口/peer 周期扫描或广播全部消息。
- 不将子网相同、UDP socket bind 成功或单向收包视为双向可达。
- 当前不考虑 macOS；P1/P2 仅保留适配器边界。
