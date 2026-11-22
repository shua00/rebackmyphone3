# LAN P2P 完整验收矩阵（v2-only）

状态：**正式验收基线；所有条目均不得因后续实现而删减或降级**  
适用分支：`feature/lan-p2p-v2-only`  
关联设计：[`LAN_P2P_RELIABLE_DELIVERY_DESIGN.md`](./LAN_P2P_RELIABLE_DELIVERY_DESIGN.md)  
版本日期：2026-11-22

> 本矩阵固定项目最初确定的 10 项验收标准，并叠加开发过程中补充的全部约束。两部分是“同时满足”的关系，不是二选一。任何一项未通过、未验证或缺少证据，都不得宣布整个通信项目验收完成。

## 0. 验收判定规则

每个条目分别记录四种证据，不得互相替代：

- **代码**：实现位置、协议/状态机/数据结构审查结果。
- **自动化**：单元、集成、故障注入或回归测试结果。
- **CI**：对应提交的 Python 检查、Android 编译和工作流结果。
- **实机**：真实 Android 设备、真实网络和真实服务启停下的结果。

状态只允许使用：

- `未开始`：尚无有效检查。
- `实现中`：代码正在开发。
- `代码已核查`：完成静态审查，但没有足够自动化或实机证据。
- `自动化通过`：对应自动化用例通过。
- `CI通过`：对应提交的工作流通过。
- `实机通过`：按步骤在真实设备完成并留有记录。
- `阻塞/失败`：发现问题或前置条件不成立。

**总体验收通过条件**：A01–A10、S01–S18、模式 M-A/M-B/M-C 及回归 G01–G06 全部达到各自要求；所有要求实机验证的条目必须为“实机通过”。CI 通过、代码存在、模拟测试通过都不能代替实机验收。

## 1. 最初确定的 10 项核心验收标准（A01–A10）

| ID | 核心验收标准 | 必须验证的行为 | 通过证据 |
|---|---|---|---|
| A01 | APK 不依赖服务端直连 | 三个 Python 服务全部关闭、无云端可用时，APK 仍能独立完成局域网发现和设备间消息通信；核心 LAN 路径不依赖服务端代发 | 代码/自动化/实机 |
| A02 | 局域网自动发现与稳定身份 | 两台及以上 APK 自动发现；使用持久化 `saved_device_id`/稳定 peer ID；IP 改变、进程重启后身份不变；过期 IP 不继续作为有效路由 | 代码/自动化/实机 |
| A03 | v2-only 统一协议 | 所有参与端使用协调一致的 v2 协议、消息类型、稳定发送者/目标身份、message_id、时间戳、认证和去重；不再双发 v1；不得出现新旧信封混合部署 | 代码/互通测试/CI |
| A04 | 双向可达验证 | A→B、B→A 均须完成真实消息持久化接收及正确 ACK；仅发现、仅 UDP send 成功、仅收到 hello 均不算可达 | 自动化/实机 |
| A05 | 一对一与多设备通信 | 两台设备一对一；三台及以上设备互相发现并按固定目标集合进行群体投递；每台设备状态独立 | 自动化/实机 |
| A06 | 可靠消息投递 | 消息先持久化；逐设备 ACK；有界重试；相同 message_id 重发；重复包只显示一次但重新 ACK；部分 ACK 只重试未确认设备；失败明确可见 | 代码/自动化/故障注入/实机 |
| A07 | 网络切换、断网与恢复 | Wi-Fi 断开/切换、IP 变化、短时离线后旧 endpoint 失效，重新发现；待发送消息及逐设备状态恢复，不丢失、不误投、不重复显示 | 自动化/实机 |
| A08 | Android 后台与省电适配 | 处理休眠、多播接收限制、必要时使用 MulticastLock、网络回调、退避和租约；不得高频永久扫描；AP 隔离等不支持场景需明确诊断 | 代码/自动化/实机 |
| A09 | 无服务器时本地记录 | 无 Python 服务/云端时，发送箱、收件箱、历史、去重 ID、未送达状态保存在设备本地；进程重启后仍可恢复 | 代码/自动化/实机 |
| A10 | 三个 Python 服务全部关闭仍可对讲 | `rescue2.py`、`chat.py`、`server2.py` 同时停止；同 LAN 两台或多台 APK 仍可双向聊天/对讲 | 实机（硬性） |

