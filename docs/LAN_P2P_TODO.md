# LAN P2P 后续 TODO 与验收门槛

更新：2026-11-28

## 网络感知落地与 #1035 回归基线（P0）

- [ ] **回归门槛优先**：以 #1035（run 45754370135）作为普通 LAN 双 Android 对讲历史行为基线；先恢复并实测双向发现、peer.hello/peer.ack、route verified、消息与 ACK 闭环，再叠加 STA/AP 多路径和 Rescue2 跨网能力。
- [ ] **感知→应用闭环**：网络/接口事件、候选路径、身份认证、双向可达验证、route/session lease、业务通道选择、RTT/ACK/失败结果回写必须形成闭环；单向收包、同网段或 socket bind 成功不代表在线。
- [ ] **Rescue2 统一连接状态**：区分 discovered、locally-reachable、outbound-session、relay-required、offline/unreachable；设备列表和操作入口不得把“发现到”误显示为“可连接”。
- [ ] **Rescue2 分业务路由**：即时指令优先已验证 LAN UDP，跨网时使用设备主动出站认证会话/HTTP，必要时受控中继；文件传输独立协商与认证数据面；设备管理复用会话但不混用聊天/文件/命令账本。
- [ ] **跨 NAT 明确边界**：Android 主动向可达入口注册并维持会话；不依赖 UDP 广播穿透。没有直连、出站会话或中继时明确报告不可达。
- [ ] **快路径成本门槛**：普通单路径设备保持 O(1) 路由选择，不增加常驻扫描线程、周期探测、每包接口枚举或冗余 fan-out；异常/多路径工作按事件和目标定向激活。

## 新增 P0 架构目标：统一网络感知与自适应路由

> 架构定义见 [UNIFIED_NETWORK_AWARENESS_AND_ADAPTIVE_ROUTING.md](UNIFIED_NETWORK_AWARENESS_AND_ADAPTIVE_ROUTING.md)。这是项目级 P0 架构原则，当前先确立契约和边界，不打断 LAN P2P v2 IPv4 STA/AP 主线。

- [x] 确立统一 Network Awareness + Adaptive Routing 架构：网络/接口感知、候选路径与可信路径分离、路径级认证与租约、双向可达验证、路径质量事实、单路径快路径、多路径有界回退及失效恢复。
- [x] **当前 P0-A（第一阶段）**：LAN 聊天发送端使用 path-scoped RouteBinding；发送任务捕获具体 path/socket/listener generation，发送前复核 binding 仍有效；不再从 peer-wide endpoint 或其他路径认证借用路由。
- [x] **当前 P0-A（第一阶段）**：引入 `LanAdaptiveRouteSelector`；单 Listener 走 O(1) 首选路径查询，多 Listener 才枚举 fresh authenticated bindings；首选路径优先，缺省按最近认证时间排序；备用候选仅在调用方请求时构造，最多 2 条。
- [ ] **当前 P0-A（下一阶段）**：将显式发送失败/ACK 超时与备用 RouteBinding 回退闭环；每次切换必须重新校验 binding、记录具体 path failure，避免对同一消息重复执行或无限重试。
- [x] **多 Listener runtime 代码阶段**：STA primary + validated secondary path 已接入独立 socket/worker/lease/generation 与按路径清理；CI 通过，仍待批次 C ingress/ACK 闭环与真机可达性验收。
- [x] **多路径感知前置**：服务启动时生成一次初始 LAN chat path plan；热点/AP 与 Tethering 状态广播仅在事件到达时触发一次候选快照刷新，不启动 Listener、不扫描轮询；OEM 广播不可用时 fail soft。
- [x] **Listener 生命周期前置**：primary Listener 实例已纳入 path-keyed worker owner map；实例按身份安装/清理，避免旧 receive loop 移除同路径新实例；Java 单测覆盖双路径 owner 和 stale cleanup。
- [x] **多路径 secondary Listener 代码接入（CI 已通过，真机待验收）**：已增加按已验证路径精确绑定的独立接收循环，支持额外 Android Wi-Fi Network 与未表示的 AP/local-interface candidate；只处理 authenticated P2P 帧。路径变化、generation handoff、显式重启和 Service teardown 会按实例身份停止旧 worker。普通单路径不创建额外 Listener。AP 广播/ACK、NAT/客户端隔离和三设备任意两台互通仍待真机验收。
- [x] **多 Listener 旧命令保护**：旧 UDP 管理命令去重提升为 TrackingService 级共享 `LanUdpCommandDeduplicator`；secondary 只处理 authenticated P2P 帧，legacy FIND_ME_* 仍只由 primary 处理。
- [x] **去重并发回归保护**：Java 12 并发 ingress 单测验证同一广播仅一次接纳；Python 静态契约防止后续接收循环复制时退回局部去重表。
- [ ] **当前 P0-A**：普通路由器两台或多台 Android/Python 设备快路径回归；不增加无关周期扫描、高频探测、每包全路径枚举或默认多路径重复发送。
- [ ] **P0-B**：沉淀 Android/Python 对齐的 path state、route lease、failure reason、route decision 语义及可复用接口。
- [ ] **P0-C（正式 P0 目标，分阶段实施）**：Rescue2/Server2 复用统一网络感知与已验证路径；LAN 内优先 UDP，即时跨网/无 LAN route 时使用设备主动出站会话、HTTP 或受控中继；以 commandId 幂等、TTL、接收/执行状态和结果回传防止重复执行。
- [ ] **P0-C 热点发现**：Rescue2 能发现连接 Android 手机热点的设备。同热点且双向可达时使用定向发现与认证单播；跨子网/NAT/客户端隔离时不得假设广播可穿透，必须通过设备主动出站会话或受控中继发现和保持会话。
- [ ] **P0-C 跨网络指令下发**：同一 commandId/operationId 跨 UDP/HTTP/会话回退；设备端持久化幂等执行账本；区分 queued、sent、received/accepted、executing、succeeded、failed、expired；即时命令 TTL 到期不得补执行。
- [ ] **P0-C Android ↔ Rescue2 双向文件互传**：双方均可发起；采用类似蓝牙分享的用户流程（选设备、发送 Offer、对端接受/拒绝、显示进度和结果），底层使用独立认证文件传输数据面，具备分块、SHA-256 完整性校验、逐块 ACK、有限重试、断点续传和进程重启恢复。
- [ ] **P0-C 网络边界**：跨子网/NAT 不依赖 UDP 广播穿透；优先设备主动出站连接，必要时受控中继/隧道。没有可达入口或中继时明确报告不可达，不宣称已实现 NAT 穿透。
- [ ] 上述 Rescue2 P0 目标不改变当前 Rescue2 新诊断封存状态；旧版 `pull_logs_multi()`、Shell/ADB/Root 能力必须保持。聊天、文件、命令继续共享网络感知事实但隔离业务协议、队列、存储和幂等执行边界。

## 2026-11-27 多 Listener runtime 审计结论（历史记录；当前状态见下方批次 B/C 更新）

- [x] 审计 TrackingService 当前聊天 Listener 生命周期：确认仍是单一 wildcard-bound UDP Listener，Registry/Selector 的多 Listener 逻辑尚无 runtime 多 Listener 接入。
- [x] 审计现有精确路径绑定能力：`LanSocketBinding.bindDatagramListenerSocket()` 已存在，但尚未接入聊天 Listener。
- [x] 新增 `LanChatListenerRuntime` path-keyed lease owner，并将现有单 Wi-Fi Listener 的 Registry 注册/注销接入该 owner；当前仍只有一个实际聊天 Listener，不代表 STA/AP 多 Listener 已完成。
- [x] 增加 lease replacement、stale cleanup、独立 path、外部 path invalidation 和单路径无后台 worker/timer 的组件测试。
- [x] ACK ingress 改为由接收循环传入不可变 `listenerPathFingerprint + listenerGeneration`；不再从单 Listener 全局 `rescueSocketPathFingerprint` 推断 ACK 所属路径。
- [ ] 将实际 receive thread/socket 发布、关闭、generation 以及路径变更协调全部迁移到 per-path owner；当前 `TrackingService` 仍只有一个实际聊天接收循环。
- [x] 明确实施门槛与分阶段顺序，详见 [LAN_CHAT_MULTI_LISTENER_RUNTIME_AUDIT.md](LAN_CHAT_MULTI_LISTENER_RUNTIME_AUDIT.md)。
- [ ] 下一步：先将现有单 Wi-Fi Listener 迁移到 path-keyed owner，保持单路径仍仅一个 Listener；通过生命周期/竞态测试后，再接入第二条 STA/AP path。


## 到首次真机验收的剩余批次（估算）

> 当前代码状态预计还需 **4 个实施批次**进入受控真机验收，另预留 1 个按需修复批次。详细阶段和验收顺序见 `LAN_CHAT_MULTI_LISTENER_RUNTIME_AUDIT.md` 第 10 节。进入真机测试不等于拓扑验收通过。

1. 单 Listener socket/thread/path/generation 生命周期对象化并迁移现有 STA Listener。
2. AP/local-interface 可靠识别、精确绑定和第二 Listener 接入。
3. 多 Listener ingress/ACK/peer control、共享旧命令分发、peer×path 独立认证闭环。
4. 有界备用路径回退、真实质量事实采样最小闭环、普通单路径性能回归和真机测试包准备。
5. 条件预留：根据不同 ROM/热点行为/网络切换真机结果修复。

## 当前阶段总纲：四级优先级（2026-11-27 调整）

> **当前唯一主线：LAN P2P V2 基础可靠性与真机验收。** Rescue2 新诊断暂停开发并封存；不得继续扩建其管理员认证、任务调度、设备端授权或诊断回传体系。封存不等于删除，成果索引见 [RESCUE2_DIAGNOSTICS_ARCHIVE.md](RESCUE2_DIAGNOSTICS_ARCHIVE.md)。

1. **P0 — LAN P2P V2 基础闭环（当前主线）**：双机自动发现/主动刷新、独立发现与端点缓存、独立可信配对、免手工输入 Peer ID/IP/端口、文字/长消息、文件双向传输、逐设备最终送达确认、ACK/重试、断网/切网/进程重启恢复；完成 Android/Python 单测、模拟集成和至少双 Android 真机验收。CI 通过不能替代真机验收。
2. **P1 — Rescue2 原有能力保护**：旧版 Android 日志批量拉取 `pull_logs_multi()` 原样保留；Shell/ADB/Root 等既有管理能力不被新架构收窄。只做必要兼容与回归，不借机重构旧功能。
3. **P2 — Rescue2 新诊断（封存 TODO）**：保留当前成果、源码、测试和历史提交，暂不继续实现。LAN P2P V2 真机验收通过后，再评估是否恢复；恢复前先审计封存代码与依赖、修复测试并重新定范围。
4. **P3 — 语音消息/实时对讲**：待 P0 真机验收通过后启动专项设计与实施；语音数据面与聊天、文件传输底层隔离。

## 当前批次与验收状态

当前应围绕 P0 未完成项推进，不再把诊断模块建设列为第 4 批或任何并行主任务。下方已完成标记仅代表对应代码/组件层工作，不代表真机通过。

## P0 当前工作清单：发现、配对、网络恢复与综合回归

> 进度核对：第 1 批发现/缓存底座完成；第 2 批设备列表与发送 UI 已有代码；第 3 批安全配对核心代码和组件测试已完成。第 4 批正在收尾。以上均不代表双机真机验收通过。

> 代码层状态（2026-11-26）：配对控制通道、TrackingService 生命周期接入、双方 SAS UI、双端确认与加密凭据提交恢复链路已实现并有组件测试；当前主要缺口转为双机真机验收及文件传输端到端验收。不得将代码层测试通过记作真机通过。
- [x] 独立配对控制 TCP 传输基础：独立端口、长度前缀、单连接单请求/响应、帧大小限制、连接/读取超时、有界工作队列、目标身份路由校验和会话序列防重放；已接入 TrackingService 生命周期、独立发现端点及配对 UI。
- [x] 将配对控制 Host/Initiator 接入 TrackingService 生命周期和配对 UI；覆盖网络变化重建、服务销毁关闭、待处理会话查询、双方 SAS 决策及状态广播。
- [x] 配对请求/响应携带协议版本、双方 Peer ID、会话/序列、临时 P-256 公钥和 nonce，并由派生密钥及确认 proof 绑定握手 transcript。
- [x] 双端显示一致 SAS；只有双方明确确认后才能进入 READY_TO_COMMIT，之后通过加密 pending-commit journal 和认证 COMMIT/ACK 完成 per-peer secret 持久化。
- [x] 任一端拒绝、SAS 错误、超时、取消、断线、重放或认证失败均不得从临时会话直接落盘；已覆盖拒绝/取消/重放和认证失败的组件测试。
- [x] 配对成功凭据的 per-peer map 合并逻辑：保留现有 peer 和 legacy allowlist，新增 peer 使用独立 32-byte secret。
- [x] 配对 UI 已改为基于独立发现候选设备发起会话、双端核对 SAS 和明确确认；不再以手动输入 Peer ID 或全局共享口令建立新信任。
- [x] 独立发现/endpoint cache 兜底；聊天 UDP 列表只能作为可选 hint。
- [x] 配对组件集成测试：请求/响应、重复/并发会话、序列防重放、确认 proof、双端确认门槛、拒绝/取消、认证 COMMIT 重试及多 peer 独立提交均有测试；旧凭据迁移由凭据层测试覆盖。
- [ ] 双机真机验收（尚未执行）：Android 6+、不同 ROM、网络切换、发现兜底、失败不落盘、成功后 per-peer 文件传输。

## 第 4 批：网络恢复与交互闭环
- [x] 文件传输页提供显式设备列表刷新动作；点击后由 TrackingService 请求独立发现通道立即广播，广播节流，不改变信任状态。
- [x] LAN 聊天与文件传输分别监听 Wi-Fi LAN 路径变化；网络指纹变化后撤销旧端点并重绑各自传输资源，默认蜂窝网络变化不误清仍有效的 Wi-Fi LAN 路由。
- [x] 聊天输入框支持多行长消息；IME 发送、物理 Enter 换行、Ctrl+Enter 发送，并保留发送后焦点/键盘恢复。
- [ ] 双机真机回归：A/B 双向发现、手动刷新立即发现、Wi-Fi 断开/重连、AP/子网变化、蜂窝与 Wi-Fi 默认路由切换、聊天和文件传输分别恢复。
- [ ] 双机真机回归：双方 SAS 确认/拒绝/取消、配对后双向文件传输、摘要校验、断线恢复；真机结果独立记录，不以 CI 代替。

## 通信安全与性能模式
- [x] 独立不可变会话策略组件：SECURE / HIGH_PERFORMANCE / AUTO，设置变更不改变已创建的会话快照。
- [x] 三种模式始终强制要求已认证 peer、加密、完整性校验和防重放；不存在关闭安全底线的裸奔模式。
- [x] 按 CHAT / VOICE / FILE 区分性能参数；高性能模式仅调整有界 in-flight 窗口、缓冲预算和 ACK 合并。
- [x] AUTO 在高质量链路的文件传输选择高性能参数；语音/聊天及弱网/未知链路保持保守参数。
- [ ] 将模式偏好分别接入文件传输与对讲设置；两者底层和持久化隔离。
- [ ] 将会话快照接入实际文件传输管线，测量并调优窗口、ACK 合并与超时。
- [ ] 真机测量安全/性能/自动模式的 CPU、内存、耗电、单向延迟、吞吐量、丢包和重传率，再确定默认参数。

## P2 封存成果：真机验收诊断模块（独立、可关闭、可复用）

