# LAN Chat 多 Listener 运行时审计（2026-11-27）

状态：**阻塞项审计完成；多 Listener runtime 尚未实现。** 本文记录当前代码事实与下一阶段实施门槛，避免把 Registry/Selector 的组件能力误报为运行时多路径能力。

审计基线：`feature/lan-p2p-v2-only`，commit `94d502595464282b0a0d3407dc4a88dd8cab11c1`。

## 1. 当前代码事实

### 1.1 聊天数据面仍为单 Listener

`TrackingService.startRescueListener()` 当前：
- 从 `lanSocketGeneration` 捕获单一 listener generation；
- 从 `wifiLanFingerprint` 捕获单一 listener path fingerprint；
- 创建一个 `DatagramSocket`；
- 通过 `bindSocketToWifi(rescueSocket)` 绑定当前 Wi-Fi Network；
- 使用 `rescueSocket.bind(new InetSocketAddress(8888))` 通配地址绑定；
- 将唯一 socket 发布到 `rescueSocketRef`，接收循环只处理该 socket；
- 最终按捕获的 path/socket/generation 从 Registry 移除 Listener。

因此目前不能接收并区分 STA 与本地 AP 两条独立路径的聊天报文。Registry 可登记多个 Listener，并不代表 TrackingService 已创建多个 Listener。

### 1.2 现有精确绑定能力尚未接入聊天 Listener

`LanSocketBinding.bindDatagramListenerSocket(path, socket, port)` 已提供：
- Android Network 路径：先调用对应 `Network.bindSocket(socket)`；
- 本地接口候选路径：绑定 `path.localAddress`；
- 最后精确绑定指定地址与端口，并校验实际 local address/port。

但聊天 Listener 仍未使用该入口。AP 候选不是天然可用路径；精确 bind 成功也不能证明远端 peer 可达。

### 1.3 路径发现与聊天 Listener 生命周期尚未统一

当前 Wi-Fi Network callback 监听所有 TRANSPORT_WIFI 网络，但 `refreshWifiLanTransport()` 更新的是单一 `wifiLanFingerprint` 并推进单一聊天 socket generation。该逻辑不能直接表达同一设备上并存的 STA Android Network 与未映射到 ConnectivityManager 的 AP 本地接口。

`LanNetworkPath.enumerateLocalInterfaceCandidates()` 是候选枚举，不是 AP 角色判定，也不是可达性证明。不得仅凭接口名（例如 wlan0/ap0）或私网地址推断 STA/AP 角色。

### 1.4 现有 Registry 能力

`LanChatPathRegistry` 已支持：
- path fingerprint → Listener 的独立登记；
- Listener socket + generation 精确 compare-and-remove；
- peer × path 的认证 route 与 lease；
- listener replacement/removal 时清理对应 path routes；
- 捕获具体 RouteBinding 并在发送前复核。

`LanAdaptiveRouteSelector` 已支持单 Listener O(1) 首选路径查找和多 Listener 候选选择。但多 Listener 分支只有在 TrackingService 真正注册多个有效 Listener 后才具备 runtime 意义。

## 2. 不可绕过的实施要求

1. **独立生命周期对象**：每条可用 path 有独立 socket、接收循环、path fingerprint、generation、启动/停止状态与错误状态；不得复用单一 `rescueSocketRef` 代表所有路径。
2. **精确绑定**：聊天 Listener 必须使用 `LanSocketBinding.bindDatagramListenerSocket()`；禁止多 Listener 使用 wildcard bind 抢占其他路径流量。
3. **路径身份**：Android Network 与 local-interface candidate 分开建模。相同物理 Wi-Fi 下的 STA/AP 不能因 SSID、地址段或接口名称相似而合并。
4. **竞态安全**：启动、socket bind、Registry register、发布、停止和 generation handoff 必须有明确所有权。旧线程 finally 只能移除自己捕获的 path/socket/generation，不得清理新 Listener。
5. **资源边界**：单路径设备只启动一个 Listener；没有第二条已识别且可绑定的 path 时，不创建额外线程、socket、定时器或探测任务。
6. **接收路径快照**：每个入站包必须携带其接收 Listener 的 path/socket/generation；ACK、fragment ACK、配对和 peer control 校验必须沿用该 ingress snapshot。
7. **可信路由建立**：候选接口、广播发现、socket bind 成功都不能登记为 authenticated route。每个 peer × path 必须独立完成认证和双向可达验证。
8. **关闭与恢复**：路径消失只撤销对应 Listener 与 path routes；其他路径、Peer 身份、配对凭据及持久化消息不能被连带清除。
9. **锁与 I/O**：不能在全局生命周期锁内执行阻塞 receive、socket close 或网络 IPC；必须避免锁顺序反转和关闭等待死锁。