## 2. 开发过程中补充的全部强制要求（S01–S18）

以下条目全部叠加在 A01–A10 之上。

| ID | 补充要求 | 不可妥协的验收点 | 证据要求 |
|---|---|---|---|
| S01 | 原有功能完整保留 | 只增强可靠性，不删除、弱化或擅自改变 rescue2.py、chat.py、server2.py、Android 原有找回/定位/设备管理/网页管理/控制/聊天等功能 | 变更审查+回归 |
| S02 | 悬浮留言最高优先级 | 原远程留言/关怀提醒的接收、悬浮显示、关闭、已读、历史和服务端队列行为保持原样；不得因对讲改造退化 | 回归+实机 |
| S03 | 留言与对讲严格隔离 | 留言使用 `FIND_ME_FLOAT_MSG`/`custom_msg` 与 `showFloatingMessage(content)`；LAN 对讲使用 v2 JSON `chat.message`/`chat.ack`，Android 通过 `persistIncomingLanChat()` 持久化 Inbox/历史后通知对讲 UI；跨网络网页/HTTP 对讲继续使用独立 `chat_msg` 动作。旧字符串式 `FIND_ME_CHAT`/`FIND_ME_CHAT_ACK`/`FIND_ME_PEER_*` 不属于 v2-only 接收协议。两类消息的类型、队列、历史、去重命名空间、清理和 UI 入口不得混用 | 代码+自动化+实机 |
| S04 | Android 聊天窗口行为不变 | 打开、关闭、返回、隐藏、刷新及历史行为保持既有体验；不得修改 `ChatActivity.java` | diff 审查+回归 |
| S05 | LAN 直连优先、HTTP 兜底准确 | 同 LAN 只有在握手/端点验证成功后才标记 UDP 可达；不能仅凭私有 IP 或同网段推断。跨 LAN 使用现有 HTTP/云端路径；网络切换时使旧 UDP endpoint 失效 | 代码+互通/实机 |
| S06 | 稳定身份与动态路由分离 | peer_id/service_id 持久稳定；IP/端口只是临时 endpoint；network_scope 仅作辅助提示，不作身份或认证依据；同主机不同逻辑服务保持不同 peer | 自动化+重启测试 |
| S07 | 持久化消息账本 | Outbox、Inbox、逐设备 targets、去重记录、ACK 状态通过事务持久化；服务/应用重启后可恢复；增量 schema 迁移不丢旧数据 | 迁移/故障注入 |
| S08 | 固定收件人集合 | 有已知成员时创建消息即固定所有目标（包括暂时离线成员）；无已知成员时只在有界首次发现窗口纳入响应设备；窗口结束即冻结；后来加入者不无限加入旧消息 | 自动化+边界测试 |
| S09 | 逐设备 ACK 语义 | ACK 必须核对 message_id、目标 peer、receiver scope、认证信息及当前登记来源 endpoint；只确认对应设备；ACK 表示收件箱已持久化，不表示已读 | 代码+伪造/错配测试 |
| S10 | 空目标与部分送达 | 空目标不得判为 delivered；全部固定目标 ACK 后才能整体 delivered；部分 ACK 只重试未 ACK 目标；超时/过期必须明确显示，不能静默成功或静默删除 | 自动化+故障注入 |
| S11 | ACK 丢失与重复包 | 接收端先事务提交再 ACK；ACK 丢失时相同 message_id 重发；重复包重新 ACK，但不重复写历史/触发 UI | 自动化+实机 |
| S12 | 数据库失败安全 | 收件箱写入/事务失败时不得发送 stored ACK；发送箱写入失败时不得假装已发送；错误必须可诊断，原待发送数据不能被覆盖 | 故障注入 |
| S13 | 队列容量与过期策略 | Android 原有 50 条队列约束不得被静默扩大或破坏；队列满明确拒绝新消息、不覆盖旧待发送项；过期保留可诊断状态 | 自动化 |
| S14 | Python 三服务独立职责 | server2、rescue2、chat.py 保留各自端口、服务身份、过滤规则、独立/代理运行方式、网页及管理职责；共享代码不能造成作用域串线 | 代码+回归+实机 |
| S15 | 网页/云端消息投递状态 | 网页提交成功只代表服务端接收；必须与目标设备持久化 ACK 分开显示/记录；跨网络投递沿用稳定 message_id 和逐目标状态 | 自动化+实机 |
| S16 | 设备管理删除安全 | 在线、离线、异常设备均可删除；二次确认；明确“删除服务端记录”与“清除设备端数据/停止设备运行”的区别；默认只删除服务端记录和关联管理数据，不远程擦除或停用 | 回归+实机 |
| S17 | 轻量性与用户控制 | 不引入不必要第三方依赖/权限/常驻线程/高频任务；不增加隐蔽追踪或绕过用户停止控制的能力；APK 约 140 KB 为历史体积参考，任何增长须说明原因，不以体积压倒可靠性 | 依赖/权限/diff 审查 |
| S18 | 性能目标不牺牲可靠性 | LAN 快路径目标约 0.8 秒是性能目标，不是送达承诺；不得为追求时延缩短 ACK/重试窗口或牺牲持久化；记录真实延迟分布和未确认目标 | 性能测试+实机 |