> **状态：暂停/封存，不属于当前开发批次。** 以下 [x] 是已有成果记录；[ ] 是未来 TODO。不得为完成本节而继续扩建。详见 `RESCUE2_DIAGNOSTICS_ARCHIVE.md`。

- [x] 通用诊断结果模型 DiagnosticRun：通过 featureId 区分 LAN、定位、设备管理、后台服务等功能测试，支持 PASS/FAIL/SKIPPED、耗时、指标和预期测试数量。
- [x] 通用 DiagnosticExecutor：Probe 不实现传输协议，只调用目标功能的真实公开接口；支持进度回调、取消、稳定失败码。
- [x] JSON、Markdown、HTML 三种报告格式；HTML 离线自适应查看，Markdown 适合交给 AI，JSON 适合程序分析与版本对比。
- [x] 统一导出写入器：调用方可接 Android Storage Access Framework 保存到用户选择的位置，不申请广泛存储权限。
- [x] Android 保存/分享/HTML 查看 Intent 适配：支持保存 JSON/MD/HTML、分享 Markdown+JSON 到 AI 应用、打开 HTML 查看。
- [x] 报告缺少测试项、存在跳过项或失败项时不得判定整体验收通过；失败只输出稳定错误码。
- [x] HTML 转义不可信字段，默认不记录密钥、原始设备标识、IP 地址和文件内容。
- [ ] 将通用诊断执行器接入真实功能测试项：LAN 发现/端点连通/配对/身份认证/消息往返/文件摘要/ACK/断线恢复；后续定位、设备管理、后台服务按 featureId 注册各自 Probe。
- [ ] 增加双机手动协同测试和相同 runId 关联，记录两端各自结果后生成联合报告。
- [ ] 增加诊断入口和报告历史列表，集成保存、分享、HTML 预览及双机联合报告；入口默认关闭或仅测试构建可见。
- [ ] 采集设备型号/API、应用版本/提交号、网络类型、策略档位、延迟分位数、吞吐、重试/失败码；严禁采集密钥、原始 Peer ID、IP 地址、文件内容。
- [ ] 测试执行器只调用正式通信公开接口，不复制协议实现、不修改生产状态；与聊天/对讲底层隔离。
- [ ] 诊断功能可通过构建开关或运行时开关整体关闭；报告 schema 版本化，支持离线比较和未来复用。
- [ ] 真机验收覆盖不同 Android/ROM、同网/网络切换、弱网、后台/前台、重启恢复；测试结果与人工观察交叉验证。



## P2 封存成果：多端诊断数据汇总通道（独立于文件传输）

> **状态：暂停/封存。** Transport 抽象和本地队列保留，不继续接入网络。

- [x] 定义 transport-neutral 诊断事件封装：batchId/runId/匿名 participantId/sequence/eventId/时间戳/受限 JSON 报告；限制单报告大小，拒绝超限数据。
- [x] 定义独立诊断协调器：先本地入队再发送、离线保留待补报、显式重试、接收端成员白名单校验、eventId 去重、批次隔离。
- [x] 增加独立 app-private SQLite spool；不复用聊天数据库、文件传输数据库或文件传输 TCP/UDP 通道。
- [x] 增加可插拔 Transport 接口；传输适配器必须是独立轻量控制通道，文件传输只允许作为人工导出附件的可选方式。
- [x] 增加单测覆盖离线缓存/恢复补发、错误批次、未授权成员、重复事件与 envelope 大小限制。
- [ ] 后续接入实际诊断控制通道（Android/Python 双端实现），并对传输做身份认证、加密、重放保护和限流；未接入前不开放跨设备网络上报。
- [ ] 增加批次创建/成员注册/结束时间与超时、部分设备缺席、联合报告状态汇总 UI。
- [ ] 增加 SQLite 保留上限、过期清理、失败重试退避和用户清除入口。
- [ ] Android/Python 两端实现相同 envelope schema 与 eventId 幂等规则。

## 多设备与跨端验收矩阵（必须独立判定）
- [x] Python 固定收件人账本已有逐目标 ACK 与 partial/delivered 状态；补充三接收端扇出回归测试，验证必须全部 ACK 才能 delivered、重复 ACK 幂等、未知/迟到设备不能加入已发送消息的收件人快照。
- [ ] 双 Android 设备：发现、双端配对确认、单聊双向消息、ACK/重试、文件双向传输、摘要校验、断网/切网/进程重启恢复。
- [ ] 三台及以上 Android 设备：同一发送者向三个固定成员扇出；分别验证单人/多人 ACK、部分失败、成员离线后恢复、重复/乱序消息去重、群成员加入/退出边界、文件多目标传输及每个目标独立状态。
- [ ] 群聊语义必须先明确：发送时固定收件人快照；新成员不追溯收到旧消息；离线补发只针对原收件人且受配置时限约束；群消息只有全部目标成功才显示全量送达，同时展示部分送达明细。
- [ ] Android + Python：真实双向聊天、身份认证/拒绝、消息 ACK、离线补发、文件 offer/chunk/ACK/整文件 SHA-256、断线续传、协议版本/字段兼容；Python 单测或静态源码对照不能替代真实 socket 跨端测试。
- [ ] 跨端测试应提供可启动的 Python peer/runner、明确端口和安全前置条件、超时与清理；测试结果导出统一 JSON/Markdown，runId 关联 Android 与 Python 两端报告。
- [ ] 验收状态分栏：Android 单测、Python 单测、模拟多节点集成、双机真机、三机群聊真机、Android-Python 真机互通；不得合并成单一“测试通过”状态。
- [ ] 测试报告记录每端角色/平台/版本/能力、runId、场景 ID、结果、耗时、稳定错误码和指标；禁止记录密钥、原始 peer 标识、IP 地址、聊天正文或文件内容。

## P2 封存成果与后续 TODO：Rescue2 超级管理权限

> **状态：暂停/封存。** 管理员身份信任组件和设计记录保留；不继续开发诊断任务远程执行。此处既有 Rescue2 不受应用层白名单限制、Root 遵循 Android 系统授权边界的约束仍然有效。

- [ ] **Rescue2 超级管理员权限（不可削弱的既有能力约束）**：Rescue2 是独立 L2 超级管理端，不设置应用层权限等级、业务功能白名单或 Shell/ADB 命令白名单；保留并持续扩展既有完整救援、设备管理、任意合法 Shell/ADB 操作及临时扩展能力。管理员身份/签名只用于让设备识别并认证 Rescue2 管理端，不得反向变成限制 Rescue2 可执行功能的权限系统。
- [x] **Android 系统能力边界约定**：Rescue2 超级管理员身份不等于自动获得 Android Root；实际操作仍受设备 UID、SELinux、沙箱、ADB 可用性和系统授权约束。普通设备已有 Root 时，Rescue2 应可按设备现有授权流程申请并使用 Root；不得伪造或绕过 Root 授权。实际 Root/ADB 远程执行通道仍待实现与真机验收。- [x] Android 核心包新增 `Rescue2AdminTrustStore`（不随 diagnostics=false 精简构建剥离）：独立保存 Rescue2 P-256 公钥、SHA-256 指纹、显式信任与持久化撤销；严格校验 secp256r1、支持公钥身份 JSON 预览/导入及域分离 ECDSA/SHA-256 detached signature 验签。该类仅认证管理端身份，不定义或收窄 Rescue2 操作权限。
- [x] Android 新增 `Rescue2AdminTrustActivity`：从文件选择器导入 Rescue2 公钥文件，导入前展示完整 SHA-256 指纹并要求用户明确确认；列出当前可信管理员并支持逐个二次确认撤销。通过 Chat 设置入口访问，属于核心构建，不依赖 diagnostics。
- [x] Rescue2 新增 G 菜单：首次创建/读取独立 P-256 管理员密钥，Windows 当前用户 DPAPI 保护私钥，导出只含公钥、指纹和 schemaVersion 的 JSON；密钥路径 `%LOCALAPPDATA%\\RebackMyPhone3\\rescue2_admin.key.dpapi`。需要安装 `requirements-rescue2-admin.txt`；不读取/复用 `RESCUE_SECRET`。
- [x] **诊断参与端隔离约定**：只读 Probe 白名单仅约束普通 Android 诊断参与端的自动诊断任务，不约束 Rescue2 管理端自身，也不限制 Rescue2 通过合法 Shell/ADB 执行其他管理任务。`DiagnosticTaskEnvelope/Verifier` 是诊断专用协议，不得用作通用 Rescue2 命令白名单。
- [ ] 独立 CommunicationPolicyManager：策略版本化、签名验证、作用域、有效期、撤销、审计。
- [ ] rescue2 可管理对讲和文件传输策略，但不绕过 Android OS sandbox 或接收端授权。
- [ ] 临时免长期配对发送使用短期 capability，绑定管理员、任务、目标、文件摘要/大小、操作范围、过期时间和一次性 nonce。
- [ ] 访客接收由接收端主动开启限时窗口或明确授权；隔离接收区并由用户确认保存/打开；无身份认证不能保证抗 MITM。
- [ ] 不承诺向没有兼容接收组件/服务的任意 Android 设备强制送达。
- [ ] rescue2 不依赖 server2/chat.py 在线，不破坏聊天/文件传输底层、状态和存储隔离。

## 长消息聊天分片发送与确认
- [x] Android 发送端将超出单包上限的单条消息编码为 300 raw-byte 分片，继续使用同一 messageId、完整消息 SHA-256 和 1200-byte 认证 UDP 数据报上限；短消息保留原单包兼容路径。
- [x] 固定收件人逐设备持久化分片 ACK 位图；每轮最多发送 8 片的有界选择性重传窗口，跳过已确认分片，缺片重传；进程重启后从 SQLite 位图恢复进度。
- [x] 分片 ACK 只确认接收端已持久化分片缓存；全部分片 ACK 后仍等待完整消息入 Inbox 的最终 chat.ack，必要时重发末片触发接收端重新尝试组装/入库。
- [ ] 长消息固定目标双机真机验收：乱序/丢片/重复 ACK/进程重启/网络切换/部分目标离线/最终 Inbox ACK。
- [x] 空收件人首次发现窗口的长消息广播改为有界首轮发送：每个广播地址/端口最多发送前 8 片；后续窗口只向该消息已建立的临时收件人端点单播推进。
- [x] 首轮有效分片 ACK 可在 discovery_pending 窗口内建立消息级临时收件人和独立 ACK 位图；最多 64 个临时收件人，不写入可信设备表，不改变其他消息的收件人快照。
- [x] 分片 ACK 仅表示接收端已持久化分片缓存；全部分片 ACK 后发送完成探测包，最终送达仍以完整消息持久化 Inbox 后的 chat.ack 为准。
- [x] 修复已有 verifiedChatPeers 路由时跳过广播临时收件人建账的边界：即使 peer.ack 先于首个 fragment.ack 到达，也先尝试按 messageId/peerId 建立临时进度，再写入该 peer 的分片位图。
- [ ] 补充多收件人进度隔离测试：同一广播消息的多个 peer 分别 ACK、乱序 ACK、重复 ACK、单个 peer 丢片重传，不得串写其他 peer 位图或推进错误端点。
- [ ] 补充广播窗口丢包与进程重启恢复测试：覆盖首轮 ACK 部分缺失、后续窗口部分缺失、ACK/发送竞态、重启后从 SQLite 位图恢复；不得将分片 ACK 升级为最终送达。
- [ ] 长消息广播及固定目标双机真机验收：乱序/丢片/重复 ACK/进程重启/网络切换/部分目标离线/最终 Inbox ACK。

## 后续阶段
- [ ] 控制通道、UI 和凭据 commit 集成完成后进行安全审查与双机验收。
- [ ] 公共模块重构延后到真机验收完成后。
- [ ] 文件传输 UI：统一聊天入口 + 独立文件管理页；默认关闭，通过设置开启。


## P1/P2 边界：Rescue2 日志拉取与多手机遥控诊断

> **P1 保护要求：** 旧版 `pull_logs_multi()` 不得修改，必须保持原有批量拉取能力。新增诊断采集属于 P2，整体封存，绝不能替换、依赖或改变旧功能。


- [x] **旧版 Android 日志拉取完整保留**：`pull_logs_multi` 已恢复为 `481dc2c` 基线函数实现；旧版菜单入口、UDP 命令、逐设备派发、30 秒等待、上传识别、成功/未响应统计和 `clear_after` 行为不变。旧函数不调用诊断事件记录器；后续修改必须通过兼容回归测试。不得因新版诊断采集建设而删除或削弱旧版日志拉取功能。旧版日志拉取能力必须保留。
- [ ] **新增独立诊断日志采集**：与旧版日志拉取并列，支持单设备/批量两种目标模式；复用 Android 诊断 Probe、报告历史和导出组件，但不得调用或修改 `pull_logs_multi`。协议、认证、任务状态与传输通道独立。
- [x] 已完成旧版 `pull_logs_multi` 行为基线核对；新诊断任务可借鉴其多目标定向、限时等待和逐设备结果呈现，但不得复用旧 UDP 明文命令或共享救援口令。
- [ ] 日志拉取走独立诊断控制通道；主控创建有时效任务，设备端逐任务授权并返回 taskId。必须展示目标设备和采集范围，禁止默认广播拉取。当前 Android 已有本地 DiagnosticRun/Probe/History/Export 和 transport-neutral DiagnosticBatchCoordinator/Envelope/LocalStore，但 Coordinator 的网络 Transport 仍是抽象接口，LanP2pProtocol 也尚无诊断任务消息类型；TrackingService 尚无远程诊断任务命令处理，Rescue2 尚未接入诊断批次协议。因此目前不具备真实远程单设备/批量诊断采集能力。
- [ ] 支持按时间窗、日志级别、模块/标签筛选；设备端先筛选和脱敏，再分块回传，并设置单设备/批次字节上限、超时、速率限制和自动过期。
- [ ] 默认只读且不清理设备原日志；任何清理操作独立授权并由设备端二次确认。排除密钥、认证材料、聊天正文、文件内容及原始 IP/Peer ID。
- [ ] 按设备显示成功/失败/部分结果、字节数、时间范围、脱敏状态和稳定错误码；支持导出文本、JSON、Markdown 供 AI 分析。
- [ ] 日志回传不得依赖被测文件传输链路；文件传输仅可作为用户主动导出的离线附件方式。
- [ ] 多手机遥控测试采用“测试主控端 + 多个测试参与端”：批次成员固定快照、逐设备加入确认、每个动作独立授权，支持超时、取消、离线和部分成功。
- [ ] 普通 Android 诊断参与端的自动诊断任务仅调用已注册只读 Probe；该限制不得套用到 Rescue2 超级管理端。Rescue2 任意合法 Shell/ADB 操作能力必须保留；Android 系统自身的 UID/SELinux/Root/ADB 授权边界仍然有效。
- [ ] 预设套件优先覆盖发现、连接、配对状态检查、消息往返、文件摘要只读验证和网络切换观察；影响业务状态的动作不进入默认自动套件。
- [ ] 设备端展示任务、进度和停止入口；参与设备可随时退出，主控不能扩大权限或追溯执行新动作。
- [ ] 联合报告使用 batchId/runId/匿名 participantId 关联，分别记录 PASS/FAIL/SKIPPED/TIMEOUT/NOT_JOINED；缺席设备绝不算通过。
- [ ] 只有 Rescue2 独立管理员身份/公钥信任、端到端认证与加密、防重放、任务时效、设备端逐任务授权、成员白名单和审计全部落实后，才开放真实跨设备诊断控制；当前 Transport 抽象不代表网络能力已可用。