## 3. 建议实施顺序

### 阶段 A：Listener runtime owner（先做）

- 建立独立的聊天 Listener owner/manager，负责 path-keyed start/stop/replace。
- 每个实例封装 immutable path snapshot、socket、generation、线程和停止信号。
- 先把现有单 Wi-Fi Listener 迁移到该 owner，保持仍只有一个 Listener；验证生命周期行为不退化。
- 再接入第二条经确认可绑定的 AP/local-interface path。
- Registry 注册/注销必须由 owner 在明确的资源所有权边界执行。

### 阶段 B：Ingress 与 ACK 路径隔离

- 接收循环将 Listener snapshot 传入 packet handler。
- 所有 ACK/fragment ACK/peer control 使用原始 ingress path snapshot，不得在异步处理后重新猜测当前默认路径。
- 增加旧 generation 包、旧线程 finally、socket replacement 与并发 stop/start 测试。

### 阶段 C：路径认证与有限回退

- 每条路径独立完成 peer challenge/response 和 route lease 建立。
- 真实 ACK RTT、成功、发送异常和 ACK timeout 按 peer × path 采样。
- 仅在主路径失败/超时后选取最多一个备用 path 尝试；重验 binding，保持消息 ID、收件人快照和幂等语义，禁止 fan-out 和无限重试。

### 阶段 D：验收

- 普通路由器单路径：一个 Listener、无额外线程/定时器/探测、O(1) route lookup。
- STA + AP + Router 三设备：主机/热点客户端、STA 客户端及另一 Router 客户端三组任意两台分别双向发现、认证、消息最终 ACK。
- 网络切换/接口地址变化/热点关闭：仅清理受影响 path，旧 generation 不能污染新路径。
- Android 单测、Python 协议回归、CI/APK build 与真机结果分开记录。

## 4. 当前不得宣称

- 不得宣称 TrackingService 聊天数据面已经多 Listener。
- 不得宣称 STA/AP 双路径聊天已可运行。
- 不得宣称 selector fallback 已接入发送失败/ACK timeout 闭环。
- 不得宣称动态路径质量评分已完成。
- 不得以接口枚举、bind 成功或单向收包替代三设备任意两台双向真机验收。

## 5. 2026-11-27 后续批次进展

- [x] 新增 `LanChatListenerRuntime`，用 path-keyed immutable lease 统一 Registry 注册、generation 所有权和 compare-and-remove；不创建 worker/timer，也不关闭 socket。
- [x] 现有单 Wi-Fi Listener 的注册/注销已接入该 owner，保持单 Listener 的 socket/thread 资源模型。
- [x] ACK ingress 从 receive loop 显式传递 path fingerprint 与 listener generation；异步 ACK/peer.ack 继续使用该捕获快照，避免未来多 Listener 时读取全局单 Listener path 字段而串线。
- [x] 增加 runtime lease 和 ACK ingress 静态契约测试。
- [ ] 尚未把每条 path 的 socket、receive loop、thread、Network snapshot、停止/替换生命周期全部迁入 owner；因此当前仍不是多 Listener runtime。
- [x] 当前单 Wi-Fi 聊天 Listener 已接入 `bindDatagramListenerSocket()`，按选中 Android Network 的 LinkProperties 精确绑定本地 IPv4 地址和 8888 端口；无 Wi-Fi Network 时仅保留既有非聊天 UDP wildcard 兼容分支。
- [ ] AP local-interface candidate 尚未接入 TrackingService 聊天 Listener，STA/AP 双 Listener 仍未完成。
- [ ] 尚未实现 per-peer×path 真实 RTT/失败采样和发送失败后的备用路径回退。

## 6. 本批结论

本批完成运行时阻塞点审计并形成可执行实施顺序。下一批应先把现有单 Listener 安全迁移到 path-keyed owner，再逐步引入第二条 Listener；不应一次性并行修改 TrackingService 的 socket 生命周期、ACK ingress 和发送重试，以免扩大竞态面。


## 7. 2026-11-27：STA Listener 精确地址绑定补充