## 3. 三种运行模式（M-A–M-C）

| ID | 模式 | 固定场景 | 必须通过的行为 |
|---|---|---|---|
| M-A | APK 自主局域网模式 | 三个 Python 服务全部关闭；同一 LAN 有两台或多台 APK | 自动发现、双向一对一/多设备对讲、本地历史、ACK/重试、断网恢复；不依赖云端 |
| M-B | 局域网临时协作模式 | 同 LAN 下按需启动 chat.py 或 server2.py，其他服务可关闭 | APK 直连仍可工作；网页/服务端协作功能可用；服务启停不导致身份、历史或消息重复；不得要求所有服务必须同时运行 |
| M-C | Armbian 服务端模式 | Armbian 上运行 server2.py/rescue2.py，并按既有部署使用 chat.py | 跨网络 HTTP/服务端消息、网页留言、设备管理、陪护/定位、运动数据、命令反馈、诊断及既有功能保持可用；服务端接收与设备 ACK 分开统计 |

## 4. 必须覆盖的故障与回归用例（F01–F12 / G01–G06）

| ID | 场景 | 预期结果 |
|---|---|---|
| F01 | 发送方在发送箱提交后、发包前退出 | 重启后恢复并发送原 message_id |
| F02 | 接收方收包后、数据库提交前退出 | 不产生 stored ACK；发送方可重试 |
| F03 | 接收方提交成功、ACK 发出前退出 | 重发后命中去重记录并重新 ACK，不重复显示 |
| F04 | ACK 在网络中丢失 | 只重发未确认目标；最终状态正确 |
| F05 | ACK 来自非目标 peer/错误 IP/错误端口/错误 scope | 拒绝 ACK，不改变目标状态 |
| F06 | 首次发现窗口内多设备响应，窗口结束后又有设备加入 | 只把窗口内符合条件的设备纳入原消息目标；窗口关闭后集合冻结 |
| F07 | 首次发现无任何设备响应 | 保留 waiting/discovery 状态，绝不标记 delivered |
| F08 | 多服务同主机、不同端口 | service_id/peer_type/scope 正确隔离，不串消息 |
| F09 | Wi-Fi 切换、IP 变化、断网再恢复 | 旧 endpoint 失效，重新发现并恢复未确认消息 |
| F10 | SQLite 磁盘/事务写入失败 | 不 ACK、不误报成功、不覆盖待发消息 |
| F11 | 队列满、消息过期 | 明确失败/过期状态，不静默丢弃 |
| F12 | HTTP 已接收但目标设备离线 | 显示服务端已接收、设备尚未 ACK；上线后恢复投递 |
| G01 | 留言悬浮回归 | 留言显示/关闭/已读/历史/清理与改造前一致 |
| G02 | 对讲回归 | 对讲历史、通知、窗口打开关闭行为不变；留言不进入对讲历史 |
| G03 | 找回/定位回归 | 原有找回和定位路径、指令、上报不退化 |
| G04 | 设备管理回归 | 在线/离线/异常设备管理与安全删除规则符合 S16 |
| G05 | Python 服务回归 | 三服务独立/代理模式、端口、网页和原管理功能保持 |
| G06 | 发布回归 | v2-only 分支构建通过；不得合并 main；APK/服务版本与协议版本匹配 |