## P3 后续 TODO：实时语音对讲与语音消息（P0 真机验收通过前不实施）

> 实施门槛：LAN P2P 文字消息、发现/端点恢复、身份与配对基础完成代码验收及双机真机验收；公共基础模块重构完成后，再启动语音专项。此项当前仅做架构规划，不提前引入音频依赖、权限或后台服务。

- [ ] **基础设施复用边界**：复用已验收的 Peer 身份、可信配对/认证、设备发现、网络选择与切换、端点缓存和恢复机制；不得把 UDP 发现列表本身当作身份凭据。
- [ ] **实时音频独立数据面**：建立独立 voice 会话、状态机、端口/套接字生命周期、队列和诊断指标；不复用文字聊天消息/ACK 状态机，不复用文件传输 TCP/数据块通道。
- [ ] **会话安全**：基于已认证 Peer 信任关系协商独立会话密钥；密钥按会话和方向派生，采用认证加密、序列号/nonce、防重放、会话过期与撤销；不得直接复用聊天 HMAC 密钥或把当前共享口令描述为设备级强身份。
- [ ] **媒体技术验证**：优先评估 Opus 编解码与 UDP 实时传输；通过真机测量确定采样率、帧长（初始评估 20 ms）、码率、MTU 与缓冲预算，不预先锁死参数。
- [ ] **实时音频链路**：独立实现 AudioRecord/AudioTrack 生命周期、抖动缓冲、乱序/丢包处理、丢包隐藏、时钟漂移处理、回声消除、降噪、音量与耳机/扬声器路由。
- [ ] **语音会话状态机**：覆盖 IDLE、CONNECTING、ACTIVE、RECOVERING、CLOSED；明确呼叫/接听/拒绝/忙线/超时/取消/断网/切网/后台限制/音频焦点丢失等状态与终态。
- [ ] **资源与权限**：按需申请麦克风权限；后台持续对讲必须符合 Android 前台服务及通知要求；会话结束释放录音、播放、网络和唤醒资源。不得为语音常驻而影响现有聊天服务稳定性。
- [ ] **语音消息独立形态**：录制后发送的语音消息属于媒体消息/附件能力，使用独立媒体对象、持久化和完整性校验；可在聊天 UI 统一呈现，但不能伪装成实时音频会话，也不依赖文件传输业务状态。
- [ ] **渐进验收**：先双 Android 点对点半双工/全双工可用性，再测单向/双向延迟、抖动、丢包、弱网、网络切换、锁屏/后台、耳机与免提、来电打断、耗电和温升；群聊/多端混音及跨网络能力另立阶段。
- [ ] **诊断与可选构建**：语音诊断项接入通用 DiagnosticExecutor，报告记录编解码/延迟/丢包等脱敏指标；诊断可关闭或从正式构建剥离。是否提供可选语音模块构建变体，待实现前评估。
- [ ] **实施前架构评审**：对比 WebRTC（仅在确有其必要时引入）、轻量 Opus/UDP 自研链路的依赖体积、Android 兼容性、维护成本与安全边界；先做技术验证和双机原型，不直接将某个方案视为已定实现。


## P2 封存成果：Rescue2 独立管理员诊断任务协议

> **状态：暂停/封存。** 当前 envelope、签名/验签、防重放、consent 等代码只作为未完成原型保存；不代表端到端远程诊断可用。后续恢复前需重新审计并修复现有失败测试。


- [x] 增加 Python/Android 共用的有界诊断任务 envelope：taskId、管理员公钥指纹、固定目标 participant 快照、诊断参与端只读 Probe ID 白名单、创建/过期时间及随机 nonce。
- [x] Python 与 Android 字段名、schemaVersion、目标/Probe 上限、唯一性、TTL 和拒绝非法 Probe 的跨语言模型测试。
- [x] Rescue2 管理员 P-256 签名密钥生成/Windows DPAPI 安全存储、公钥指纹计算、显式 Android 端公钥信任与撤销；不得复用 peer secret 或旧救援口令。
- [x] 诊断任务签名与 Android 端验签：签名绑定规范 JSON、管理员指纹、任务时效和固定目标 participant；拒绝非规范 JSON、错误目标、过期任务、未知管理员及无效签名。
- [x] Android 持久化 taskId/nonce 防重放；覆盖重复 taskId、重复 nonce、进程重建和过期后清理/重用测试。
- [ ] 设备端逐任务同意/拒绝/取消状态机仍待实现；当前验证器/防重放组件未接入网络分发，未授权前不得执行。
- [ ] 接入 DiagnosticExecutor 的 Probe 白名单执行适配器、任务超时/并发限流和逐设备结果状态。
- [ ] 独立诊断控制通道及报告回传的端到端认证、加密、断点续传和限流。
- [ ] Rescue2 单设备/批量诊断任务界面、报告查看/保存/导出及真实控制通道仍待实现；当前 G 菜单仅管理身份和导出公钥。

> 当前批次新增的 task envelope 仅是序列化与输入校验，不提供身份认证、签名、授权或传输安全。管理员公钥信任、签名验签和设备端授权均未完成；不得把 schema 测试通过视为远程诊断已可用。

## P0 网络拓扑扩展：安卓热点主机 / 客户端 / 无互联网局域网

> **验收要求新增，属于 LAN P2P V2 基础能力，不是后续可选增强。** 不得以普通无线路由器局域网测试代替本节验收。当前代码主要通过 `TRANSPORT_WIFI Network`、`LinkProperties` 和定向 UDP 广播选择路径；热点客户端可能符合该模型，但热点主机的 AP 接口未必以普通 Wi-Fi Network 暴露，且热点实现可能限制客户端间广播/单播。因此在完成接口发现、socket 绑定和真机矩阵验证前，不得宣称热点主机模式已支持。

### 必须支持的拓扑

- [ ] **安卓热点主机 + 1 个客户端**：主机开启系统便携式 Wi-Fi 热点，客户端连接；无外部路由器、无互联网，双方可发现、独立配对、双向文字/长消息、文件双向传输。
- [ ] **安卓热点主机 + 至少 2 个客户端**：验证主机与每个客户端双向互通，并验证客户端 A 与客户端 B 是否能直接互通；不得假定热点一定允许客户端隔离关闭。
- [ ] **热点主机角色变化**：不同手机分别承担热点主机和客户端；热点关闭/重开、客户端加入/离开、热点网段变化后，端点缓存及时失效并重新发现。
- [ ] **纯离线运行**：关闭移动数据/互联网访问，仅保留热点 Wi-Fi；发现、配对、消息、ACK、文件传输均不得请求云端或公共 DNS。
- [ ] **普通路由器 Wi-Fi**：继续保持现有同网段发现与传输回归，不能为了热点兼容而破坏。
- [ ] **混合网络**：设备同时有蜂窝数据与热点/局域网时，LAN socket 必须绑定正确的本地 LAN 接口；不得因默认路由是蜂窝网络而将 LAN 数据错误发往蜂窝。

### 实现约束与技术路线
- [ ] 建立统一的 `LanNetworkPath`/网络路径快照抽象，至少包含接口名、IPv4 地址/前缀、广播能力、可绑定的 Android `Network`（可空）、路径类型（普通 Wi-Fi 客户端 / 本机热点 AP / 其他本地链路）和脱敏指纹；路径选择、发现、配对监听、文件 TCP listener 必须使用同一快照，禁止各自独立猜接口。
- [ ] **热点主机接口识别**：不能只依赖 `NetworkCapabilities.TRANSPORT_WIFI`。在 Android 版本和权限允许范围内枚举本机活动 IPv4 接口/地址，识别热点侧接口（不同厂商可能是 `ap0`、`wlan0` 或其他名称，禁止硬编码单一接口名）；排除 loopback、无效地址和非目标接口。接口枚举结果必须与路由/地址信息交叉验证。
- [ ] **socket 绑定策略**：有明确 Android `Network` 时优先使用 `Network.bindSocket`；本机热点 AP 接口没有可绑定 `Network` 时，必须通过接口地址/接口绑定策略确保 UDP/TCP socket 使用该热点链路，不能静默退回默认蜂窝网络。若系统不允许安全绑定，应明确报告该路径不可用，不得伪报成功。
- [ ] **发现不依赖单一广播机制**：定向广播作为低成本首选，但必须验证热点主机与客户端、客户端彼此的广播收发。广播被热点/AP 隔离策略限制时，评估同网段定向单播探测、受控邻居候选交换或用户明确触发的短时备用发现；不得进行无界端口扫描，不得把未认证发现结果升级为可信设备。
- [ ] **客户端隔离检测**：区分“设备已加入同一热点”与“设备之间实际可达”。通过有界探测/连接结果确认可达性；若热点固件启用了客户端隔离，应向 UI/诊断报告明确失败原因和受影响链路，不得显示为已发现/可连接。
- [ ] **多网络与网络切换**：热点开关、接口地址/前缀变化、Network 回调、默认路由切换必须驱动同一个路径代数；旧 socket、候选租约和旧网络配对端点全部按路径代数失效，随后在新路径重建。
- [ ] **安全边界**：热点密码只负责接入 Wi-Fi，不等于应用层可信配对；Peer ID/IP/端口均只是候选提示。配对仍需独立双端确认和认证，消息/文件仍需认证加密与完整性校验。
- [ ] **能力分层**：普通 Wi-Fi 与热点共享已验证的身份、配对、消息/文件协议和可靠性状态机；网络发现/绑定作为可替换适配层。对讲未来复用已验收的网络路径与身份基础，但继续保持独立音频数据面，不复用文件传输数据通道。

### 当前代码推进记录（2026-11-27）

- [x] 新增 `LanNetworkPath` 基础模型，区分 ConnectivityManager 可绑定的 Android Network 与未映射到 Network 的本地 IPv4 接口候选；路径对象明确允许 `network == null`，但不把候选冒充为可绑定网络。
- [x] 新增活动本地 IPv4 接口枚举：按接口状态和地址属性筛选，不硬编码 `wlan0`/`ap0` 等 OEM 名称；记录接口、地址、前缀和广播地址（若存在）。
- [x] TrackingService 在 LAN 文件传输网络协调时采集并记录本地 IPv4 接口候选，便于确认热点主机 AP 接口是否对应用可见。
- [x] 增加地址筛选、非硬编码接口名、无广播地址和 Android Network 必须显式提供等单元测试。
- [ ] **尚未完成热点主机数据面**：当前接口枚举仅用于候选发现/诊断，尚未把无 Network 的 AP 接口用于 UDP/TCP socket 绑定，也未宣称热点发现/配对/传输可用。下一步必须先实现可证明的接口绑定策略与真实 socket 可达性验证；若 Android/OEM 不提供安全可用的绑定能力，应明确失败而非回退蜂窝默认路由。
- [ ] 尚未完成广播受限环境下的备用发现、热点客户端隔离检测及双机/三机无互联网真机验收。

### 热点专项验收报告必须单列

- [ ] 每台设备记录角色（热点主机/客户端）、Android API/厂商 ROM、接口类型（脱敏）、IPv4 前缀、是否有互联网、是否允许客户端互通、发现方式、双向连接结果。
- [ ] 分别报告：主机↔客户端 A、主机↔客户端 B、客户端 A↔客户端 B；每条链路分别测试发现、配对、消息最终 ACK、文件摘要校验、断网恢复。
- [ ] 至少覆盖两种不同厂商/ROM 的热点主机；若只能在部分 ROM 工作，必须明确兼容矩阵和失败原因，不得笼统标注“安卓热点支持”。
- [ ] 热点专项结果与普通路由器结果分栏统计；模拟测试、Android 单测、双机真机、三机真机不得合并为一个通过状态。


## 统一 LAN P2P V2 网络架构与跨平台兼容矩阵（2026-11-27 新增）

> **架构约束：LAN P2P V2 不得围绕某一种 Wi-Fi 接入方式写成特例集合。必须采用统一的网络路径抽象、跨平台一致的协议与独立可替换的网络适配层。Android 与 Python 均为正式 P2P 节点；Android↔Android、Android↔Python、Python↔Python 三种组合均属于正式兼容目标。** 本节是架构与后续验收要求，不代表相关能力已经实现或通过测试。

### 一、统一分层架构

- [ ] **网络路径层（Network Path）**：统一描述活动接口、接口类型、地址族、本机地址/前缀、广播/组播能力、路由可用性、可绑定的 Android Network（可空）、路径代数/生命周期和脱敏指纹。Android 与 Python 分别实现平台适配器，但对上层暴露一致的路径语义。
- [ ] **路径选择与绑定层**：发现、配对控制通道、聊天和文件传输必须基于明确选定的路径快照工作；不得各自独立猜测默认网卡或静默回退到蜂窝/ VPN/其他接口。每次绑定后通过真实收发和路径信息交叉验证；无法证明路径正确时报告不可用。
- [ ] **发现层**：广播/组播作为可用时的首选，配合有界定向单播探测、已知可信端点的短期缓存和受控备用发现。发现结果始终只是未认证候选；禁止无界扫描，禁止将 IP、端口、广播包或热点接入密码当作身份凭据。
- [ ] **身份与配对层**：Peer 身份、可信关系和配对凭据独立于 IP、接口、网段、SSID、热点主机角色及操作系统；网络切换、地址变化和跨接入方式迁移不得重置可信身份。继续执行独立双端确认、认证加密、完整性校验和防重放。
- [ ] **统一协议层**：Android/Python 共用版本化消息 schema、字段语义、长度/字节序、UTF-8 规则、错误码、时间戳单位、身份标识、认证 transcript、ACK/重试和幂等语义。平台差异只留在 socket、系统网络 API、存储和 UI 适配层。
- [ ] **消息可靠性层**：短消息、长消息分片、选择性 ACK、有限重试、最终 Inbox 持久化 ACK、固定收件人快照、去重、离线补发边界和进程重启恢复在 Android/Python 间语义一致。分片 ACK 不得冒充最终送达。
- [ ] **文件传输层**：与聊天和未来语音数据面隔离；统一 offer/chunk/ACK/完成确认、文件大小上限、SHA-256、断点续传、临时文件清理和失败恢复语义。每个目标设备独立记录最终结果。
- [ ] **语音预留边界**：后续语音复用已经验收的 Peer 身份、可信配对和网络路径选择；语音会话、密钥、端口、队列和实时音频数据面独立，不复用聊天消息 ACK 状态机或文件传输数据通道。
- [ ] **路径生命周期**：统一处理接口新增/消失、地址/前缀变化、默认路由变化、热点开关、VPN 状态变化、休眠唤醒和进程重启。路径变化递增代数并使旧 socket/端点租约失效；不得清除独立的 Peer 身份、可信配对和已持久化消息/文件任务。
- [ ] **可观测性**：统一输出脱敏路径诊断字段、发现方式、选路原因、绑定结果、连接失败阶段、协议版本和稳定错误码；禁止记录密钥、原始 Peer ID、文件正文或不必要的原始 IP。代码/单测/模拟集成/真机结果分开统计。

### 二、网络接入与特殊拓扑兼容矩阵

优先级说明：P0 是当前 LAN 基础可靠性和真机验收门槛；P1 是基础闭环稳定后扩展；P2 是高级/可选网络能力。优先级不代表当前已支持。

