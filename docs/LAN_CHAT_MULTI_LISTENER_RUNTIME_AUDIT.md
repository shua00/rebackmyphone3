# LAN Chat 多 Listener 运行时审计（2026-11-27）

状态：**批次 B 的 secondary Listener 运行时代码已接入并通过 CI；批次 C 正在进行 ingress/ACK/业务分发闭环审计。** CI 不等于 OEM 热点可达或真机拓扑验收。

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


## 2026-11-27 批次 A 进展：单 Listener 生命周期实例化

- 新增 `LanChatListenerInstance`：以不可变 path fingerprint + generation 标识一个接收实例，明确登记其唯一 socket/thread；不自行创建线程、socket、timer 或探测任务。
- `TrackingService.startRescueListener()` 先创建实例，再将实际创建的 DatagramSocket 和 listener Thread 附着到实例；socket 发布前复核 active instance、socket identity 与 generation。
- 网络路径替换时先对旧实例执行 stop-request，再在锁外关闭 socket；旧 receive loop 的 finally 仍需同时匹配当前 thread、instance identity 和 socket identity，不能清理替换后的实例。
- 单 Listener 仍只有原有一个接收线程和一个 socket；本批没有启动第二 Listener，也没有改变 UDP 8888 绑定策略、ACK ingress snapshot、消息/文件传输协议。
- 新增 `LanChatListenerInstanceTest`，覆盖唯一资源附着、停止后禁止迟到资源附着、旧 generation 不能冒充替换实例；更新 Python 静态契约测试。
- **范围说明**：这是把现有单 Listener 的身份/资源所有权显式化，不等于完成完整 manager 化；socket bind/receive/close 和 multicast lock 仍由 TrackingService 控制，后续批次再逐步把状态迁入 owner，并审查 onDestroy/网络切换/启动失败的所有退出路径。


### 批次 A CI 结果（代码基线 `6b7d7e0207bf96fddf868612d5338d529da1e09f`）