- [x] `TrackingService.startRescueListener()` 在 socket 未绑定前调用 `bindSocketToWifi(socket, 8888)`；若选中 Wi-Fi Network，内部构造与该 Network 对应的 `LanNetworkPath` 并调用 `LanSocketBinding.bindDatagramListenerSocket()`，不再先做 Network-only bind 后再 wildcard bind。
- [x] helper 对 Android Network 执行 `Network.bindSocket`，然后将 UDP Listener bind 到该路径的精确 IPv4 地址；Socket bind、generation 校验、Registry lease 注册和 socket 发布顺序保持不变。
- [x] 仅无可用 Wi-Fi Network 时，调用方才看到 socket 仍未绑定并进入旧 wildcard 兼容路径。选中 Network 后精确绑定失败会抛出 IOException，不会静默回退到蜂窝/default route。
- [ ] 当前仍然只有一条 STA 聊天 Listener；AP candidate listener、每路径独立接收线程和多 Listener 旧命令分发归属仍待实现。


## 8. 2026-11-27：旧 UDP 管理命令跨 Listener 去重前置

- [x] 新增 `LanUdpCommandDeduplicator`，将旧 UDP 管理命令的重复抑制从 receive-loop 局部 HashMap 提升为 TrackingService 实例级共享对象；未来 STA/AP 所有 Listener 必须复用同一实例。
- [x] 保留原有 3 秒重复窗口、30 秒保留清理语义及最多 50 条记录上限；采用单调 elapsedRealtime 时间，避免系统墙钟回拨影响窗口判断。
- [x] 新增组件测试：跨 ingress 共享实例抑制重复广播、窗口到期重新接纳、无效 key/时钟拒绝、记录数有界。
- [ ] 多 Listener 旧命令处理归属仍需在真实多 Listener 接入时验证；此提交只建立共享去重基础，不代表第二条接收循环已经启动。


## 9. 2026-11-27：跨 Listener 旧命令去重回归加固

- [x] TrackingService 只持有一个 service-scoped `LanUdpCommandDeduplicator`；接收循环不再各自创建局部去重 HashMap。
- [x] Python 静态架构测试锁定上述集成边界，防止后续复制接收循环时重新引入 per-loop 去重表。
- [x] Java 并发测试使用 12 个并发 ingress 模拟同一广播同时到达，必须只有一个调用被接纳。
- [x] GitHub Actions 验证结果：439 项 Python 测试通过（1 项既有 skip）；Windows Socket Binding、Android unit tests、Debug 与 Slim Release 构建成功；APK artifact 已上传。
- [ ] 该去重仍只覆盖原有 alarm/low-battery 两类命令的既有语义；其他 legacy 命令仍须逐项审查是否具备原生幂等/全局去重需求，不能盲目扩大去重范围。
- [ ] 第二条 Listener 尚未启动，真实双路径广播重复执行仍需在多 Listener 集成后做设备级验证。

## 10. 到首次真机验收的剩余批次估算（计划，不是完成承诺）

按当前代码缺口，预计还需 **4 个实施批次**进入第一轮受控 Android 真机验收；若 AP 接口识别、OEM 热点行为或端口绑定暴露新问题，预留第 5 个修复批次。这里的“进入真机验收”指可以开始执行测试矩阵，不代表三设备拓扑已通过。

1. **批次 A：单 Listener 生命周期对象化**——把 socket/thread/path/generation/stop handoff 纳入同一 owner；先迁移现有 STA Listener，保持单路径资源数不变，并通过竞态测试。
2. **批次 B：AP/local-interface 路径识别与第二 Listener**——明确区分 Android Network 与 AP local-interface，基于实际地址精确绑定；无已确认第二路径时不创建任何额外资源。
3. **批次 C：多 Listener ingress/认证/业务分发闭环**——验证 ACK/fragment ACK/peer control 使用 ingress snapshot；共享 legacy 命令去重；每个 peer×path 独立认证，不因 bind 或发现直接变可信。
4. **批次 D：受控回退与真机测试准备**——完成一次性备用路径尝试、真实 ACK RTT/失败采样的最小闭环、诊断/验收场景与 APK；普通单路径性能回归通过后开始双机/三机测试。
5. **预留批次 E（条件触发）**——修复真机暴露的 ROM、热点客户端隔离、接口权限、网络切换或端口行为差异；不把它预先计入正常路径。

真机验收按风险递进：普通路由器双 Android → 热点主机与一个客户端 → 热点主机、STA 客户端、路由器客户端三设备三组两两双向 → Android/Python 互通与网络切换。每一步独立记录，不以 CI 代替。