| 网络环境 / 拓扑 | 优先级 | 必须验证的特点 |
|---|---|---|
| 普通 Wi-Fi 路由器，同网段 | P0 | 双向发现、配对、消息/文件、蜂窝并存时正确选路 |
| Android 热点主机 + 1 个客户端 | P0 | 无外部路由器/互联网；主机与客户端双向完整闭环 |
| Android 热点主机 + 至少 2 个客户端 | P0 | 主机↔各客户端及客户端↔客户端分别验证，识别 AP 客户端隔离 |
| Android 热点纯离线模式 | P0 | 关闭移动数据/互联网仍可发现、配对、消息和传文件 |
| Android 热点与普通路由器角色切换 | P0 | 热点开关、主机更换、客户端加入/离开、地址变化和缓存失效 |
| 便携路由器、随身 Wi-Fi、无线中继 | P1 | 中继/双频/多级接入下的广播范围、单播可达性和路径变化 |
| Mesh 网络 | P1 | 节点漫游、回程链路变化、客户端隔离及广播/组播行为 |
| USB 网络共享 / USB 网络适配器 | P1 | 独立网段、接口绑定、拔插和重新枚举 |
| 有线以太网 / USB-C 网卡 / 扩展坞 | P1 | 非 Wi-Fi 接口发现、双向收发、Wi-Fi 与以太网并存 |
| 多网卡并存（Wi-Fi + Ethernet + 蜂窝） | P1 | 多路径候选、确定性选路、禁止错误默认路由 |
| VPN 开启或切换 | P1 | VPN 接管路由、局域网排除规则、路径变化与可解释失败 |
| Guest Wi-Fi / AP 客户端隔离 | P1 | 区分同 SSID/同网段与真实端点可达，不误报在线 |
| VLAN / 多子网 / 不同广播域 | P1 | 广播不可跨网段时使用已知端点或受控发现；不做无界扫描 |
| DHCP 变化、静态 IP、地址冲突 | P1 | 地址租约更新、冲突检测、旧端点失效和恢复 |
| IPv4/IPv6 双栈 | P2 | 地址族选择、作用域、链路本地地址和端点编码兼容 |
| IPv6-only / NAT64 等特殊网络 | P2 | 明确能力边界、地址发现和端到端可达性验证 |
| Wi-Fi Direct / Wi-Fi P2P | P2 | 独立接入/组所有者机制、系统 API 限制和可选适配器 |
| 无 DHCP 或受限局域网 | P2 | 不依赖公共 DNS；有界候选交换和显式失败诊断 |

### 三、跨平台节点组合验收矩阵（强制）

| 网络环境 | Android ↔ Android | Android ↔ Python | Python ↔ Python |
|---|---|---|---|
| 普通 Wi-Fi 路由器 | P0 | P0 | P0 |
| Android 热点（主机/客户端角色覆盖） | P0 | P0 | P0 |
| 便携路由器 / 中继 | P1 | P1 | P1 |
| USB 网络共享 | P1 | P1 | P1 |
| 有线以太网 | P1 | P1 | P1 |
| Mesh / 多子网 | P1 | P1 | P1 |
| VPN / 多网卡并存 | P1 | P1 | P1 |
| IPv4/IPv6 双栈 | P2 | P2 | P2 |
| Wi-Fi Direct | P2 | P2 | P2 |

- [ ] P0 三种节点组合都必须真实运行对应平台程序；Python 单元测试、协议模型对照或模拟 socket 不能代替 Android-Python 真机/实机互通。
- [ ] Android↔Python 必须双向验证发现、身份认证/配对、短消息、长消息分片、逐设备最终 ACK、文件 offer/chunk/ACK、整文件 SHA-256、断线续传和进程重启恢复。
- [ ] Python↔Python 必须验证多进程/多主机真实 socket 互通、独立身份与配对、消息可靠性、文件完整性和版本协商；不得因两端同语言而省略协议兼容测试。
- [ ] Python 节点当前阶段只规划并覆盖 Windows、Linux。macOS 暂不纳入范围，不作为实现、测试或验收门槛。平台接口绑定能力不同，必须记录各 OS 的实现和限制，不得假设 Python socket 在所有系统行为完全一致。
- [ ] Android 端覆盖不同 Android API 级别和至少两种厂商/ROM；热点主机角色、热点客户端角色分别记录。
- [ ] 每种组合分别记录发现、可达、认证、消息最终送达、文件摘要校验、恢复六类结果；任一环节失败不得被其他环节成功掩盖。
- [ ] 统一协议兼容测试覆盖当前/前一受支持 schema 版本、未知字段、缺失字段、超长帧、非法 UTF-8、错误字节序/长度、重复/乱序/迟到 ACK、重放和不兼容版本拒绝。
- [ ] 跨端联合验收报告以 runId 关联两端记录，标记设备角色、OS/API、应用版本/提交号、协议版本、网络拓扑和脱敏路径信息；缺席设备、跳过测试和模拟结果均不得记为真机通过。

### 四、实施顺序与范围控制

1. **当前批次优先**：在现有 LAN P2P V2 基线上完成热点主机本地接口路径的安全 socket 绑定研究/验证；让统一路径模型真正接入发现、配对控制监听和文件 TCP listener 前，先完成普通 Wi-Fi 回归及可证明的路径绑定测试。不得为了抽象统一而一次性大规模替换稳定链路。
2. **P0 跨平台闭环**：审计现有 Python 协议与 Android 协议差异，建立共用 schema/兼容测试；补齐 Android↔Python、Python↔Python 的真实 socket runner 和三种节点组合验收。先覆盖普通路由器与 Android 热点。
3. **P1 网络适配扩展**：按独立适配器逐项增加 USB、有线、便携路由器、中继、Mesh、VPN、多网卡、Guest/隔离和多子网支持；每项必须有路径发现、绑定、可达性、恢复和失败诊断测试。
4. **P2 高级网络扩展**：在 IPv4 P0/P1 稳定后评估 IPv6、Wi-Fi Direct 和其他特殊网络；以可选适配器接入，不让高级网络依赖拖累基础 LAN。
5. **质量门槛**：任何新适配器不得削弱普通 Wi-Fi、热点 P0 或 Android/Python 协议兼容；CI 绿灯只证明自动化覆盖范围，最终仍需真实设备/主机矩阵验收。未完成双 Android 与 Android-Python 真机验收前，不得宣称 LAN P2P V2 全面完成或合并 main。



## 本批推进记录（2026-11-27）：Python 离线接口枚举第一步

- [x] 新增 `lan_p2p_network_paths.py`，建立 Python IPv4 接口路径快照结构，枚举活动接口、本机地址、前缀和系统明确提供的广播地址；结果确定性排序。
- [x] `chat.py` 的子网发现不再通过连接 `8.8.8.8:80` 推断出口地址，也不再按前三段拼接猜测 `/24` 广播地址。
- [x] Python 聊天广播和启动发现改为逐个使用枚举出的接口广播地址，并保留有限的 `255.255.255.255` 备用广播。
- [x] 增加多网卡/多子网、loopback/link-local/非法地址过滤、接口 down、无广播地址和接口枚举不可用等单元测试。
- [x] 本批相关 Python 验证工作流通过：`45896435014`（路径枚举测试）、`45896463008`（移除公网探测）、`45896491893`（多接口广播接入）；均为 `validate-python: success`。纯 Python 改动未触发 Android APK build，不能据此宣称 Android 构建已验证。
- [ ] **明确未完成**：本批 Python 接口枚举只提供路径候选和广播地址，不等于 Socket 已绑定到指定接口；尚未实现 Linux/Windows 各自可靠的接口绑定与出口验证；macOS 暂不纳入范围。
- [ ] **明确限制**：Python 侧 `psutil` 为可选能力；缺失时接口枚举 fail-closed，当前只保留有限全局广播，不猜测子网。后续需确定并纳入正式安装依赖/各平台适配实现。
- [ ] **Android 热点主机仍未打通**：`LanTransferDiscovery` 当前仍要求非空 Android `Network` 和非空广播地址，并调用 `Network.bindSocket()`；本地 AP 接口候选尚未进入 UDP/TCP 数据面。不能将本批 Python 改动描述为热点兼容完成。
- [ ] 下一步：补齐 Python 各 OS 路径绑定能力及真实多网卡出口验证；Android 侧先建立显式的路径绑定能力/失败状态，再验证 AP 本地接口候选的实际收发，保持普通 Wi-Fi Network 绑定回归。



## 下一批推进记录（2026-11-27）：Android 网络路径绑定边界第一步

- [x] 新增 `LanSocketBinding`：UDP DatagramSocket 与 TCP Socket 共用同一显式绑定入口；仅接受携带真实 Android `Network` 的路径快照，并调用 `Network.bindSocket()`。
- [x] 对 `LOCAL_INTERFACE_CANDIDATE` 明确 fail-closed：当前 Android 公共 Java API 没有可移植的热点 AP 接口 `SO_BINDTODEVICE` 替代方案；无 Network 候选不得被误当作已绑定接口，也不得静默走进程默认蜂窝/VPN 路由。
- [x] `LanTransferDiscovery` 内部改为持有 `LanNetworkPath` 快照；保留旧构造器作为兼容入口，新生产调用从 `TrackingService` 传入包含接口名、地址、前缀、广播地址及 Network 的完整路径快照。
- [x] 增加 UDP/TCP 对无 Network 热点接口候选的拒绝测试，确保候选不会被误用为可绑定路径。
- [ ] 本批尚未把统一路径快照接入聊天 UDP listener、配对控制 TCP listener 和文件 TCP listener 的全部创建/重建路径；后续逐个接入并补充路径代数/生命周期回归，不能把本批称为全链路统一。
- [ ] 本批没有实现热点 AP 接口 socket 绑定，也没有声称 Android 热点主机可用；需要基于 Android API/OEM 的真实能力继续研究和真机验证。无法安全绑定的设备必须显示路径不可用。
- [ ] 本批未修改聊天消息协议、聊天持久化/ACK 状态机、对讲 UI 或文件传输协议；只调整独立文件发现 socket 的路径传递与绑定边界。
- [ ] Python 端目前仓库根目录没有通用 `requirements.txt`、`pyproject.toml` 或 `setup.py`；`psutil` 仍是可选依赖，尚未解决安装策略。本批不擅自引入运行时依赖，后续需提供跨平台依赖声明/安装入口并验证 Windows/Linux。


## 下一批推进记录（2026-11-27）：Android 监听器路径快照接入第二步

- [x] 文件 TCP listener 增加显式 `start(LanNetworkPath, address, port)` 入口；启动前验证路径必须是携带真实 Android `Network` 的路径，且监听地址必须与快照本机 IPv4 完全一致。
- [x] 配对控制 TCP host 增加相同路径快照入口；与文件 TCP 使用同一校验边界，避免各自传入任意本机地址。
- [x] TrackingService 的文件 TCP listener、配对控制 listener 均从同一类 ConnectivityManager/LinkProperties 信息构造路径快照（接口名、IPv4、前缀、Network），指纹改为路径快照指纹；路径/地址变化会触发现有关闭并重建流程。
- [x] 聊天 UDP listener 的绑定改为先构造 `LanNetworkPath`，再经 `LanSocketBinding.bindDatagramSocket()`；原聊天协议、消息状态机和 UDP 业务处理未改。
- [x] 增加 TCP listener 对无 Network 热点候选拒绝、监听地址与路径地址不一致拒绝的单元测试。
- [ ] **重要边界**：Android `ServerSocket` 不能传给 `Network.bindSocket()`；TCP 服务端通过绑定快照中的具体本机 IPv4 地址限定入站接口。这是地址绑定，不应描述成 Network API 级 socket 绑定。最终路径仍需真实设备收发验证。
- [ ] **尚未统一出站 TCP**：配对控制客户端 `exchange()` 及文件 TCP 发起连接仍需显式传入所选路径，并在 `Socket.connect()` 前通过 `Network.bindSocket()` 绑定；本批未改调用者/会话语义，下一批优先补齐，避免多网卡/蜂窝并存时出站连接走错默认路由。
- [ ] **路径生命周期尚需加强**：当前 TrackingService 依靠网络回调触发 refresh 和路径指纹变化重建监听器；还需审计网络回调遗漏、短暂 LinkProperties 不完整、旧候选租约失效和活跃连接关闭策略，并补充自动化测试。
- [ ] 本批未触碰聊天协议、消息 ACK/重试/持久化、UI、文件协议、对讲音频通道，也未触碰 Rescue2 旧日志功能。


## 本批推进记录（2026-11-27）：配对控制出站 TCP 路径绑定

- [x] 配对控制 transport 增加生产路径入口：创建未连接 Socket 后，先经 `LanSocketBinding.bindStreamSocket(LanNetworkPath, socket)` 绑定，再执行 `connect()`；绑定失败不会进入 connect，也不回退进程默认网络。
- [x] 对端点做路径一致性校验：只接受 Network-backed IPv4 路径，远端配对地址必须属于所选本机 IPv4 前缀；拒绝无 Network 热点接口候选和跨网段端点。
- [x] 配对发起器保存不可变 `LanNetworkPath` 快照；初始握手、双方确认、确认重试、拒绝、取消及当前会话 COMMIT 均复用该路径。
- [x] TrackingService 在发起配对前从当前选定 Wi-Fi Network/LinkProperties 构造路径快照；持久化 COMMIT 恢复重试重新选取当前路径并绑定。
- [x] 配对控制 listener 的路径指纹变化或路径不可用时，关闭旧 provisional initiator 会话，避免旧 Network/旧端点继续用于新配对；持久化 COMMIT journal 保留并允许在新路径恢复。
- [x] 无路径兼容入口限制为 loopback 测试用途，避免生产代码误用普通 Socket 走蜂窝/VPN 默认路由。
- [x] 新增 fail-closed 测试：无 Network 接口候选、端点超出所选子网时，在连接前拒绝。
- [ ] 本批只完成配对控制出站路径；文件 TCP 出站仍使用既有 `Network.getSocketFactory()` 方式，后续需将其纳入同一 `LanNetworkPath` 校验/诊断契约并覆盖路径代数。
- [ ] 当前路径一致性使用 IPv4 前缀约束；尚未覆盖多路由/非直连路由、IPv6 和热点 AP 无 Network 的绑定方案。热点主机仍明确不可宣称支持。
- [ ] CI 成功后仍需双 Android 真机验证：配对初始握手、确认/拒绝/取消、COMMIT ACK、蜂窝并存时的真实出口路径，以及切 Wi-Fi/热点后旧会话失效和持久化 COMMIT 恢复。


## 本批推进记录（2026-11-27）：Python 跨平台接口绑定适配第一步

- [x] 新增 `lan_p2p_socket_binding.py`：Linux 使用 `SO_BINDTODEVICE`，Windows 使用 `IP_UNICAST_IF`（接口索引按网络字节序传递）；两者均额外绑定所选接口源 IPv4 地址。macOS 暂不支持、不测试，也不纳入当前验收范围。
- [x] 绑定前校验接口名、IPv4 地址、前缀和接口索引；可选校验远端地址必须属于所选路径子网。接口不存在、平台不支持、系统拒绝绑定或参数不合法时抛错，不允许回退默认路由。
- [x] 新增跨平台适配单元测试：覆盖 Linux/Windows 参数编码、源地址绑定、跨子网端点拒绝、无效前缀、非 IPv4 socket、接口索引缺失及未知平台 fail-closed。
- [x] 新增 `requirements-lan-p2p.txt`，将 `psutil` 作为 LAN Python 接口枚举的明确依赖；CI 安装并编译新适配模块，执行新增测试。
- [ ] **重要限制**：本批适配器及测试只证明平台 API 调用参数和失败边界正确，不证明当前运行主机具有所需权限，也不证明真实出口接口/路由正确；当前阶段必须在 Linux、Windows 实机上检查 socket 绑定结果和实际收发路径；macOS 不纳入本阶段。
- [x] **Android 文件 TCP 出站路径快照接入**：生产 `LanTransferSenderService` 每次 Offer/分片重连均重新读取所选 Wi-Fi `Network` 的 `LinkProperties`，构造不可变 `LanNetworkPath`；`LanTransferSenderWorker` 将快照传入 `LanTransferTcpClient`，后者在 `connect()` 前调用 `LanSocketBinding.bindStreamSocket()`，并拒绝非 IPv4 字面端点、跨所选前缀端点及无 Network 路径。Offer 与每个分片会话使用同一条路径契约；旧 Network 失效时连接失败并保留可恢复任务，不回退默认路由。旧 `NetworkProvider` 入口仅保留兼容测试用途。
- [ ] 本批未修改聊天消息协议、持久化、ACK/重试、聊天 UI、文件协议、对讲数据面及 Rescue2 旧日志功能。