- GitHub Actions run [45980782340](https://github.com/shua00/rebackmyphone3/actions/runs/45980782340)：全部 jobs success。
- Python：440 tests，OK（skipped=1）。
- Windows socket binding：success。
- Android：`testDebugUnitTest`、`assembleDebug` 成功；Debug build 1m21s。
- Slim Release：`assembleRelease -PincludeDiagnostics=false` 成功，17s。
- APK artifact：`app-diagnostic-and-slim-apks`，artifact ID `15189696370`。
- 以上是代码/构建验证，不是 Android 真机验收；本批仍未启动第二 Listener。


### 批次 A 补充：实例成为 Socket/Worker 生命周期资源来源

- `TrackingService` 的网络替换与服务销毁优先从当前 `LanChatListenerInstance` 取回 Socket/Thread，再执行 stop/close/interrupt；旧字段保留为兼容引用，不再作为唯一资源所有权来源。
- close/interrupt 仍在 `rescueLock` 外执行；实例 attach 只接受未关闭 Socket、NEW 状态 Thread，拒绝重复或迟到资源。
- 最新验证 commit：`96fb9c03eacd67f51079272b54923b0ceef32760`；Actions run [45981692678](https://github.com/shua00/rebackmyphone3/actions/runs/45981692678) 全部成功。
- Python：440 tests，OK（skipped=1）；Windows socket binding success；Android `testDebugUnitTest` + `assembleDebug` success；Slim Release `assembleRelease -PincludeDiagnostics=false` success。
- APK artifact：`app-diagnostic-and-slim-apks`，ID `15189847113`。


## 批次 B（进行中）：AP/local-interface 路径计划与识别边界

- 新增 `LanChatListenerPathPlan`：从已验证的 `LanTransferPathSet` 生成确定性 primary/secondary 路径计划；优先选择 Android Network 作为 primary，单路径时 secondary 为空。
- `TrackingService.refreshWifiLanTransport()` 仅在实际 Wi-Fi LAN fingerprint 变化后刷新聊天路径计划；调用既有 `currentLanTransferPathSet()` 规则，不在每包、发送路径或稳定单路径运行期做接口枚举。
- 计划组件只保存路径元数据，不创建 Socket/Thread/Timer/Probe；本轮尚未启动第二个聊天 Listener。
- **AP 识别边界**：`LOCAL_INTERFACE_CANDIDATE` 仅代表未被 ConnectivityManager Wi-Fi Network 表示、且通过 IPv4/subnet/broadcast 与 cellular/VPN 排除检查的本地接口候选。接口名不能证明它是 AP；多候选、快照不完整时 fail closed。只有精确地址 bind 成功才算该候选可作为监听路径。
- 新增 `LanChatListenerPathPlanTest`，覆盖单路径不产生 secondary、STA 优先、AP 候选作为 secondary、无 STA 时单候选 primary 及非法空输入。
- 当前仍未完成：热点状态独立事件触发、候选接口真实角色确认、第二聊天 Listener 的 per-path socket/thread/stop 生命周期、跨路径 ACK ingress 和 peer×path 认证接入。不得将路径计划测试等同于多 Listener runtime 完成。


### 批次 B 补充：热点事件感知与初始路径计划

- [x] 新增动态热点/Tethering 广播观察：监听平台 Wi-Fi AP 状态变化与 tether 状态变化 action；Android 13+ 使用 `Context.RECEIVER_NOT_EXPORTED`，旧版本使用兼容注册重载。
- [x] 广播回调只提交一个有界 maintenance refresh，重建候选路径计划；不解析 OEM 私有 extras、不依赖接口名、不直接把候选标记为 AP，也不创建 Listener/socket/thread/timer。
- [x] 广播不可用/注册失败时 fail soft，不影响服务启动、不启动周期扫描；ConnectivityManager Wi-Fi callback 仍是 represented Network 的权威来源。
- [x] 服务启动时显式生成一次初始路径计划。此前 fingerprint 在 NetworkCallback 注册时即初始化，等待 fingerprint 后续变化会使首次稳定网络的 path plan 长期为空。
- [ ] 广播事件只解决拓扑变化触发，不证明本地候选可绑定或 AP peer 可达；第二 Listener 仍未接入。
- [ ] 当前仍需完成接收循环共享分发抽取、per-path listener map/manager、第二 Listener 精确绑定与并发竞态测试。

CI：前一组 path-plan commits 的 run `45982642484` 已全部成功（Python、Windows socket binding、Android unit/debug/slim build，artifact `15189534048`）。本次热点 receiver 两个 Android 源码提交已触发新的 CI，结果待核验。


### 批次 B 本轮验证补充（2026-11-27）

- [x] Receiver 注册与 Service teardown 的窄竞态已关闭：若 onDestroy 在 receiver 发布前观察到空字段，注册线程发布后会再次检查 `serviceDestroyed` 并立即注销。
- [x] 新增 `tests/test_lan_hotspot_event_contract.py`，锁定启动初始 plan、AP/Tethering action、Android 13 receiver flag、事件回调不启动 Listener/Socket/Thread、不引入周期轮询，以及销毁时注销。
- [x] Android 源码 commit `8d2bb949ca08d8bfb408573ea35dfe55193c40a4`：Actions run [45984239869](https://github.com/shua00/rebackmyphone3/actions/runs/45984239869) 全部成功；Python、Windows socket binding、Android unit/debug/slim build 均通过，artifact `15191455609`。
- [x] Android 源码 commit `b58578e739125a85e90205978c618457d95e8bac`：Actions run [45984421449](https://github.com/shua00/rebackmyphone3/actions/runs/45984421449) 全部成功；Python、Windows socket binding、Android unit/debug/slim build 均通过，artifact `15190816457`。
- [x] 热点事件静态契约测试 commit `1a6a42a24b21f51034437310257cb81f190116b7`：Actions run [45984525071](https://github.com/shua00/rebackmyphone3/actions/runs/45984525071) Python 与 Windows jobs 成功；Android build 按 path filter 正确跳过（仅 tests 变更）。
- [ ] 上述 CI 不代表 OEM 实际广播覆盖率，也不代表 AP Listener 可绑定或 STA/AP 互通；真机确认仍待进行。


### 批次 B：path-keyed receive-loop owner（本轮）

- [x] `LanChatListenerRuntime` 新增按 path fingerprint 管理 `LanChatListenerInstance` 的 install/get/identity-remove/snapshot API。该 API 只管理 owner token，不在锁内创建线程、关闭 Socket 或 join。
- [x] 现有 primary `startRescueListener()` 已将其 `LanChatListenerInstance` 纳入 owner map，并在 receive-loop finally、bind/start 失败和 teardown-before-start 分支按实例身份清理。
- [x] 增加双路径 owner 独立性、同路径旧实例不能移除替换实例、snapshot 不可变等 Java 单元测试。
- [ ] 该改动尚未创建 AP secondary worker；旧 primary 接收循环仍负责 legacy UDP commands，后续 secondary 必须只分发 authenticated LAN P2P frames，避免两条路径重复执行 legacy 命令。
- [ ] 继续完成 secondary candidate 精确绑定、独立 receive loop、secondary HELLO discovery、generation/path-plan reconciliation、onDestroy 批量停止及竞态测试。


CI 验证（2026-11-27）：

- [x] commit `05c001486f08dceef53ed86c10badb945a226934` / run [45986410871](https://github.com/shua00/rebackmyphone3/actions/runs/45986410871)：Python 443 tests（skipped=1）、Windows socket binding、Android unit/debug/slim build 全通过；artifact `15191124437`。
- [x] commit `9a9b40749d95e17a53a52f8ff1304e3367b23909` / run [45986436210](https://github.com/shua00/rebackmyphone3/actions/runs/45986436210)：上述 jobs 全通过；artifact `15191777902`。
- [x] commit `ac2c15d977fc12caa9cc7769e19a961213b49320` / run [45986488456](https://github.com/shua00/rebackmyphone3/actions/runs/45986488456)：上述 jobs 全通过；artifact `15192185065`。
- [ ] 以上是 owner-map 前置阶段的构建验证，不等于 secondary listener 已实现或完成真机验收。


### 批次 B：AP/local-interface secondary ingress 接入（代码阶段）

- [x] 路径计划 reconciliation 使用当前选中的 active Wi-Fi Network 作为 primary，其余所有已验证路径（额外 Android Wi-Fi Network 或 `LOCAL_INTERFACE_CANDIDATE`）作为 secondary；普通单 Wi-Fi 路径不会创建第二 socket/thread。
- [x] secondary worker 先登记 path-keyed instance，再创建 Socket；必须通过 `LanSocketBinding.bindDatagramListenerSocket(path, socket, 8888)` 精确绑定所选路径地址，绑定失败不回退 wildcard、不影响 primary。
- [x] secondary 使用独立 socket/thread/generation/lease，接收循环只调用 authenticated `handleLanRealtimePacket`，不会重复执行 primary 的 legacy `FIND_ME_*` 管理命令。
- [x] secondary 启动时只在候选接口具备 broadcast address 时发一次带 nonce 的 peer.hello；挑战超时使用既有有界调度器。
- [x] path plan 变化、generation 变化、显式 Listener restart 和 Service teardown 均按实例身份停止 secondary；先标记 stop、撤销 lease，再在锁外关闭 socket/interrupt worker。
- [ ] 新增的 `tests/test_lan_secondary_listener_contract.py` 与 Android build 正在 CI 验证；CI 通过后才确认代码阶段完成。
- [x] 若设备仅有未被 ConnectivityManager 表示的 AP/local LAN path，primary 也按 path plan 精确绑定该本地地址；广播和 network_scope 使用同一 primary path identity。
- [ ] 仍需设备验证：OEM AP 地址是否可绑定、AP/STA 广播和 ACK 是否双向到达、热点 NAT/客户端隔离对任意两台通信的影响，以及三设备三组双向验收。


CI 验证补充（secondary listener 代码阶段，2026-11-27）：

- [x] commit `82c8b012b118a539ffb1bbeb01cc0a964876647c` / run [45988859572](https://github.com/shua00/rebackmyphone3/actions/runs/45988859572)：Python 447 tests（skipped=1）、Windows socket binding、Android unit/debug/slim build 全通过；artifact `15193211135`。该构建包含 AP-only primary 精确绑定及所有已验证 secondary path 接入。
- [x] commit `c1a28a0d21fb55bbb3fc3d8553fb9138388fe3b6` / run [45989057403](https://github.com/shua00/rebackmyphone3/actions/runs/45989057403)：Python 447 tests（skipped=1）、Windows socket binding、Android unit/debug/slim build 全通过；artifact `15192233835`。该构建包含 AP-only primary path 地址变化/消失时的 generation-safe rebind。
- [x] commit `79c2d2c77b8f4546b281aa89df3cc932984b68ac` / run [45989092125](https://github.com/shua00/rebackmyphone3/actions/runs/45989092125)：Python 448 tests（skipped=1）、Windows socket binding 通过；仅测试文件变更，Android build 按路径过滤跳过。
- [ ] CI 只验证代码/构建契约。尚未证明真实设备的热点广播覆盖、不同 OEM 的精确 bind、跨 STA/AP ACK 路由、热点 NAT/客户端隔离或三设备任意两台双向通信。


## 2026-11-27 批次 B 收口与批次 C 启动

### 批次 B 当前代码事实（取代本文早期“尚未实现第二 Listener”的历史状态）

- [x] TrackingService 已按 path plan reconciliation 启动 secondary ingress；STA primary 与 secondary 各自持有独立 socket、worker、path fingerprint、generation、runtime lease。
- [x] secondary 通过 LanSocketBinding.bindDatagramListenerSocket(path, socket, 8888) 精确地址绑定；绑定失败不 wildcard fallback，不关闭 primary。
- [x] secondary receive loop 只分发 authenticated LAN P2P 帧；legacy FIND_ME_* 命令仍只由 primary 处理，避免双路径广播重复执行。
- [x] ACK/fragment ACK 的异步处理参数保留接收时的 path fingerprint、socket 和 generation；ACK 接收路径先校验该三元组仍对应 Registry listener，再按 peer×path 查找认证路由。
- [x] 多 Listener 时禁用无 path identity 的 SQLite peer-wide endpoint fallback；该兼容路径只在恰好一个 listener 时可用。
- [x] 每条路径单独停止和 compare-and-remove；旧 worker 的 finally 不能移除同路径替代实例/lease。
- [ ] 尚未完成：真实发送失败/ACK timeout 后接入 selector fallback；路径质量采样；OEM/AP 广播、单播与回程可达真机验证。

### 批次 B CI

- [x] 82c8b012b118a539ffb1bbeb01cc0a964876647c / run 45988859572：Android unit/debug/slim release、Python、Windows socket binding 均成功。
- [x] c1a28a0d21fb55bbb3fc3d8553fb9138388fe3b6 / run 45989057403：Android unit/debug/slim release、Python、Windows socket binding 均成功。
- [x] 79c2d2c77b8f4546b281aa89df3cc932984b68ac / run 45989092125：Python 448 tests（1 skip）、Windows socket binding 成功；测试文件变更，Android 按 path filter 跳过。

### 批次 C：ingress/ACK/业务分发闭环（启动）

- [x] 审计 primary/secondary 接收循环入口：两者都进入同一 handleLanRealtimePacket / handleAuthenticatedLanPacket，secondary 不执行 legacy 管理命令。
- [x] 审计消息 ACK 回发：chat.message 与 chat.fragment 的 ACK 使用原 ingress socket 和原始来源 endpoint，不读取全局 primary socket。
- [x] 审计 ACK 接收：peer.ack、chat.ack、chat.fragment.ack 携带 ingress path/socket/generation；chat.ack 和 fragment ACK 的最终账本更新要求当前 ingress lease 与 peer×path route 匹配；多 Listener 禁止 peer-wide legacy endpoint fallback。
- [x] 新增 tests/test_lan_chat_ingress_ownership_contract.py，静态锁定上述 path/socket/generation 归属与 ACK 异步参数传递。
- [ ] 待该测试 CI 通过后，将本次 source audit contract 标为通过；随后补充 peer.ack route admission、同一广播跨 STA/AP 的去重与双 Listener 并发处理测试。
- [ ] 待审计 peer.hello 异步持久化与回包的 stale ingress 行为、消息重复到达两路径时的持久化/ACK幂等和 generation 切换边界。
- [ ] 本批不接入路径回退或 RTT 评分；那些属于下一批的受控发送闭环，不能与 ingress 正确性混在一起。

### 不得误报

- 代码和 CI 已有 secondary Listener，不等于 AP 接口一定可绑定或热点网络允许双向客户端通信。
- 当前路径质量尚无可靠的 per-peer×path RTT/失败事实；selector 只能使用认证时间与显式 preferred path。
- PR #1 仍 Open，未合并 main；真机验收未开始。


## 2026-11-27 批次 C：peer.ack 认证登记 generation 原子性

- [x] `LanChatPathRegistry.recordVerifiedPeer(...)` 增加精确 ingress overload，要求调用方传入接收 ACK 的 DatagramSocket 与 generation。
- [x] 路由登记在 Registry 同一同步临界区内校验当前 Listener 的 socket identity、generation，并写入对应 PeerRoute；Listener 替换与 route admission 不再存在“先检查、后登记”的跨锁竞态。
- [x] `TrackingService` 的 peer.ack 处理改用精确 ingress overload；若 socket/generation 已被替换，拒绝 route promotion，并且不更新 peer-wide verified cache、不释放 discovery latch。
- [x] 新增 Android 单测：旧 socket/generation 在 Listener replacement 后不能把旧 endpoint 登记到新 generation；新 Listener 的正确 generation 仍可独立建立路由。
- [x] 新增 Python 静态契约：锁定 peer.ack 使用精确 ingress epoch，且 route admission 失败必须先于全局 peer cache promotion。
- [ ] 本轮新提交尚未取得 Android Gradle / Python / Windows CI 结果；必须以新 head 的完整 Actions 结果作为构建验收，不得引用前一轮成功 run。
- [ ] 同一 peer 在多路径同时认证时，Registry 保留 peer × path 的独立路由；peer-wide SQLite/cache 仍只是兼容性提示，不得在多 Listener 下作为发送授权或 ACK 归属依据。后续继续审查其过期、发现抑制与路径降级语义。


## 2026-11-27 批次 C：peer challenge nonce 的出站路径归属

- [x] 为每个待确认 challenge nonce 记录实际发出它的 Listener path fingerprint、DatagramSocket 和 generation。
- [x] secondary path HELLO、primary discovery HELLO 与定向 challenge 均通过当前 runtime lease 注册 nonce 的出站归属；无法匹配当前 lease 时不发送/不保留可接受的挑战。
- [x] 收到 peer.ack 时，先校验 nonce 对应的出站 lease 与本次 ACK 的 ingress path/socket/generation 完全一致，再允许进入异步持久化。
- [x] 定向 nonce 被消费、挑战超时或网络路径失效时，同时清理出站归属；广播发现 nonce 仍可在其短窗口内收集同一路径的多个 peer。
- [x] 新增 Python 静态契约覆盖 nonce 出站绑定、ACK ingress 匹配与清理边界。
- [ ] 尚未取得本轮新 head 的 CI 结果；仍需 Android unit tests、Debug/Slim build、Python 全量测试和 Windows binding 验证。


## 2026-11-27 批次 C：peer-wide cache 与 path-scoped route 的职责隔离

- [x] `verifiedChatPeers` 仅保留兼容性成员/endpoint 提示，不再作为多路径 discovery 或 directed challenge 的存活路由证明。
- [x] 新增 `hasFreshLanChatPeerRoute`，通过 `LanAdaptiveRouteSelector` 和 Registry 当前 binding 判断任一 Listener 是否仍有新鲜认证路由；单 Listener 仍走 Registry O(1) 查询，不启动扫描、探测或额外线程。
- [x] `peer.hello` 的重复挑战抑制改为按实际 ingress path/socket/generation/endpoint 查找同路径认证路由；pathless legacy 仅允许在不超过一个 Listener 时使用旧 cache 提示。
- [x] 待发送目标的 directed challenge 与 discoveryNeeded 均基于 path-scoped binding 判断，避免 STA 路由失效后被 peer-wide cache 错误抑制 AP/剩余路径重新发现。
- [x] Python 静态契约覆盖 peer-wide cache 不得抑制 path-scoped discovery。
- [ ] 本轮最终 CI 仍待当前 head 的 Python、Windows、Android Gradle / Debug APK / Slim Release 全部完成；以最新 Actions run 为准。