## 5. 实施状态记录（初始冻结）

冻结时依据现有设计文档和已知 CI 信息建立基线。**本表不是通过声明**；未附带可核验测试证据的项目一律保持未验收。

| 范围 | 冻结时状态 | 说明 |
|---|---|---|
| A01–A10 | 未完成实机验收 | 不能由构建结果推断通过 |
| S01–S18 | 未完成整体验收 | 需逐项审查和回归 |
| M-A–M-C | 未完成实机验收 | 三种运行模式分别验收 |
| F01–F12 | 未完成完整故障注入 | 需补齐自动化和实机故障场景 |
| G01–G06 | 未完成完整回归 | 尤其留言悬浮、找回定位、设备管理 |
| CI | 按具体 commit/run 单独记录 | 仅证明该提交的自动检查/构建结果 |

## 6. 每次验收记录模板

每条记录必须包含：

- 条目 ID：
- 测试日期与测试人：
- Git commit SHA / APK 版本 / Python 服务版本：
- 设备数量、Android 版本、网络拓扑：
- 服务运行状态（server2/rescue2/chat.py）：
- 前置条件与操作步骤：
- 预期结果：
- 实际结果：
- 代码/自动化/CI/实机证据链接或日志：
- 状态：未开始 / 实现中 / 代码已核查 / 自动化通过 / CI通过 / 实机通过 / 阻塞/失败：
- 问题与后续修复：

## 7. 冻结规则

1. A01–A10 是最初的十项核心验收标准，永久保留。
2. S01–S18、M-A–M-C、F01–F12、G01–G06 是后续补充，全部累加，不得取代 A01–A10。
3. 新增需求只能增加条目或补充更严格的验证，不得删除、合并弱化既有条目。
4. 修改验收标准必须有明确变更记录；不能为了让当前实现通过而降低标准。
5. 任何单项失败都不能用其他项目通过抵消。
6. 未经实机验证的项目必须明确标注“未实机验收”。
7. 本矩阵适用于本分支后续所有提交、代码审查、自动化测试、CI 和最终发布验收。


## 8. 本轮集中反查记录（2026-11-22）