## 本批推进记录（2026-11-27）：Android 文件 TCP 出站路径快照统一

- [x] 文件发送生产服务不再只传递裸 `Network`；每次尝试均从同一 Network 的 `LinkProperties` 构造 `LanNetworkPath`，要求存在接口名和可用 IPv4 地址/前缀。
- [x] Offer 和每次断点续传 TCP 会话均使用路径快照；新建未连接 Socket 后先 `LanSocketBinding.bindStreamSocket()`，再 connect。
- [x] 文件端点只接受规范 IPv4 字面地址，且必须属于快照本机 IPv4 前缀；DNS 主机名、跨子网地址、无 Network 路径均 fail-closed。
- [x] 新增 IPv4 子网成员判断单测，覆盖 /24、/16、非八位边界及非法前缀。
- [ ] 路径快照在每次重连时重新选择；当前活跃 TCP 会话不会因网络回调主动中断，而是在旧 Network 断开/连接失败后按既有任务状态机暂停并恢复。后续可评估是否需要路径代数监听来更快关闭旧会话，但不得破坏已完成分块和任务恢复。
- [ ] Android 本次代码层路径绑定仍需普通 Wi-Fi 双机真机验证；不代表 Android 热点主机无 Network 接口已经支持。
- [ ] Python 跨平台适配仍需 Linux/Windows 实机验证实际 socket 出口和权限差异；单测仅验证 API 参数和 fail-closed 行为。macOS 暂不纳入范围。

## 本批推进记录补充（2026-11-27）：Python 文件 TCP 路径绑定接入

- [x] connect_transfer_peer() 增加 network_path 参数；指定路径时先绑定接口及本机源 IPv4，再连接目标；目标必须是所选前缀内的 IPv4 字面地址。
- [x] 未传路径时，Python 文件 TCP 客户端仅允许 loopback 测试连接；非 loopback 连接必须显式提供路径，防止生产连接静默使用系统默认路由。
- [x] TransferTcpServer 增加路径参数；指定路径时先绑定接口，再绑定快照本机地址/端口；未提供路径时仅允许 loopback 监听，不再接受通配/非 loopback 监听。
- [x] Python 绑定适配器支持 listener 模式：只做网卡绑定，由 listener 随后绑定明确端口；client 模式额外绑定源地址和临时端口。
- [x] 新增回归测试：无路径时拒绝非 loopback 客户端/服务端端点；现有 loopback 协议集成测试继续通过。
- [ ] **剩余限制**：目前 Python 文件 TCP 调用方需要把实际枚举的 IPv4InterfacePath 显式传入 network_path；接口绑定 API 尚未在 Linux/Windows 两个平台做真实网卡收发验收。当前 CI 只在 Ubuntu 上验证 Linux 代码路径参数和跨平台参数单测；macOS 暂不纳入范围。
- [ ] Python TransferTcpServer 的路径模式仍需由实际服务启动入口选择对应 LAN path；不可把测试 loopback 模式视为正式 LAN 节点已验收。


## 本批推进记录（2026-11-27）：Python 文件传输监听器路径生命周期

- [x] 新增 `lan_p2p_transfer_runtime.py`：将接口枚举、唯一路径选择、TransferTcpServer 创建和路径变化重绑收敛为独立运行时适配器。
- [x] 多网卡下不再默认取第一张网卡；调用方必须指定 interface_name/address，只有恰好一个候选时才允许自动选择。
- [x] 启动时将选中的 IPv4InterfacePath 同时作为监听地址和 network_path 传入 TransferTcpServer；实际绑定仍由既有跨平台 socket adapter 执行，失败不回退。
- [x] refresh() 在路径完全相同的情况下保留 listener；路径变化时先关闭旧 listener 再绑定新路径；无候选时关闭 listener；新路径绑定失败时保持停止状态。
- [x] close() 幂等，关闭后禁止重新启动；新增运行时测试覆盖唯一选择、多网卡歧义、同路径保留、切网重绑、路径消失、绑定失败和关闭后重启拒绝。
- [ ] **集成边界**：运行时适配器已经提供给宿主程序的正式启动/刷新入口，但仓库当前尚无调用 TransferTcpServer 的 Python 生产服务启动点；本批不伪造“已接入 chat.py/server2.py”。后续需明确哪个 Python 节点部署文件接收服务，并由其生命周期调用 start()/refresh()/close()。
- [ ] **平台验收边界**：Linux CI 的参数测试不能证明 SO_BINDTODEVICE 权限和 Windows IP_UNICAST_IF 在真实主机的出口行为；仍需 Linux、Windows 实机验证。macOS 暂不考虑。
- [ ] 不修改聊天消息/长消息 ACK 状态机、对讲数据面、Android 文件协议、Rescue2 旧日志 `pull_logs_multi()`。


## 范围修正（2026-11-27）：暂不支持 macOS

- [x] 当前 LAN P2P V2 Python 跨平台目标收敛为 **Linux + Windows**；Android 继续独立作为核心平台。
- [x] 从 Python socket 绑定适配器移除 macOS `IP_BOUND_IF` 分支，macOS 当前会按不支持平台 fail-closed，不会误报绑定成功。
- [x] 移除 macOS 专属模拟测试；保留 Linux `SO_BINDTODEVICE`、Windows `IP_UNICAST_IF` 参数与失败边界测试。
- [x] 后续 CI、安装说明、跨平台集成矩阵均以 Linux/Windows 为准；macOS 不作为交付、测试、兼容性或真机验收门槛。
- [ ] 若未来重新纳入 macOS，必须单独评估接口绑定实现、权限和真实出口行为，再增加适配及测试；不得仅恢复一个平台分支就宣称支持。


## Python 文件传输生产宿主审计（2026-11-27）

- [x] 审计当前 Python 入口：`chat.py` 是独立/代理模式的 UDP 聊天与 Web 前端进程；`server2.py` 是设备管理、HTTP/UDP 服务进程。两者当前均未导入或调用 `lan_p2p_transfer_*` 文件传输模块。
- [x] 确认 `TransferTcpServer`、`TransferListenerRuntime`、`TransferStore`、`TransferFilesystem` 和 TCP PSK challenge 目前属于可测试的传输组件，尚未构成一个可直接启动的 Python P2P 文件接收节点。
- [x] 确认当前没有已接入生产的 Python 文件接收生命周期：没有宿主启动/网络变化刷新/退出关闭的实际调用链；因此不能把 `TransferListenerRuntime` 的单测通过描述成 Python 节点已监听或 Android↔Python 文件传输已打通。
- [x] **生产接入前置条件 A（服务 API 层）**。Python 接收服务要求调用方显式提供稳定 Peer ID；新增只读逐设备凭据存储，授权判断与密钥读取均按 Peer ID 查询，发现缓存/IP/聊天口令不参与授权。当前凭据必须由独立可信配对流程安全 provision，不能从 AndroidKeyStore 加密 SharedPreferences 直接读取。
- [x] **生产接入前置条件 B（独立服务骨架）**。新增 `lan_p2p_transfer_service.py`，不自动挂入 `chat.py`/`server2.py`；显式构造后才可 start，统一持有 TransferStore、TransferFilesystem、TransferListenerRuntime，提供 start/refresh/close、实际 address/port、错误回调和退出清理。
- [ ] **生产接入前置条件 C：跨端认证兼容**。核对 Android 与 Python 的认证 transcript、密钥派生、Peer ID 编码和配对密钥来源完全一致；未完成逐字段兼容测试前，不启用真实文件接收。
- [ ] **验收**：独立 Python 进程可启动并报告实际绑定接口/地址/端口；接口变化后旧 listener 关闭并重新绑定；无可信配对、绑定失败、接口歧义均 fail-closed；Android↔Python 与 Python↔Python 分别完成 offer/chunk/ACK/整文件 SHA-256/断点续传的真实 socket 测试。
- [ ] 以上完成前，Python 文件 TCP 仅视为传输组件与模拟/单测能力，不视为已交付生产节点。不得修改现有聊天/长消息 ACK、对讲数据面或 Rescue2 旧日志 `pull_logs_multi()`。


## 第一批推进记录（2026-11-27）：Python 文件接收服务骨架与逐设备信任接入

- [x] 新增 `PeerCredentialStore`：只读加载显式 provision 的 JSON 凭据映射，限制 1–256 个 Peer；校验 Peer ID、Base64 格式及解码后恰好 32 字节；POSIX 凭据文件要求 0600 或更严格；不接受空信任表、未知版本、错误密钥和不安全文件权限。
- [x] 新增 `LanTransferService`：独立于聊天进程；显式 start/refresh/close；统一管理 SQLite TransferStore、TransferFilesystem、TransferListenerRuntime；只绑定已枚举且唯一/显式选择的 IPv4 路径；报告真实监听地址/端口；网络路径消失时关闭监听；错误回调异常不会破坏服务清理。
- [x] TCP 认证接入逐设备授权与 secret provider；认证前先检查 Peer ID 是否在显式可信表，随后只取该 Peer 的 PSK；缺失凭据不会回退共享密钥。
- [x] Android 兼容性审计：Android 配对凭据存储在 AndroidKeyStore 封装的 SharedPreferences 中；每个 Peer 的 32 字节配对密钥以 Base64 字符串保存；Android TCP authenticator 对 Base64 字符串 UTF-8 字节执行 `SHA-256("lan-p2p-v2\\0" + secret)`。Python 侧凭据格式及既有认证派生与此保持一致。
- [ ] **安全集成限制**：本批未增加从 AndroidKeyStore 解封装并导出密钥的 UI/接口，也没有从聊天口令推导文件凭据。Python JSON 凭据文件需要由受信任的独立配对/管理员 provision 流程生成；在该安全导出流程完成前，不能声称 Android 与 Python 已完成真实配对。
- [ ] **Windows 文件权限**：当前 POSIX 0600 校验在 Linux 生效；Windows ACL 权限需在 Windows 实机/安装器方案中单独验证，不能将 POSIX mode 位校验当作 Windows 安全边界。
- [x] 新增服务/凭据测试：可信 Peer 精确匹配、未知 Peer 拒绝、空表/错误版本/非法 Peer ID/非法 Base64/错误密钥长度拒绝、POSIX 宽权限拒绝、唯一接口启动、路径变化重绑、无路径关闭、关闭幂等、多网卡歧义 fail-closed。
- [ ] **本批仍未完成端到端文件传输验收**：本服务骨架和认证凭据入口不等于 Android↔Python 文件协议闭环；offer/chunk/ACK/最终 ACK/SHA-256/断点续传的真实跨端 socket 测试继续列入后续批次。
- [ ] Linux/Windows 真实网卡绑定权限与实际出口验证仍需实机完成；CI 单测不替代平台实测。


## 第一批次收尾与代码层完成记录（2026-11-27）

- [x] 新增 `lan_p2p_transfer_provision.py`：提供交互式、非命令行明文输入的逐设备 PSK provision；验证 Peer ID、标准 Base64 和 32 字节密钥；拒绝默认覆盖；POSIX 输出文件使用 0600、临时文件原子替换和 fsync。
- [x] 新增持久化 Python Peer ID 生成/读取：首次生成随机稳定 ID，后续从权限受限文件恢复；不从 IP/MAC/发现结果推导身份。
- [x] 新增 `lan_p2p_transfer_host.py`：独立前台进程入口，显式传入凭据、数据目录、身份文件、网卡选择和端口；启动后报告真实 Peer ID/监听地址/端口；SIGINT/SIGTERM 时关闭服务。不会由聊天/设备管理进程自动启动。
- [x] 新增 provision 和 identity 测试，覆盖密钥长度、Peer ID、覆盖保护、符号链接拒绝、0600 权限和身份稳定性。
- [x] 第一批 Python 生产宿主的**代码/API/CI 范围**完成：独立服务、显式凭据 provision、稳定本地身份、真实前台启动入口、路径生命周期和 fail-closed 自动化测试均已具备。
- [ ] **部署安全边界仍未完成**：管理员 provision 的 PSK 必须来自双方已完成认证的安全配对渠道；不能从 AndroidKeyStore 明文导出，也不能通过聊天口令推导。Python↔Android 自动配对密钥同步不是第一批完成项。
- [ ] **操作系统实机验收仍待完成**：Linux SO_BINDTODEVICE 权限/实际出口和 Windows IP_UNICAST_IF/ACL 必须分别实测。代码与 CI 通过不等于 Linux/Windows 部署验收通过。
- [ ] **真实跨端传输仍待完成**：Android↔Python 真实 socket 的 offer/chunk/ACK/最终完成 ACK/SHA-256/断点续传，归入第二批跨端闭环和后续真机验收。

## 第二批次推进记录（2026-11-27）：Android/Python 协议黄金向量

- [x] 新增 Python 跨语言测试，使用 Android `LanTransferFrameCodecTest` 已有的 Python golden wire bytes，验证 Python encoder 输出逐字节一致，并验证 Python decoder 可读取同一 wire frame。
- [x] 新增认证黄金向量：固定 32 字节 client/server nonce、Peer ID、逐设备 Base64 PSK，逐项验证 PSK 派生 key、server proof、client proof 和 session key。
- [x] Android `LanTransferAuthenticatorTest` 增加同一认证黄金向量断言；通过反射调用既有私有原语验证双方使用同一 UTF-8、长度前缀、domain separation、HMAC-SHA256 和 session 派生，不改变线上认证协议。
- [ ] 继续补齐 Android/Python Offer、Chunk、Chunk ACK、Missing、Complete、Cancel 帧的跨语言双向黄金向量与状态语义验证。
- [ ] 继续验证 Android↔Python 真实 TCP socket 认证及完整文件传输，不以共享 fixture 或各自 loopback 单测替代。


## 第二批次推进补充（2026-11-27）：帧协议跨语言一致性修正

- [x] 新增共享 fixture：`app/src/test/resources/lan_p2p_frame_vectors.json`，包含 Offer、Offer ACK、Chunk、Chunk ACK、Missing、Complete、Cancel 共 7 种帧的完整 wire hex、元数据、分片索引和 payload。
- [x] Python 测试逐种帧验证编码结果与共享 wire hex 完全一致，并从 socket stream 解码同一 wire frame；Android 单测从同一 fixture 读取，逐种帧验证 Java 编码字节及解码结果。
- [x] 跨语言审计发现并修正真实协议差异：Android 原 canonical JSON 使用 `JSONObject.quote()`，会把 MIME 类型中的正斜杠编码为 `\\/`，而 Python `json.dumps(ensure_ascii=False)` 输出原始 `/`，导致 HMAC 与帧字节不一致。Android codec 现使用与 Python 一致的 JSON 字符串转义（不转义正斜杠、控制字符按 JSON 规范转义）。
- [x] 认证向量使用 Android 实际存储格式：Base64 编码的 32 字节逐设备密钥；双方验证 SHA-256 key derivation、server/client HMAC proof 和 session key。
- [x] Python CI 已通过 410 项测试；共享七帧向量、认证向量和服务信任撤销测试均包含在内。
- [ ] Android 单测/APK 构建正在对最新修正重新执行。此前 Android 失败分别定位为测试向量 NUL 转义写错、Android JSON 正斜杠转义不兼容；均已修正并提交，不能把先前失败运行计为通过。
- [ ] 仍需补齐真实 Android↔Python TCP socket 双端运行测试，以及完整 offer/accept/chunk ACK/missing/complete 状态语义、SHA-256、断点续传重启测试。黄金向量只证明序列化/认证原语兼容，不替代端到端传输验收。

## 第二批次推进补充（2026-11-27）：VERIFYING 状态崩溃恢复语义对齐

- [x] 对照 Python `serve_transfer_session()` 与 Android `LanTransferTcpSession.serve()`，发现 Android `complete` 请求只接受 `transferring/completed`，而 Python 同时接受 `transferring/verifying/completed`。
- [x] 确认 Android `LanTransferFilesystem.assembleAndPublish()` 已具备 `verifying` 状态恢复逻辑：可恢复 rename 前后的崩溃窗口、重新校验 staging receipts、完成 SHA-256 并原子发布；但 TCP session 的状态门禁此前阻止了远端重连触发该恢复。
- [x] Android TCP session 现允许 `verifying` 状态收到 `complete` 后进入既有恢复/校验流程；未改变状态机迁移规则、文件落盘顺序或聊天数据面。
- [x] 新增 Android loopback TCP 回归测试：预置完整 chunk receipt 并将任务置于 `verifying`，模拟进程重启后发送 `complete`，验证服务端完成发布、状态进入 `completed` 且文件 SHA-256/内容一致。
- [x] Android Gradle `testDebugUnitTest`、诊断 Debug APK 与 slim Release APK 构建均通过（CI run `45914317953`）；Python 全量 unittest/py_compile 在加入 VERIFYING 恢复回归测试后通过（CI run `45914457579`）。此修正只解决一个已定位的跨端恢复状态门禁差异，不代表完整 Android↔Python 端到端互通验收完成。
- [x] 同步新增 Python socketpair 回归测试：将接收端任务预置为 `VERIFYING`，重连发送 `complete`，验证 Python 服务端同样可恢复并完成 SHA-256 校验与文件发布；Android/Python 两端分别覆盖同一恢复语义。

## 第二批次推进补充（2026-11-27）：重复 Offer / Chunk 幂等性

- [x] 新增 Python TCP socketpair 回归测试：在已有 `TRANSFERRING` 任务上重放完全相同的 Offer，确认返回 `new_offer=false` 且不重置状态；同一 Chunk 连续发送两次，确认第一次 `new_receipt=true`、重放 `new_receipt=false`，账本只保留同一份 durable receipt。
- [x] 新增 Android loopback TCP 回归测试，覆盖相同的 Offer 与 Chunk 重放语义。
- [x] Python 全量 CI（Run `45915399980`）通过，Android Gradle 单测及两种 APK 构建仍在执行中。
- [ ] 后续继续覆盖断开导致 ACK 丢失后的新连接重新查询、损坏 receipt 失效后的缺片回补，以及两端真实 socket 互通；当前幂等测试不等同于跨进程/跨设备端到端验收。

## 第二批次推进补充（2026-11-27）：取消清理语义对齐

- [x] 发现 Python TCP `cancel` 过去只更新 SQLite 状态，没有像 Android 一样清理私有 staging 文件；修正为先持久化 `CANCELLED`，再清理 staging，最后发送 cancel ACK。重复取消仍按终态响应且清理可幂等重试。
- [x] 新增 Python 回归测试，确认 ACK 返回后 staging 占用归零且任务状态为 `CANCELLED`（Run `45915548543` 全部通过）。
- [x] 新增 Android 对应 loopback TCP 回归测试；Android Gradle `testDebugUnitTest`、诊断 Debug APK 与 slim Release APK 全部通过（Run `45915577083`）。
- [x] 同一 Run 的 Python `py_compile` 与全量 unittest 通过：`413 tests`。

## 第二批次推进补充（2026-11-27）：ACK 未被发送端观察后的跨连接恢复

- [x] 新增 Python 与 Android 回归场景：接收端已持久化 Chunk 并发送 ACK，但发送端主动关闭连接且不读取 ACK；随后建立新 TCP 会话，重新查询 Missing 页面，必须以 durable receipt 为准跳过已落盘分片并完成剩余分片。
- [x] Python 全量 CI（Run `45915852728`，414 tests）通过；Android ACK 丢失恢复 CI（Run `45915890221`）的 `validate-python`、`detect-android-changes`、`build` 三个 job 全部 success。该测试覆盖应用层未观察到 ACK 的断线恢复路径，不模拟物理网络设备丢包或 Wi-Fi/AP 切换。

## 第二批次推进补充（2026-11-27）：损坏 receipt、多页 Missing 与终态重放

- [x] 新增 Python 损坏 staging chunk 回归测试：receipt 已存在但文件字节被篡改时，`reconcile_page` 必须删除无效 receipt、重新报告缺片；重新发送正确 Chunk 后 receipt 恢复有效。
- [x] 新增 Android 对应回归测试：修改 `payload.part` 已落盘字节后，`reconcilePage` 必须失效对应 receipt、回到可补传状态；正确 Chunk 可重新落盘并生成 durable receipt。
- [x] 新增 Python 130-chunk 多页恢复测试：按每连接 62 个 chunk 的预算，跨三条认证 TCP 连接依次处理 62/62/6 个 chunk，验证 Missing 页持续前进、无重复死循环、最终 SHA-256 与发布文件完全一致。
- [x] 新增 Android 对应 130-chunk/三次认证重连测试，验证同一多页语义和最终文件内容。
- [x] 新增 Python 与 Android 终态重放测试：已完成任务重复 `complete` 返回 completed；随后重放 `cancel` 仍返回 completed，不能回退状态或删除已发布文件。
- [x] 新增回归测试 CI 已通过：Python 全量 417 tests（Run `45917117043`）；Android `testDebugUnitTest`、诊断 `assembleDebug`、slim `assembleRelease -PincludeDiagnostics=false` 均通过（同一 Run `45917117043`）。
- [ ] 多页协议当前采用“每次连接从 Missing 起点重新查询、发送本连接首批缺片、Complete 再返回下一批缺片”的有界推进方式；`more` 是提示后面仍有缺片，不代表客户端必须在同一连接无限翻页。跨连接测试用于证明该约定无漏片/死循环。
- [x] 增加 JVM Android 客户端→Python 接收服务的真实 TCP 双端进程测试，以及 Python 发送端→Android Robolectric TCP 接收服务的反向测试；两条路径均执行真实 loopback TCP socket、双方互相认证、Missing/Chunk ACK/Complete 与最终文件 SHA-256/字节发布验证。CI Run `45919960578` 全部通过。

- [x] 补测“staging 文件已写入但进程在 receipt 提交前崩溃”的 orphan chunk 窗口：Python 仅在无 receipt 且目标是可安全读取的普通文件时允许替换孤立损坏数据；有 receipt 的冲突数据仍拒绝覆盖；Android 覆盖单文件 staging 的同一恢复窗口。Python 418 tests 和 Android 单测/Debug APK/slim Release APK 均通过（Run `45917427009`）。
- [ ] 下一项优先实现 JVM Android 客户端↔Python 服务端真实 TCP 双向互通测试，覆盖真实认证握手、Offer、显式 Accept、Chunk ACK、Missing、Complete 和 SHA-256 发布；不得仅以共享 golden vectors 或两端各自 loopback 测试替代。
- [x] orphan chunk 修复的 Python 回归覆盖：模拟只写入部分/损坏 staging 文件、未提交 receipt 的崩溃窗口，重传后文件被正确替换并建立 durable receipt。
- [x] orphan chunk 修复的 Android 回归覆盖：模拟 `payload.part` 已存在但 receipt 尚未提交，重传正确覆盖对应偏移、恢复 receipt 并通过 reconciliation。
- [x] 最新验证：Python 全量 418 tests（Run `45917427009`）；Android `testDebugUnitTest`、`assembleDebug`、`assembleRelease -PincludeDiagnostics=false` 全部通过（Run `45917427009`）。
## 第三批次推进（2026-11-27）：Android↔Python 双向真实 TCP 互通

- [x] JVM Android `LanTransferTcpClient` → Python `authenticate_tcp_server` + `serve_transfer_session`：真实 TCP 两连接，先 Offer/Offer ACK，接收端显式 ACCEPTED→TRANSFERRING，再 Missing/Chunk ACK/Complete；Python 校验最终发布文件字节与内容。
- [x] Python `authenticate_tcp_client` + `send_offer_over_session` → Android Robolectric `LanTransferTcpSession`：真实 TCP 两连接；Python 发送 Offer，Android 持久化后由测试显式执行 ACCEPTED→TRANSFERRING，Python 再执行 Missing/Chunk/Complete；验证 Android 最终 COMPLETED 状态及发布文件字节一致。
- [x] 两条跨语言方向使用测试专用 PSK，不依赖聊天密钥或生产设备凭据；仅 Linux CI 执行子进程互通测试，避免将 Python 可执行文件要求强加给 Windows/macOS 开发环境。
- [x] CI Run `45920419820`：Python 全量 418 tests；Android `testDebugUnitTest`、诊断 Debug APK、slim Release APK 均通过。
- [ ] 该项验证的是 JVM/Robolectric Android 实现与 Python 运行时在同一主机上的真实 TCP 协议互通，不等同于实体 Android 手机经 Wi-Fi/AP 的真机网络验收；后者仍需双机/热点场景验收。

## 第四批次并行推进（2026-11-27）：Android 热点 AP 本地接口路径

- [x] 修正 AP 本地接口候选只能发现、不能承载服务的问题：`LanSocketBinding.validateServerSocketAddress` 允许 `LOCAL_INTERFACE_CANDIDATE` 绑定到其精确本地地址；文件 TCP Listener 与配对控制 Host 因而可在 AP 地址上创建 ServerSocket。
- [x] AP 候选上的 TCP/配对客户端连接改为绑定候选本地源地址；上层仍必须先验证目标 IPv4 属于所选接口子网。Android `Network` 对象存在时继续使用 `Network.bindSocket`，不改变普通 Wi-Fi 路径。
- [x] AP 候选 UDP 发现：接收端 wildcard 绑定固定发现端口并按所选接口子网过滤来源；发送端绑定候选源地址后发送显式广播地址。候选仍只是发现提示，TCP 双向 PSK 认证仍是唯一身份依据。
- [x] 新增 Robolectric 回归覆盖：未知 OEM AP 接口名、候选源地址绑定、ServerSocket 精确地址校验、AP 候选可构造发现服务、发现来源必须处于所选子网；配对控制候选路径测试已同步更新。
- [x] CI Run `45921608216`：Python 全量 418 tests、Android `testDebugUnitTest`、诊断 Debug APK、slim Release APK 全部通过。
- [ ] 重要限制：Android 对无 `Network` 的 AP 候选使用源地址绑定，不等同于底层 `SO_BINDTODEVICE` 或 `Network.bindSocket`；同网段直连依赖内核对已连接子网的路由选择。仍须在真实 Android 热点主机/客户端设备上验证 OEM 接口、广播接收、配对回连及双向文件传输；若设备 VPN/厂商网络栈阻断源地址路由，应明确失败，不能回退蜂窝。


## 第五批次并行推进（2026-11-27）：Python 宿主网络路径自动刷新

- [x] 补齐独立 Python 前台宿主的生命周期缺口：宿主原先启动后只等待 SIGINT/SIGTERM，没有周期性调用已有的 `LanTransferService.refresh()`；网络切换、热点关闭/重启或本机地址变化时，监听器可能停留在旧路径。
- [x] `lan_p2p_transfer_host.py` 现默认每 3 秒重新枚举 LAN IPv4 路径并刷新服务；新增 `--refresh-interval` 可配置项，必须为正数。
- [x] 继续 fail-closed：无可用/唯一 LAN 路径时由既有 runtime 关闭监听，不回退 wildcard/蜂窝；后续周期发现有效路径时可重新绑定，稳定 Peer ID 不变。重绑定失败或凭据 reload 异常会记录日志并重试，不让瞬时故障直接杀死前台宿主。
- [x] 新增宿主生命周期测试：验证周期刷新被调用、刷新间隔被传递、退出时关闭服务，以及非正刷新间隔被拒绝。
- [x] Python CI Run `45926201669`：`validate-python` 与 Android 变更检测均通过；本批只修改 Python 文件，因此 Android build 按 workflow 条件跳过。
- [ ] Android AP 候选路径仍需真机验证：源地址绑定不是底层设备绑定，也不是 Android `Network.bindSocket`；双机热点场景需核实接口路由、广播接收和回连，不得静默回退蜂窝。
- [ ] Windows ACL 和 Windows 网卡绑定仍需 Windows runner/实机单独验证；POSIX 0600 检查不能作为 Windows 凭据文件安全证明。

- [x] 追加 runtime 回归：模拟网卡枚举临时抛出异常，确认旧 listener 先关闭；下一轮有效接口快照恢复后重新绑定成功。
- [x] 追加 Linux/Windows 绑定失败回归：模拟 `SO_BINDTODEVICE` 权限错误及 `IP_UNICAST_IF` 不可用，确认不会退化为仅绑定源 IP 的假成功。
- [x] 上述追加测试已由 CI Run `45926534295` 验证：Python 全量 `423 tests` 通过；Android 变更检测通过，因仅 Python 文件变更而按条件跳过 Android build。
- [x] 复核发现宿主首次启动时若 Wi-Fi/AP 尚未就绪，原实现会直接退出，无法等候网络恢复。宿主现对初始绑定失败按刷新周期重试；Service 对失败的初始 runtime 执行关闭和清理，后续重试不遗留半启动实例。
- [x] 新增启动竞态回归测试：第一次启动因接口未就绪失败，第二次启动恢复成功，之后正常刷新并在退出时关闭服务；不启用 wildcard 或其他网络回退。Run `45927181959` Python 全量 `424 tests` 通过。
- [x] Linux runner 专项真实 socket smoke test 通过（Run `45927460323`）：以 `lo` 接口执行 `SO_BINDTODEVICE`，并从 `127.0.0.1` 连接 `127.0.0.2`；内核接受设备绑定且 TCP connect/accept 成功。Python 全量 `426 tests` 通过（含该测试，另跳过 Windows 专属用例）。
- [x] Windows runner 专项 `IP_UNICAST_IF` smoke 与 Linux runner `SO_BINDTODEVICE` smoke 均已在各自真实操作系统 CI runner 上通过（Run `45927460323`）；Windows 专项 11 tests、1 个 Linux 专属用例按平台跳过。物理多网卡 LAN 出口、Linux 不同权限配置、Windows ACL 仍需对应部署环境实机验收。
- [x] Windows runner 专项校验通过（Run `45927389104`）：Windows 真实 loopback TCP socket 通过 `IP_UNICAST_IF` 绑定指定接口，并成功从 `127.0.0.1` 连接 `127.0.0.2`；Windows socket binding 专项 10 项通过。测试过程中发现并修正 `socket.if_nameindex()` 在 Windows 返回 `(index, name)` 的平台差异。该结果验证 Windows API 参数/字节序与本机 loopback 连接，不替代多网卡物理 LAN 出口验收。
- [x] 新增服务进程重启集成测试：关闭并重新创建 `LanTransferService` 后，未完成任务仍为 TRANSFERRING，已 fsync/登记的 staging 分片与 receipt 可重新校验，缺失分片账本保持正确，可信 Peer 列表可加载，服务可重新启动。Run `45928099513`：Python 全量 `427 tests` 通过（1 项跨平台跳过），Windows socket binding 专项通过，Android 变更检测通过；仅 Python 变更，Android build 按条件跳过。
- [x] 启动等待网络就绪修正代码提交 `9b2121649a8ad9803445382d66d5af0d8ffbf037`，测试夹具修正后 Run `45927181959` 全部通过：Python 全量 `424 tests`，Android 变更检测通过；Android build 按条件跳过。