本节只记录已取得证据的代码/自动化/CI 结果，不构成整体验收通过声明。

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 稳定 peer 路由 | 已修复端点变化继承旧验证的问题；新端点须重新完成挑战验证 | commit `0f3bd13f6b31b09ae440fd7db9a58335c389fb19` |
| 网络恢复发现 | Android 监听启动/网络路径变化主动发送一次 HELLO；收到有效挑战响应后触发持久化 Outbox 补发；挑战专用包阻断 ping-pong | commits `1a4799063f2f7cd883f1f816e77457d1e029c80a`、`c66379a466da1d220d199068978ec90ed64d4c3c` |
| 收件箱去重恢复 | SQLite Inbox 增加持久化 presentation marker；历史提交与 marker 之间崩溃时可重试补齐；旧 500 条 ID 列表只作迁移/恢复辅助 | commit `362bfd3efa315ce59b1c25a2ef815f5e81168efd` |
| UDP 来源端点 | 异步处理前复制来源 IP/端口，避免接收循环复用 DatagramPacket 导致端点串包 | commit `0a2f045740a1d3620fc5d6b5f3d70f5c48203b25` |
| v2-only 接收面 | Android、server2、rescue2、chat.py 已移除旧字符串式对讲/发现/ACK 入口；保留普通救援指令和独立悬浮留言路径 | commit `efdd4789e16fa70c76df5b9556a8c7bc3349e13f` |
| 目标快照与重试测试 | 新增固定目标、部分 ACK 后只重试未确认目标、发现窗口冻结、空目标不误报送达、错误密钥拒绝等自动化用例 | commit `4b4cc9229481d4f5da21a9d302463ba389d8681d`；该提交 CI 中 12 项 Python 单测通过 |
| SQLite 写入失败安全 | Android Outbox、Targets、Inbox、Peer 和 ACK 写入现核查 insert/update 结果；peer.hello/peer.ack 只有相关持久化成功后才启用路由或回 ACK | commit `aa85ce537f211020c969c93ec77a0141c644c451` |
| Python 握手失败安全 | 三个 Python 服务仅在 peer 路由成功落库且 nonce 有效时回应 peer.ack | commit `623cd7bfa67f4933c719a7e7e61ab125c14bfa70` |
| v2 同文不同消息 | 取消按 3 秒相同正文吞消息；不同 message_id 即使正文相同，也各自写入历史 | commit `aeeb91770ff50f32416ecf3bf6f468b924421a85` |
| Python 离线成员目标快照 | server2/rescue2/chat.py 把历史已知成员纳入固定目标集合；即时 UDP 仅使用当前有效租约路由，不向过期 IP 盲目单播 | commits `8e10325aa3c66a895332e55e1e4f9be5c0486e62`、`6dcddb5cd67346fb8757e07c75408c892a3af58a` |
| 网页 HTTP 与 LAN v2 统一身份 | 同一网页消息的 UDP v2 与 HTTP 兜底使用同一 message_id/sender；Android 两路进入同一 SQLite Inbox；先到的一路显示一次，后到一路只确认；stored ACK 撤销另一条队列；HTTP accepted 只有在 Inbox 持久化后才确认 Outbox | commits `fcafe8cb37c544e5345a17beb4ab89d62a677d43`、`49163306e4206d1ca52a933bbbe93f459dd8d969` |
| HTTP 聊天中断恢复 | chat_msg 遇到 executing/uncertain/failed 后可用同一 message_id 幂等重试；其他有副作用命令保留原来的不自动重放规则；Python ACK 重复到达保持幂等 | commit `49163306e4206d1ca52a933bbbe93f459dd8d969` |
| Python 服务启动发现 | server2/rescue2/chat.py 的 UDP 监听成功绑定后各发一次认证 HELLO，覆盖 Android 与其他服务端口；无周期扫描；server2 网页设备目标只纳入 Android peer | commit `b9af01eb2bf517864aa802eec9515d79beb8f967`；17 项单测中的启动广播模拟测试通过 |
| 当前累计 CI | 最新代码提交执行 17 项 Python 单元测试、Android Java 编译、Debug APK 构建和 artifact 上传，全部成功 | [Actions run 44984288925](https://github.com/shua00/rebackmyphone3/actions/runs/44984288925)，对应 commit `b9af01eb2bf517864aa802eec9515d79beb8f967` |
| 最新 CI | Python 检查、12 项单元测试、Android Java 编译、Debug APK 构建和 artifact 上传均通过 | [Actions run 44983112090](https://github.com/shua00/rebackmyphone3/actions/runs/44983112090)，对应 commit `623cd7bfa67f4933c719a7e7e61ab125c14bfa70` |
| 首次发现窗口与目标快照 | Python 消息写入 Outbox 时启动 5 秒窗口；Android 在本地消息持久入队后、任何网络发送前立即固定历史已知成员（包括离线成员）；仅初始目标为空时开放 5 秒窗口收集，过期后禁止扩张；相同 message_id 重放不得修改正文/发送者/目标集合；空目标永不判 delivered | Python 28 项测试覆盖窗口起点、晚到拒绝、重放不可扩张、广播多设备收集；Android代码见 commit `d404c59e69d7e4e430284c97c460fb355b943b29`；CI run [44986264043](https://github.com/shua00/rebackmyphone3/actions/runs/44986264043) 成功 |
| Python 对称 peer challenge | 三个 Python 服务启动广播和定向 HELLO 都使用短时 nonce；只有 peer.ack 的 nonce、peer_id、来源 IP/端口与挑战一致才升级 active；挑战完成后再加入发现目标并补发待发送消息 | 本轮提交新增挑战错配/过期/候选路由限制自动化；需 CI 验证 |
| Python active 路由语义收敛 | 仅本端 nonce challenge 获得匹配 peer.ack 才升级 active；单纯收到 challenge_only 或 chat.message 只记录 suspect；ACK 来源匹配必须为当前 active endpoint；数据库不可用时 fail closed | 本轮新增 active/suspect 与数据库故障用例，待 CI 验证 |
| Python nonce challenge 与 active 路由 | 三个 Python 服务对称执行短时 nonce 握手；只有匹配 nonce/peer_id/IP/UDP端口的响应才升级 active；收到普通 chat.message 或 challenge_only 本身不能使路由 active；ACK 来源必须匹配当前 active endpoint；广播 nonce 可在窗口内收集多设备，定向 nonce 单次使用 | commit `1d66d74365645188b18da08b63056919e031440f`；28 项测试在 run [44985996764](https://github.com/shua00/rebackmyphone3/actions/runs/44985996764) 全部通过 |
| Android challenge 身份/端点绑定 | 定向 peer challenge 将 nonce 绑定到预期 peer_id、IP 和端口；广播发现仍能在窗口内收集多个 peer；定向 nonce 单次使用；网络路径变化清理挑战缓存 | commit `320f5487c82686a9c6321e39f75d2ccad61ad256`；CI run [44985771503](https://github.com/shua00/rebackmyphone3/actions/runs/44985771503) 成功 |
| Android API 21 兼容 | 移除 API 24 才提供的 ConcurrentHashMap.newKeySet() 和 Collection.removeIf() 用法，改用旧版 Android 可用的 Collections.newSetFromMap()/Iterator.remove()；不增加 desugaring 或依赖 | commits `f35a6fd84a100d9ee1047c2de3aae89fb6781c86`、`131f9dd3dc971a7a20855beffdcbfffcddc58016`；CI run [44986422892](https://github.com/shua00/rebackmyphone3/actions/runs/44986422892) 成功 |
| Android 首次目标快照的主线程隔离 | 已知成员在消息持久化时固定；5.25 秒窗口收敛通过已有串行发送执行器运行 SQLite，不在 Android 主线程执行；回收失败时保留本地队列供下次恢复 | commit `0efe4ed860a5f92092d9f5468e7bfffea02d4a7a`；CI run [44986292399](https://github.com/shua00/rebackmyphone3/actions/runs/44986292399) 成功 |
| Python v2 接收端 ACK 持久化语义 | 清除三个服务对固定显示名的持久化绕过；所有经认证的 v2 chat.message 都先写入各服务独立 SQLite Inbox/历史账本，再回 stored ACK；显示名仅作展示元数据 | 本轮新增跨服务源代码回归用例，随最新提交 CI 验证 |
| 消息 ID 正文冲突防护 | Android 与 Python 三服务都区分相同 message_id 的相同正文重发和不同正文冲突；相同正文才可重新 ACK，正文冲突不覆盖原记录且拒绝 stored ACK | 本轮共享核心函数 `validate_incoming_message_claim()` 与三服务回归用例，待 CI |
| 聊天窗口与留言代码边界 | 本轮通信核心改动未触碰 `ChatActivity.java`；悬浮留言接收/显示入口不在本轮改动文件中，仍须按 G01/G02 继续回归，不因 diff 未触及而宣布实机行为通过 | 对比冻结矩阵提交 `53a7ecb11901aa6f33d89e96257edad0947af317` 至本轮 HEAD 的文件清单；无 `ChatActivity.java` 变更 |
| 最新累计 CI | 最新分支 HEAD `131f9dd3dc971a7a20855beffdcbfffcddc58016` 的 Python 检查、28 项单元测试、Android Java 编译、Debug APK 构建、artifact 上传全部成功 | [Actions run 44986422892](https://github.com/shua00/rebackmyphone3/actions/runs/44986422892)；仍不代表真实多手机实机验收 |


**未完成/未宣称通过**：A01–A10 中要求真实设备的 LAN 双向、多设备、网络切换、三服务全关闭验收；M-A/M-B/M-C 实机运行；F02/F03/F04/F09/F10/F12 完整故障注入；G01–G06 完整回归。Android SQLite 故障注入、Python 三服务真实进程互通、真实 APK 在真实设备上的运行仍缺证据。不得以以上 CI 结果替代这些项目。