## 第六批并行推进（2026-11-27）：Android 聊天 Listener 精确 STA 绑定

- [x] 将现有 STA Wi-Fi 聊天 UDP Listener 从“Network.bindSocket + wildcard 0.0.0.0:8888”改为 `LanSocketBinding.bindDatagramListenerSocket()`，直接绑定所选 `LanNetworkPath.localAddress:8888`；Network path、interface、IPv4 地址及 prefix 均来自同一 LinkProperties snapshot。
- [x] 仅当当前没有可用 Android Wi-Fi Network 时，保留原有 wildcard UDP 兼容入口；若已选择 Wi-Fi Network 但精确绑定失败，则启动失败并按既有退避重试，不回退到默认路由。
- [x] 新增 Python 静态架构回归，约束聊天 Listener 必须使用精确地址绑定、并验证 wildcard 仅位于未绑定 fallback 分支。
- [ ] AP local-interface candidate 尚未接入 TrackingService 聊天 Listener；不能据此宣称 STA/AP 双 Listener 已实现。
- [ ] 仍需 Android Robolectric/单元测试、Python 全量测试、Windows 检查及 Debug/Slim Release APK 构建验证；真机多网卡和热点验收另计。


### 批次 A（2026-11-27）：单 Listener 生命周期实例化
- [x] 为当前唯一 LAN chat listener 增加 path/generation 级实例身份，绑定其 socket 与 worker identity。
- [x] 网络替换时标记旧实例停止；socket 关闭仍在 lifecycle lock 外执行。
- [x] 覆盖旧实例迟到清理、停止后资源附着、socket/thread identity 的单测。
- [ ] 将 bind/receive/close/multicast lock 的完整生命周期进一步收敛到 owner；逐项覆盖 onDestroy、bind failure、thread start failure 和网络切换竞态。
- [ ] 保持单路径只有一个 listener；完成后再进入 STA/AP 第二 Listener。


- [x] 批次 A 单 Listener lifecycle instance 改造及组件测试；CI run 45980782340 全绿（Python 440 tests/1 skipped、Windows socket binding、Android testDebugUnitTest、Debug APK、diagnostics=false Slim Release APK）。
- [ ] 真机验证当前单 Listener 在启动/停止/网络切换/服务销毁下的 socket/thread 生命周期；完成后再启动第二 Listener。


- [x] Listener 实例成为 Socket/Worker 的生命周期资源来源；重启、网络切换和 onDestroy 均先标记停止，再在锁外关闭/中断；attach 拒绝 closed Socket 与已启动 Thread。
- [x] 最新 CI run 45981692678 全绿：Python 440 tests（1 skipped）、Windows socket binding、Android 单测、Debug APK、diagnostics=false Slim Release APK；artifact 15189847113。


### 批次 B 进展：AP/local-interface 路径规划（尚未完成第二 Listener）
- [x] 新增 `LanChatListenerPathPlan`：单路径严格一个 primary；STA + 一个未表示本地候选时规划 secondary；优先 Android Network primary。
- [x] 网络路径 fingerprint 实际变化后才刷新聊天路径计划；不在单包/发送快路径枚举接口。
- [x] Java 测试覆盖单路径、STA+AP候选、仅本地候选、稳定排序和非法输入。
- [ ] 增加热点状态变化的按需事件触发，避免依赖接口名猜测 AP。
- [ ] 第二聊天 Listener 真正 per-path socket/thread 生命周期接入；不能只停留在计划对象。
- [ ] 多 Listener ingress、ACK、peer×path 独立认证与旧命令分发集成。
- [ ] 真机确认 AP candidate 的实际角色与 exact-address bind 成功。


## 2026-11-27 批次 B/C 状态修订

- [x] 批次 B secondary Listener 代码接入：独立 socket/worker/path/generation/lease，精确地址绑定，secondary authenticated-only ingress，按身份停止与清理；相关 Android/Python/Windows CI 已通过。
- [x] 批次 C 已启动：审查 ACK/fragment ACK 的 ingress snapshot，确认异步处理传递接收时的 path/socket/generation；ACK 账本更新需当前 listener lease 与 peer×path route；多 Listener 下禁用 pathless peer-wide endpoint fallback。
- [x] 新增 tests/test_lan_chat_ingress_ownership_contract.py，锁定 secondary ingress snapshot、ACK 回发 socket 归属和 path-scoped ACK 接纳契约；CI run 45993507802 待完成。
- [ ] 批次 C 剩余：peer.hello 异步回包 stale ingress 检查、peer.ack route admission/generation 竞争、双路径重复消息持久化与 ACK 幂等、共享旧命令语义逐项审查。
- [ ] 批次 D：一次性/有界备用 RouteBinding 回退、按 peer×path 记录真实 ACK RTT/失败事实、普通单路径性能回归与真机验收包。
- [ ] 首次真机验收估算修订为：批次 C 收口 + 批次 D 测试准备，约 2 个主要实施批次；另预留条件修复批次 E。进入真机验收不代表拓扑通过。

## 2026-11-28 历史日志问题复核（代码核查，不等于真机验收）

### 历史问题 1：固定收件人部分 ACK 后长期补发

- [x] Android Outbox 按 peer 持久化 ACK 与 attempt count；已 ACK 的 target 不参与后续重发，部分 ACK 只重试未 ACK target。
- [x] Android 单 target 每轮最多 8 次自动发送；耗尽后进入 `retry_paused`，普通 retry timer 不会重置预算。
- [x] Android 只有全局网络/Listener 恢复或对应 Peer 路径重新验证等真实恢复事件才能重新开放耗尽预算，并持久化 60 秒冷却；恢复事件和 SQLite 失败有保留/重试保护。
- [x] Python Outbox 同样按 target 记录 ACK/attempt count，8 次封顶，只有 Peer 验证恢复事件可在 60 秒冷却后重开；已 ACK target 不会被重复发送。
- [x] 已有回归覆盖 partial ACK 只重试剩余固定 Peer、重试次数封顶、恢复冷却、普通 timer 不重开耗尽预算及进程重启后恢复状态。
- [x] **Android Outbox 生命周期收尾（2026-11-28）**：容量提升为 200 条 + 8 MiB 正文预算；未完成消息自创建起 7 天过期，过期只转为 `expired` 并保留投递记录，不静默删除；用户可在聊天发送状态中确认取消未完成消息，或二次确认清理已送达/已取消/已过期的本机 Outbox 终态记录。清理不删除聊天历史、不操作对端设备。迟到的已认证 ACK 仍可更新目标状态；全部目标最终 ACK 时可收敛为 `delivered`，未全部 ACK 时保持过期终态。
- 结论：旧日志中的无界连续自动重发路径已由有限 attempt budget + retry_paused + recovery cooldown 收敛；Android 长期滞留风险已由 7 天 TTL 与显式取消/清理入口收敛。Python Outbox 的 TTL/容量策略仍需单独对齐；代码与 CI 通过不替代真机验证。

### 历史问题 2：UDP 对讲长消息单包上限

- [x] Android→Android 代码路径已支持长消息分片：短消息保留原单包快路径；超限消息按 300 raw-byte 分片，单个认证 UDP 数据报仍不超过 1200 bytes；同一 messageId 绑定整条消息 SHA-256。
- [x] Android 固定收件人有持久化分片 ACK 位图和最多 8 片的选择性窗口；接收端分片缓存有 TTL/数量/总容量上限，先持久化分片、再回分片 ACK，完整组装并写入 Inbox 后才回最终 chat.ack。
- [x] Python `lan_p2p_core.py` 补齐持久化接收分片会话：30 分钟 TTL、32 会话、2 MiB 缓存上限、乱序/重复片与元数据冲突校验；完整分片组装后才进入服务端 Inbox。
- [x] Python Outbox target 增加持久化 fragment ACK bitmap；发送端按最多 8 片窗口推进，窗口推进不消耗整条消息的 retry attempt；缺片按既有有限重试预算重发。
- [x] `rescue2.py`、`chat.py`、`server2.py` 均已接入长消息首次发送、接收重组、fragment ACK、窗口推进和恢复补发；短消息继续保留 `chat.message` 单包快路径。
- [x] Python 完成分片缓存/ACK 位图/广播目标 admission/消息级端点重试测试，并增加真实 UDP socket 长消息分片往返测试（Python core sender↔receiver）。
- [ ] **端到端验收仍未完成**：Android↔Rescue2、Android↔chat.py/server2 及 Python peer↔Python peer 的真实服务进程/设备双向测试，覆盖乱序、丢片、重复片/ACK、进程重启和最终送达；core UDP 测试与静态源码契约不能替代端到端验证。
- 结论：Android 与 Python 三个服务端点的长消息代码路径均已接入；Python core UDP 往返测试通过，但真实 Android↔Python 互通和多服务部署验收仍待完成，不能提前宣称所有对讲端真机长消息已通过。



## 2026-11-28 三手机真机测试与修复记录

本节记录三台手机安装版本 `20261128-0440-V2520` 后的现场结果。**诊断探针 6/6 PASS 不等于 P2P 通信验收通过。**

### 真机观测

- Redmi 4X（API 25）、Redmi Note 8（API 35）、ZTE A2023BH（API 30）的本地诊断均为 6/6 PASS：运行环境、Wi-Fi、安装、IPv4 接口、UDP 绑定和传输运行时探针通过；当时 `fresh_peer_count=0`，因此没有证明发现/互通成功。
- 三端日志均出现 Android 发送时 `固定目标 0、已验证路径路由 0、待路径验证目标 0`，首次发现窗口结束后进入 `waiting_peers`。这是 Android→Android 发现/收件人路由未建立的直接证据。
- Android 成功持久化接收来自 server2/rescue2 的 P2P 消息；Python 服务群发、单发在用户现场测试中成功。不能据此推断 Android→Android 已通过。
- VPN 开启时，中兴日志将 `tun0 10.10.14.1/30` 作为 `ANDROID_NETWORK` secondary path，反复绑定失败；Wi-Fi 实际路径为 `wlan0 192.168.1.114/24`。
- 热点场景：中兴 AP 接口 `wlan2 192.168.124.144/24`，两台 Redmi 分别为 `192.168.124.10/24` 和 `192.168.124.246/24`；中兴日志记录 AP secondary listener 已绑定，但现场仍未实现三端互通。需要进一步区分热点/AP 客户端隔离与应用发现/接收问题。
- 中兴和 Redmi Note 8 日志均出现“LAN 对讲消息超过单个 UDP 数据包限制，未入队”；这是长消息入队前仍按单个 UDP 包校验的代码问题。
- Windows 上 8080 端口占用时，rescue2/server2 的恢复扫描反复报告 `LanP2PStorageError`，chat 启动闪退；释放端口后用户确认恢复。端口占用与存储异常的因果链尚未由 Windows 日志证明，暂记为已绕过的环境冲突，不记作代码根因已修复。

### 本轮代码修复（代码/CI，不代表真机复验）

- [x] Wi-Fi LAN Network 选择排除 `TRANSPORT_VPN`，即使 VPN Network 同时暴露 Wi-Fi transport，也不得将 tun 接口当作 LAN Listener 路径。
- [x] Android 长消息入队校验改用与实际发送一致的分片编码验证，避免有效长消息在 Outbox 入队前被单 UDP 帧限制拒绝；分片总长度仍受协议上限约束。
- [x] 对讲发送状态区只显示最近 3 条送达摘要。
- [x] 对讲输入栏固定高度，附件/发送按钮缩窄并统一垂直对齐，避免清空输入后布局高度跳变。
- [x] 文件传输发送状态区分“等待网络”和“已发送请求、待对方接收”；初始通知不再提前宣称“等待网络”。
- [x] 文件传输设备列表按稳定身份/端点排序，并在数据未变化时跳过整表重建；“与此设备配对”操作按钮改为紧凑尺寸。
- [x] server2 对讲页打开时主动广播一次发现请求，提供手动刷新入口；服务端按 LAN 接口明确广播地址发送，5 秒节流，并将当前有效租约的 Android peer 合并进目标列表。
- [x] server2 对讲与陪护设备选择器实现“所有人群发”与单设备/多设备互斥；显式清空选择后不再自动回退成全员群发。
- [x] server2 批量离线上报继承当前客户端 envelope 的 `build_version/protocol_version`，避免旧 report item 缺字段时把新设备显示回“旧版 · v1”；缺字段时保留已知版本。
- [x] 新增/更新对应回归测试。最新本轮 CI：run [46120399852](https://github.com/shua00/rebackmyphone3/actions/runs/46120399852)，commit `8a4014cb9c6a3649df075303eb3e9d4b6f9c8d1b`；Python、Windows Socket Binding、Android build 均通过。

### 仍未通过/下一步

- [ ] **P0 阻断**：三台 Android 同网段双向发现与消息收发；必须证明每端 peer.hello/peer.ack、路径指纹、peer route、固定收件人快照和最终 ACK 闭环。
- [ ] **P0 阻断**：中兴热点 STA+AP 双路径；验证 AP secondary listener 收到两台 Redmi 的发现报文并完成双向认证。需用新日志区分“未收到 UDP 广播”“收到但认证/落库失败”“ACK 回程失败”。
- [ ] **P0**：VPN 开启时重新验证只保留真实 Wi-Fi LAN path，tun0 不再启动 Secondary Listener；开启/关闭 VPN 都应恢复发现。
- [ ] **P0**：长消息重新实测：中兴→Redmi、Redmi→中兴，超过单 UDP 包但不超过协议总长度上限；验证分片、乱序/重复、最终 ACK 和重启恢复。
- [ ] **P1**：文件传输发现需记录候选接收/拒绝原因，确认热点 AP 下中兴是否实际收到两台 Redmi 的 beacon；测量 Offer 等待、接受后吞吐、分块耗时和重传率后再调参。
- [ ] **P1**：验证 server2 主动发现、目标列表更新、群发/多选互斥、版本显示；核查 Windows 8080 冲突时的实际异常原因，必要时补充明确端口占用诊断。
- [ ] 真机复测前，不将本轮代码/CI 结果标记为 Android↔Android、热点多路径或文件传输实机通过。


### 追加：发现链路诊断与 rescue2 多网卡广播（2026-11-28）

- [x] Android Chat 增加异步诊断记录：收到 peer.hello 的来源 IP/端口/ingress path、peer.ack 回发路径、peer.ack 持久化失败，以及最终 verified route。日志写入 peer executor/control executor，不在 UDP 接收快路径执行文件 I/O。
- [x] 文件发现增加候选接受和跨子网拒绝日志，下一轮真机日志可直接区分“没有收到 beacon”和“收到但被子网校验拒绝”。
- [x] rescue2 启动发现与首次广播改为枚举所有活动 IPv4 LAN 接口的 directed broadcast，并保留 limited-broadcast 兜底；不扫描公网、不猜测子网、不增加简单单网卡设备的周期探测。
- Android 诊断日志与 UI/长消息/传输状态改动的 APK build：run [46120796577](https://github.com/shua00/rebackmyphone3/actions/runs/46120796577)，四项 job 均通过，artifact `app-diagnostic-and-slim-apks`。
- 最新 Python-only 变更（rescue2 多接口广播与回归测试）：run [46120972364](https://github.com/shua00/rebackmyphone3/actions/runs/46120972364)，Python 与 Windows Socket Binding 通过；Android build 按变更检测跳过（该提交未改 Android 文件）。


- **拓扑补充**：中兴日志显示其上游/服务端网络为 `192.168.1.x`，热点 AP 为 `192.168.124.144/24)，两台 Redmi 在热点下为 `192.168.124.x`；rescue2 主机日志目标为 `192.168.1.178:9090`。若 Windows 主机只有 `192.168.1.x` 接口且没有到 `192.168.124.0/24` 的路由，rescue2 的广播不会穿越 Android 热点 NAT 到达两台客户端。验证 rescue2 对热点客户端发现时，Windows 主机也需连接该热点或具备明确可达路由；否则应归类为拓扑不可达，而非发现代码失败。


## 文件传输双模式与远程策略（新增 P0 架构契约）

详细契约见 [FILE_TRANSFER_MODES_AND_REMOTE_POLICY.md](FILE_TRANSFER_MODES_AND_REMOTE_POLICY.md)，并与统一网络感知架构联动。

- [x] 确认产品决策：默认简易快捷模式；用户可切换 quick/secure；Rescue2、Server2 可远程切换、设置默认或强制限制为其中一种；快捷模式接收策略可选逐次确认或自动接收。
- [x] 明确模式与接收策略分离、自动接收来源/资源限制、远程策略版本与回执、离线保持最后有效策略、强制策略变更时进行中传输处理。
- [x] 明确 Rescue2/Server2 双管理端冲突不能采用最后写入覆盖；必须有统一策略权威/revision，或配置管理域优先级。
- [ ] 实现统一 TransferPolicy 本地模型与持久化；新设备默认 quick，接收策略默认 ask_each_time。
- [ ] 实现 quick/secure UI 与用户自主切换、管理限制状态展示。
- [ ] 实现 quick 接收策略 ask_each_time / auto_trusted / auto_all_allowed 与资源限制。
- [ ] 实现 Rescue2/Server2 远程策略签发、权限验证、revision、幂等、有效期、应用回执和冲突处理。
- [ ] 实现强制模式切换时新 Offer 阻断、进行中传输安全处理及恢复重协商。
- [ ] 两种模式共用独立文件传输引擎，验收 Offer/Accept/Reject、分块 ACK、SHA-256、重试、断点续传和重启恢复。
- [ ] 验收单路径快路径无额外扫描/线程/探测；快捷模式不成为跨公网匿名入口，跨网文件传输必须使用安全认证会话/受控中继。

> 当前仅完成产品与架构文档契约；没有声称生产代码、CI 或真机能力已经实现。实施仍服从 LAN P2P 普通双 Android 对讲回归与多 Listener/ACK 收口的依赖顺序。


## 批次 C 追加审计：peer.ack 持久化与 RouteBinding admission 顺序

- [x] 核对当前 `peer.ack` 路径：先执行 `LanChatStore.upsertPeer(...)`（SQLite 将 peer-wide 行标记 active 并更新待发目标候选），之后才调用 `LanChatPathRegistry.recordVerifiedPeer(..., socket, generation)`。
- [x] 确认 RouteBinding admission 本身会在 Registry 锁内原子校验 Listener socket/generation；多路径聊天发送授权仍只认 path-scoped binding，peer-wide SQLite endpoint 仅作为定向 challenge 候选。
- [ ] 继续审计两步之间 Listener generation 被替换或 Registry admission 失败时，SQLite active peer-wide hint 的回滚/降级语义；当前顺序可能留下 stale compatibility hint，尚未证明它会绕过 payload 授权，也不能把它视为已解决。
- [ ] 为该竞态补 Android/静态回归：RouteBinding admission 失败不得产生可被误读为已验证的持久化状态；并发双路径认证不得由旧 ACK 覆盖新路径的兼容提示或待发送目标。
- [ ] 决定并实现安全的两阶段提交/条件回滚方案，保持 SQLite 写入不进入 UDP 接收快路径；禁止为了消除竞态而在普通单路径发送快路径增加锁竞争、磁盘 I/O 或探测。

> 当前代码审计发现的是持久化兼容提示与内存 RouteBinding 之间的竞态窗口，不是已确认的聊天 payload 授权绕过，也不是已确认的 #1035 对讲回归根因。需通过针对性测试后再修复，不应贸然撤销 path-scoped 安全边界。


## #1035 与 V2 握手差异复核（本轮继续审计）

- [x] 对照 #1035 的 `handleAuthenticatedLanPacket()` 与当前 V2：#1035 的 `peer.ack` 主要依赖 nonce、sender ID/endpoint/port 校验，随后直接写入 peer-wide SQLite 记录并更新全局 verified cache；当前 V2 增加 ACK ingress path、精确 Socket/generation 与 pending challenge egress lease 的匹配，并在 Registry 原子登记 path-scoped RouteBinding 后才更新全局兼容 cache。
- [x] 对照聊天 ACK：#1035 的普通/分片 ACK 处理没有当前 V2 这套 ingress listener epoch + peer route binding 的双重检查；V2 的多 Listener 安全约束是实质性变化，不能简单恢复旧版无路径约束逻辑。
- [x] 对照 peer.hello：V2 在异步持久化前后检查 ingress lease，并在当前路径上回复 ACK；非 challenge-only hello 会在当前路径没有匹配 RouteBinding 时发起定向 challenge。该流程需要真机日志验证实际到达与回程，静态代码不能证明无线网络已完成握手。
- [x] 明确当前 peer.ack 顺序：先 `store.upsertPeer()`，后 `recordVerifiedPeer(..., socket, generation)`，最后更新内存兼容 cache 并释放 latch。若 Registry admission 因 Listener epoch 变化失败，SQLite peer-wide hint 可能已经写入；该 hint 不能授权多 Listener payload 发送，但可能成为后续定向 challenge 候选。
- [ ] 为上述失败窗口增加可验证的持久化一致性测试：验证 Registry admission 失败后不会更新 verified cache、不会释放等待者；同时验证 stale SQLite hint 只能触发重新挑战，不能直接发送聊天 payload，也不能错误完成 discovery target。
- [ ] 设计条件化的 stale-hint 清理/过期方案，必须按 peer + endpoint + 本次验证时间/版本 compare-and-set，不能删除同一 peer 后续新路径成功写入的记录；同时检查 upsertPeer 对 Outbox target candidate 与 open discovery snapshot 的副作用，不能只回滚 lan_peers 行。
- [ ] 继续查明 #1035→V2 的实际回归点：用三机新 APK 日志逐台核对 `peer.hello RX`、`peer.ack TX`、`peer.ack` ingress、`peer route verified`、RouteBinding 数量和第一条消息 ACK；无日志/无证据时不得推断是广播、认证或 generation 哪一环。

> 当前差异复核没有发现可以安全地直接撤销的 path-scoped 安全检查；尚未证明上述 SQLite hint 竞态就是现场对讲失败根因。下一步优先补充有边界的存储一致性测试，再根据真机日志定位实际断点。


## #1035 普通 LAN 对讲回归：固定目标 endpoint fallback 差异（2026-11-28）

- [x] 重新核对 #1035 APK 对应源码快照 `ad911d29651165c991f759fb4fd5e31a9ed2e7a6`（该 commit 的提交标题虽是配对恢复，但其完整 tree 可作为 APK 源码快照；不能只看 commit diff 判断源码状态）。
- [x] 找到一个明确的发送行为差异：#1035 在准备阶段从持久化 Outbox target 的 `endpoint_ip/endpoint_port` 构建 `preparedCachedEndpoints`；发送时优先使用当前内存 verified route，否则允许向该历史 endpoint 直接发送目标 Peer ID 绑定的认证聊天包，并记录该次实际成功发送 endpoint，供同一 messageId 的 ACK 校验。
- [x] 当前 V2 已移除上述持久化 endpoint 的 payload fallback：发送目标只从当前 `LanChatPathRegistry.RouteBinding` 构建；SQLite peer-wide endpoint 只允许触发定向 challenge，不得直接授权 chat/fragment payload。若固定目标存在但当前 path-scoped binding 缺失，既不会走历史 endpoint 单播，也不会进入“无固定目标”的首次广播分支。
- [x] 该差异能解释一种重要回归形态：设备已有历史可信 endpoint、但本轮 peer discovery/RouteBinding 未建立时，#1035 仍可能单播对讲；V2 则会等待当前路径重新认证。它与 #1035“设备发现失败但 UDP 对讲双向成功”的已知结果相符，是当前最强代码差异线索，但尚不能单凭静态代码认定为现场根因。
- [ ] **P0 修复设计**：不能无条件恢复 peer-wide endpoint 直投。先为单 Listener 快路径设计有界兼容策略：必须证明 endpoint 来自未过期的历史认证记录、属于当前实际 LAN 子网/网络 scope、当前仅一个 Listener 且无跨路径歧义；每次发送仍绑定目标 Peer ID，并只接受该 messageId 成功 UDP send 后来自精确 endpoint 的认证 ACK。多 Listener 情况继续强制 path-scoped RouteBinding，不允许跨路径借用。
- [ ] 若现有 SQLite schema 缺少验证时间/租约/路径归属信息，先补充可迁移字段或独立的 path-scoped durable route record；不能把普通 `last_endpoint` 当成已认证记录，也不能仅凭私网 IP/CIDR 授权。
- [ ] 增加 Android 回归测试：①单 Listener + 未过期、当前 LAN scope 匹配的历史认证 endpoint 可按兼容策略投递；②过期/跨子网/不同 scope/多 Listener/stale generation 均拒绝 fallback；③成功发送 endpoint 才可接受同消息 ACK；④历史 endpoint 不更新 RouteBinding、不释放 peer discovery latch、不被误报为当前路径认证成功。
- [ ] 对照真机新日志确认：Outbox target 是否非空、target endpoint 是否存在且未过期、当前 RouteBinding 数量、定向 challenge 是否收到 ACK、是否因此进入 waiting_peers；重新以中兴 A2023BH API 30 + Redmi Note 8 API 35、同一 5GHz Wi-Fi 做双向短消息验收。

> 当前已定位到“#1035 有持久化 endpoint 直投 fallback、V2 删除该 fallback”的确定代码差异；尚未修改生产发送授权逻辑。下一步先补足历史 endpoint 的认证/租约/scope 证据与单 Listener 安全边界，再实现受限兼容并跑 Android 单测/CI。发现失败与聊天失败仍分别记录。


### 兼容 fallback 的数据证据边界补充

- 当前 `lan_peers` 表已有 `network_scope`、`last_verified_at`、`lease_expires_at`、`status`，但 `LanChatStore.loadTargets(messageId)` 只读取 `lan_outbox_targets.last_endpoint`，不返回 peer 验证时间、租约、scope 或 status。
- `loadKnownPeers()` 明确包含离线 peer；不能把它的 endpoint 当作新鲜认证路由。
- `upsertPeer()` 会把 peer-wide endpoint 写入所有未 ACK 的 Outbox target 的 `last_endpoint`，并把 peer 加入仍开放的 discovery snapshot；该字段因此只是 route candidate，不足以单独证明 endpoint 仍在租约内或属于当前 LAN path。
- 所以不能仅把旧版 `preparedCachedEndpoints` 逻辑复制回来。安全实现必须从 `lan_peers` 读取并校验 `status='active'`、`lease_expires_at > now`、精确 endpoint 一致、当前 network_scope 一致，并验证 endpoint 与唯一活动 Listener 的 LAN path 匹配；若任一信息缺失则只做定向 challenge，不直投。


## #1035 单 Listener 历史 endpoint 兼容 fallback：实现进展（2026-11-28）

- [x] 新增纯 Java 准入策略 `LanChatCachedRoutePolicy`：仅允许当前唯一 Listener、有效持久化认证租约、IPv4 site-local endpoint 且 endpoint 落在当前本地 LAN prefix 内；只解析 dotted-decimal IPv4 literal，不对 Outbox endpoint 做 DNS。
- [x] `lan_peers` schema 升至 DB v14，新增 `verified_path_fingerprint`、`verified_listener_generation`、`verified_listener_instance_id`。认证 ACK 持久化时记录接收该 ACK 的 path/generation 和当前 Service 实例 ID；旧数据库升级后默认空值/无效 generation，旧记录不会自动获得 fallback 权限。
- [x] 新增 `hasFreshVerifiedPeerRoute()`：必须精确匹配 peerId、IP、端口、active 状态、last_verified_at、未过期 lease、path fingerprint、listener generation 和 Service instance ID。
- [x] `observePeer()` 仍不延长认证租约；若同一 endpoint 的对端 network_scope 改变，则清空旧 lease 和 listener epoch，要求重新 challenge。
- [x] 固定收件人路由解析在当前 live RouteBinding 缺失时，才评估单 Listener fallback；必须同时确认 Runtime/Registry 都恰有一个 Listener、当前 socket/generation/lease 有效、path 与当前 primary path 一致、数据库认证记录属于当前 Service 实例，并且 endpoint 在当前 LAN subnet。
- [x] 单播/分片每个 UDP dispatch 边界重新验证 fallback 的 endpoint、租约、path/generation/Service instance 与 subnet；多 Listener 仍只接受当前 path-scoped RouteBinding。ACK 仍要求同一 messageId 实际成功发送 endpoint 证据，不因 fallback 直接放宽。
- [x] 新增 `LanChatCachedRoutePolicyTest` 覆盖同子网、跨子网、多 Listener、租约不匹配、非法地址和 IPv4 literal；新增 Robolectric `LanChatStoreCachedRouteTest` 覆盖租约到期、IP/端口变化、generation/Service instance 不匹配和 network_scope 变化。
- [x] 更新 Python 静态契约，明确受限 fallback 与旧版 peer-wide endpoint 无条件直投不同。
- [ ] **CI 尚待收口**：最新验证 run [46130981095](https://github.com/shua00/rebackmyphone3/actions/runs/46130981095) 的 Python 与 Windows Socket Binding 已通过；Android build 正在运行。后续最新提交还会触发完整 Android build。
- [ ] **真机验收仍未完成**：安装本轮最终 APK，在中兴 A2023BH API 30 + Redmi Note 8 API 35、同一 5GHz Wi-Fi 上双向发送短消息，记录 route fallback 是否命中、ACK endpoint、送达结果；随后复测多 Listener/热点矩阵。
- 结论：当前已实现受限兼容代码与测试，仍不能宣称普通 LAN 对讲回归已在真机解决；若最新 APK 仍失败，需依据双方新日志继续定位，不得把设备发现失败等同于聊天发送失败。


### 最新验证结果补记（run #1954）

- [x] 最新代码/测试提交：`2897a7b4f88b4766efebafb707fcdb5e0cdabb27`。
- [x] GitHub Actions run [#1954](https://github.com/shua00/rebackmyphone3/actions/runs/46131188787)：Python 509 项测试通过（含本轮静态契约）、Windows Socket Binding 通过、Android `testDebugUnitTest` 通过、Debug APK 构建通过、slim Release APK 构建通过。
- [x] APK artifact：`app-diagnostic-and-slim-apks`，artifact ID `15258420847`，SHA-256 `fc3d403750899e88c5624eaa427460762bc47343cbfe72565d3a18b1ddb1199a`。
- [ ] 仍需真机安装该 run 的诊断 APK，按已确认的中兴 A2023BH + Redmi Note 8 基线做双向对讲验收；CI 通过不等于真机回归已解决。
