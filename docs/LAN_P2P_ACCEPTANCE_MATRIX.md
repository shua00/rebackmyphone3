# LAN P2P 完整验收矩阵（v2-only）

状态：**正式验收基线；所有条目均不得因后续实现而删减或降级**  
适用分支：`feature/lan-p2p-v2-only`  
关联设计：[`LAN_P2P_RELIABLE_DELIVERY_DESIGN.md`](./LAN_P2P_RELIABLE_DELIVERY_DESIGN.md)  
版本日期：2026-11-23

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

**总体验收通过条件**：A01–A10、S01–S26、模式 M-A–M-C、故障 F01–F20 及回归 G01–G14 全部达到各自要求；所有要求实机验证的条目必须为“实机通过”。CI 通过、代码存在、模拟测试通过都不能代替实机验收。

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

## 2. 开发过程中补充的全部强制要求（S01–S26）

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
| S19 | Python 三服务持久化 ACK 超时重试 | rescue2/chat.py/server2 在 UDP 已发出但 ACK 丢失时，不得只依赖下一次握手；必须扫描有效租约的未 ACK 固定目标，使用原 message_id 有界补发；逐目标最多 8 次自动尝试并按 1.5/4/10/20/40/60 秒退避；ACK 目标立即退出；耗尽后保留 Outbox，只有 nonce challenge 验证的恢复事件可在 60 秒冷却后重开预算；schema 迁移失败不得永久丢失 worker | 自动化+CI+三服务实机 |

## 3. 三种运行模式（M-A–M-C）

| ID | 模式 | 固定场景 | 必须通过的行为 |
|---|---|---|---|
| M-A | APK 自主局域网模式 | 三个 Python 服务全部关闭；同一 LAN 有两台或多台 APK | 自动发现、双向一对一/多设备对讲、本地历史、ACK/重试、断网恢复；不依赖云端 |
| M-B | 局域网临时协作模式 | 同 LAN 下按需启动 chat.py 或 server2.py，其他服务可关闭 | APK 直连仍可工作；网页/服务端协作功能可用；服务启停不导致身份、历史或消息重复；不得要求所有服务必须同时运行 |
| M-C | Armbian 服务端模式 | Armbian 上运行 server2.py/rescue2.py，并按既有部署使用 chat.py | 跨网络 HTTP/服务端消息、网页留言、设备管理、陪护/定位、运动数据、命令反馈、诊断及既有功能保持可用；服务端接收与设备 ACK 分开统计 |

## 4. 必须覆盖的故障与回归用例（F01–F20 / G01–G14）

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
| F11 | 队列满、消息过期与迟到重放 | 队列满明确拒绝；未定义统一 TTL 前不得自动过期或提前清理去重记录；未来启用 TTL 时，expired 不可被恢复事件复活，迟到重放不得重新展示 |
| F12 | HTTP 已接收但目标设备离线 | 显示服务端已接收、设备尚未 ACK；上线后恢复投递 |
| F13 | Python UDP 已发出但 ACK 全部丢失，期间不发生新握手 | 三服务各自 worker 按持久化退避自动重试原 message_id；只重试未 ACK 目标；达到 8 次后暂停定时重试；验证 peer challenge 恢复事件冷却、旧 schema 迁移和服务重启后恢复；不得高频风暴或把 ACKed 目标重新发送 | 自动化+故障注入+三服务实机 |
| G01 | 留言悬浮回归 | 留言显示/关闭/已读/历史/清理与改造前一致 |
| G02 | 对讲回归 | 对讲历史、通知、窗口打开关闭行为不变；留言不进入对讲历史 |
| G03 | 找回/定位回归 | 原有找回和定位路径、指令、上报不退化 |
| G04 | 设备管理回归 | 在线/离线/异常设备管理与安全删除规则符合 S16 |
| G05 | Python 服务回归 | 三服务独立/代理模式、端口、网页和原管理功能保持 |
| G06 | 发布回归 | v2-only 分支构建通过；不得合并 main；APK/服务版本与协议版本匹配 |
| G07 | Python 三服务重试回归 | 分别启动 rescue2/chat.py/server2，独立数据库与端口下验证 ACK 超时 worker、有限预算、恢复冷却、schema 迁移和目标隔离；不依赖其他 Python 服务运行 | 自动化+真实进程互通+CI |

## 5. 实施状态记录（初始冻结）

冻结时依据现有设计文档和已知 CI 信息建立基线。**本表不是通过声明**；未附带可核验测试证据的项目一律保持未验收。

| 范围 | 冻结时状态 | 说明 |
|---|---|---|
| A01–A10 | 未完成实机验收 | 不能由构建结果推断通过 |
| S01–S26 | 未完成整体验收 | S19–S25 已有代码/自动化/CI证据；Android SQLite真实迁移/故障注入、真实多设备/进程故障注入仍待验收 |
| M-A–M-C | 未完成实机验收 | 三种运行模式分别验收 |
| F01–F20 | 未完成完整故障注入 | F13/F14/F19已有自动化/CI证据；F15/F17/F18涉及的真实多目标、socket及Android SQLite故障注入仍待补齐 |
| G01–G14 | 未完成完整回归 | 尤其留言悬浮、找回定位、设备管理及三服务真实进程边界 |
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
2. S01–S26、M-A–M-C、F01–F20、G01–G14 是后续补充，全部累加，不得取代 A01–A10。
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
| Android 同 ID 正文冲突与 UI 恢复 | Outbox/Inbox 对相同 message_id 的重复包核对发送者和正文，冲突拒绝；当历史与 marker 已落盘但前次 UI refresh 因崩溃未发出时，重试重复包仅刷新窗口，不重复追加历史 | commit `a257dd8ecd19aed47dda903669a486096c0f742d`；新增 Java 源码回归守卫，待 CI |
| Android 发现 ACK 与路由信任隔离 | 未知设备只可在当前 message_id 的 5 秒发现窗口内 ACK 并进入该消息目标；该 ACK 不写入 `verifiedChatPeers`，后续单播仍要求独立 peer.hello/peer.ack nonce challenge | 当前代码静态核查确认；新增防回归源码测试，待 CI |
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
| Android API 21 兼容 | 移除 API 24 才提供的 ConcurrentHashMap.newKeySet() 和 Collection.removeIf() 用法，改用旧版 Android 可用的 Collections.newSetFromMap()/Iterator.remove()；不增加 desugaring 或依赖；额外扫描全部 20 个 Java 源文件，未发现其他同类集合默认方法 | commits `f35a6fd84a100d9ee1047c2de3aae89fb6781c86`、`131f9dd3dc971a7a20855beffdcbfffcddc58016`；CI run [44986422892](https://github.com/shua00/rebackmyphone3/actions/runs/44986422892) 成功 |
| Android 首次目标快照的主线程隔离 | 已知成员在消息持久化时固定；5.25 秒窗口收敛通过已有串行发送执行器运行 SQLite，不在 Android 主线程执行；回收失败时保留本地队列供下次恢复 | commit `0efe4ed860a5f92092d9f5468e7bfffea02d4a7a`；CI run [44986292399](https://github.com/shua00/rebackmyphone3/actions/runs/44986292399) 成功 |
| Python v2 接收端 ACK 持久化语义 | 清除三个服务对固定显示名的持久化绕过；所有经认证的 v2 chat.message 都先写入各服务独立 SQLite Inbox/历史账本，再回 stored ACK；显示名仅作展示元数据 | 跨服务源代码回归用例通过；commit `ae7880caa3697d4d4846f173bad21d6f0051dc82`；CI run [44986692609](https://github.com/shua00/rebackmyphone3/actions/runs/44986692609) 成功 |
| 消息 ID 正文冲突防护 | Android 与 Python 三服务都区分相同 message_id 的相同正文重发和不同正文冲突；相同正文才可重新 ACK，正文冲突不覆盖原记录且拒绝 stored ACK | 共享核心函数 `validate_incoming_message_claim()`；commit `c8e91015cb5dd5bd7cf343e11e48ed93185eecdf`；30 项测试通过，见 [CI run 44986833840](https://github.com/shua00/rebackmyphone3/actions/runs/44986833840) |
| Python 首次发现窗口 ACK 对齐 | Python 三服务与 Android 一致：初始无已知目标的 discovery_pending 消息，仅在 5 秒窗口内接受标准 UDP 端口的认证 ACK 并写入该消息目标；窗口关闭或消息已有固定目标后，不允许用发现 ACK 绕过 active route 验证 | 代码提交 `f4f30840d15a87652266bd6b62eb94bfcaa1e73b`，语法修复提交 `ae36efbec2beb39f01991b7590384d31641de80a`；CI run [44987182561](https://github.com/shua00/rebackmyphone3/actions/runs/44987182561) 成功 |
| Python 主动发送端 ACK 丢失重试 | rescue2/chat.py 对已验证固定目标只重发仍未 ACK 的原 message_id，最多 3 轮、250ms 间隔；首次无目标广播也最多 3 轮；所有重试使用已有监听 socket，不新增常驻线程；每轮批量读取路由状态，ACK 后下一轮跳过已确认设备；部分 ACK 时只重发剩余固定目标 | 34项基础重试测试见 commit `a064598c47b8d66fe550d9508acc2dbd5685c8d3` / run [44987439958](https://github.com/shua00/rebackmyphone3/actions/runs/44987439958)；双目标部分ACK只重发未确认设备新增用例见 commit `e3062da7a6e97ef75b967ae4f2b5dbd0d4202cdb` / run [44987697431](https://github.com/shua00/rebackmyphone3/actions/runs/44987697431)，成功 |
| 聊天窗口与留言代码边界 | 本轮通信核心改动未触碰 `ChatActivity.java`；悬浮留言接收/显示入口不在本轮改动文件中，仍须按 G01/G02 继续回归，不因 diff 未触及而宣布实机行为通过 | 对比冻结矩阵提交 `53a7ecb11901aa6f33d89e96257edad0947af317` 至本轮 HEAD 的文件清单；无 `ChatActivity.java` 变更 |
| 最新累计 CI | 最新代码提交 `e3062da7a6e97ef75b967ae4f2b5dbd0d4202cdb` 的 Python 检查、35 项单元测试、Android Java 编译、Debug APK 构建、artifact 上传全部成功 | [Actions run 44987697431](https://github.com/shua00/rebackmyphone3/actions/runs/44987697431)；仍不代表真实多手机实机验收 |


**未完成/未宣称通过**：A01–A10 中要求真实设备的 LAN 双向、多设备、网络切换、三服务全关闭验收；M-A/M-B/M-C 实机运行；F02/F03/F04/F09/F10/F12 完整故障注入；G01–G07 完整回归。Android SQLite 故障注入、Python 三服务真实进程互通、真实 APK 在真实设备上的运行仍缺证据。不得以以上 CI 结果替代这些项目。


## 9. 本轮集中推进记录（2026-11-22，commit `5fd24070bdbcf57897de82d5ace185b473014b7c`）

本轮只追加实现和验证记录，不改变 A01–A10、S01–S19、M-A–M-C、F01–F13、G01–G07 的验收门槛。

| 项目 | 本轮结果 | 证据与限制 |
|---|---|---|
| Android Outbox 崩溃恢复 | 补发主来源改为 SQLite Outbox；SharedPreferences 仅保留为迁移/兼容镜像，避免镜像丢失导致 SQLite 待发消息被漏掉 | commit `a10177bb68f0214583c3b8e3cbce00b71043c6db` |
| SQLite 读取失败安全 | pending、已知 peer、固定 target 的 SQL 查询失败不再静默转换为空列表；发送端在无法读取固定收件人集合时拒绝本轮 UDP 发送 | commits `db67f8db738ae06ba6f64038f07eac5cdf52b6fc`、`c783c6507a53de5e87bd8eafdc8f8fc0c8c064a4`、`5cd1f07b920586de0a6f0d5bafe9a6eeb19c69a7` |
| Android 有界持久化补发 | Outbox 新增发送尝试计数及增量 schema v4 迁移；按 SQLite 快照分批处理，避免首批未 ACK 消息阻塞后续新消息；增加有限延迟唤醒，失败记账时不安排重试唤醒，服务销毁后停止延迟补发 | commits `cc9ea5c060f055d03a5b63a09cb4435675daa8d4`、`1e35a55db8713c7fe61682589054d29cfb16bf80`、`ab219a6304544107ab9bb0e81e7fc808b6e6e7f1`、`2ab1649b0e9a90bf349dfc3b4fd97b4a3ddf115f`、`fb201cada48a3e15a055c5e73f344720eca58af4` |
| Android 首次发现窗口冻结 | 空目标消息仅在原始发现截止时间内允许发现 ACK/广播；窗口关闭且无固定目标后禁止向晚加入设备广播旧消息 | commits `dbf9c35e53b653719b0c5d8442f4c514657f1adb`、`af8f8a01efe727d509c8c2112e81b513809f4b69` |
| Python 三服务固定接收人 | rescue2、chat.py、server2 网页对讲均按创建时固定目标集合发送；存在固定目标但当前无 active endpoint 时保留消息等待原 peer 恢复，不退化为全网广播；仅首次无已知目标且发现窗口开放时才允许有界发现广播 | commits `21cc2edb3c220aec811ba385977c6032d06dd384`、`12b051d845e4cf8ab2dc58202eea135385f08c91`、`0e6a4015809ca2064bddab0c361ef24de54863ce`、`7b3ee6e8b707de93cf30087b7a61ff76668622c3` |
| 本轮新增防回归测试 | 加入 Android Outbox 恢复、SQLite 读失败、有限重试/批次推进、服务销毁保护、发现窗口冻结及三个 Python 发送端固定目标规则的源码防回归测试 | 最新提交共 44 项 Python 单元测试通过 |
| 最新 CI | Python 服务语法检查、44 项单元测试、Android Java 编译、Debug APK 构建和 artifact 上传全部成功 | [Actions run 44997507429](https://github.com/shua00/rebackmyphone3/actions/runs/44997507429)，commit `5fd24070bdbcf57897de82d5ace185b473014b7c`；APK artifact ID `14744068334`，大小 161937 bytes |

**当前验收结论仍为未完成。** 本轮新增代码和 CI 证据不替代真实设备验收。A01/A04/A05/A07/A08/A09/A10、M-A/M-B/M-C、F02/F03/F04/F09/F10/F12 及 G01–G07 中要求真实设备/服务运行的项目，仍须按本矩阵逐项采集真实日志和操作结果；尤其必须实际验证“三个 Python 服务全部关闭时，两台及以上 APK 仍可双向对讲”。不得把 CI 通过标记为实机通过。


## 10. Python ACK 超时重试新增验收记录（2026-11-23）

本节是 S19/F13/G07 的实现证据记录，不表示这些条目已完成实机验收。

| 项目 | 代码/自动化结果 | CI 证据 | 实机状态 |
|---|---|---|---|
| 三服务 ACK 超时自动重试 | 共用持久化逐目标预算、退避与 due-peer 扫描；新增目标隔离、ACK 排除、attempt cap、恢复冷却测试 | commits `75525a14005c3057d96502320e772a1334f10dd6` 至 `a6d86b14e2a29b9935b6b42f2734fea8bea945fa`；最新 136 项测试通过，[run 45119367057](https://github.com/shua00/rebackmyphone3/actions/runs/45119367057) | 未实机验收 |
| schema 迁移失败自恢复 | 三服务唯一 worker 不因启动迁移失败而永久退出；新增静态回归检查 | commit `0aaa501cda5b4dbb82850eca2a09539f8a4a6481`；135 项测试通过，[run 45119234531](https://github.com/shua00/rebackmyphone3/actions/runs/45119234531) | 未实机验收 |
| 快速发送目标过滤 | 查询层排除 waiting_peers、ACKed 和达到预算的目标；原子发送记账仍作最终闸门 | commit `a6d86b14e2a29b9935b6b42f2734fea8bea945fa`；136 项测试通过，[run 45119367057](https://github.com/shua00/rebackmyphone3/actions/runs/45119367057) | 未实机验收 |
| CI 分流 | Python/测试变更只运行 Python 验证；Android 源码或 Gradle 输入变化才编译 APK；纯文档/CHANGELOG 不触发流水线 | workflow commit `91c5c2c8cde511967e78f00887bb8d0723d111e7`；后续 Python-only runs 中 build job 均为 skipped | 已由 Actions 路径判定验证，非设备验收 |

**S19/F13/G07 当前状态：自动化通过、CI通过、实机未验收。** 需在真实运行的三个 Python 服务和真实 Android 设备间制造 ACK 丢失，验证实际重试间隔、预算耗尽、恢复事件与去重行为；不能仅凭单元测试宣布通过。


## 追加验收条目（v2.8.19）

| ID | 验收要求 | 必须验证的行为 | 证据 |
|---|---|---|---|
| S20 | 后台重试原子退避复核 | due-peer 查询结果仅作为候选；后台 worker 在记账发送时必须原子复核当前退避时间，不能使用过期候选越过刚完成的初始发送 | 代码/自动化/CI/三服务进程 |
| F14 | 初始发送与后台 worker 交错 | 注入“worker先查询到期目标、初始发送随后先记账、worker最后尝试发送”的交错顺序；worker 必须被原子退避闸门拒绝，退避到期后才可再次发送 | 自动化/CI |
| G08 | 三服务严格退避模式回归 | rescue2.py、chat.py、server2.py 的后台恢复路径均调用严格退避复核；初始快速发送和 verified recovery 行为不回退 | 自动化/CI/三服务真实进程 |

以上新增项不替代 A01–A10、S01–S19、M-A–M-C、F01–F13、G01–G07。


## 追加验收条目（v2.8.20）

| ID | 验收要求 | 必须验证的行为 | 证据 |
|---|---|---|---|
| S21 | Android/Python逐目标预算一致 | Android固定目标与Python目标均独立计数、独立退避；ACKed目标不影响其他目标预算；所有未ACK目标耗尽后才暂停 | 代码/自动化/CI/真机 |
| F15 | 多目标部分ACK与不对称在线 | A目标ACK、B目标离线/延迟上线时，A不重发，B保留完整独立预算；A的尝试不消耗B的预算 | 自动化/CI/双设备 |
| G09 | Android目标级恢复迁移 | 旧数据库增量迁移 last_recovery_at_ms；恢复事件只重置冷却到期的耗尽目标，其他目标计数不变 | 自动化/CI/Android SQLite故障注入 |
| S22 | Python三服务监听生命周期 | rescue2/server2 在 listener 重绑后复用唯一 worker；chat.py 独立模式绑定/接收失败后延迟重试，代理模式不启动重复 UDP listener | 代码/自动化/三服务进程 |
| F16 | chat.py 监听重绑定与代理隔离 | 注入独立模式 UDP bind/recv 异常，listener 清理旧 socket 并延迟重绑；server2 在线时不启动 chat.py 自己的 UDP listener | 自动化/CI/三服务进程 |
| G10 | 三服务独立/代理边界 | 三服务各自使用独立 DB scope 和端口；chat.py 代理模式只通过 server2 API；server2 退出后需重启 chat.py 才重新判定独立模式 | 自动化/CI/真实进程 |

### v2.8.20 自动化与构建证据

- GitHub Actions run [45123636395](https://github.com/shua00/rebackmyphone3/actions/runs/45123636395)，最终代码 HEAD `cd1164c1d2f7c143e55d94952ad84733638c6155`：138 项 Python 自动化测试全部通过；validate-python、Android 变更检测和 build 三个 job 全部成功。
- Android `:app:assembleDebug` 构建成功；artifact `app-debug` ID `14794508674`。
- S21/S22 的代码级和 CI 回归已通过；F15/F16 的真实多设备/真实进程故障注入仍待执行，G09 的 Android SQLite真实迁移与故障注入仍待真机验收。
- 本轮不是整体验收完成：原有 A01–A10、S01–S22、M-A–M-C、F01–F16、G01–G10 仍须按矩阵完成真实设备/真实进程验收。

以上新增项不替代 A01–A10、S01–S20、M-A–M-C、F01–F14、G01–G08。



## 追加验收条目（v2.8.21：补齐 UDP listener 重绑定要求）

| ID | 验收要求 | 必须验证的行为 | 证据 |
|---|---|---|---|
| S23 | UDP接收异常后 listener 自恢复 | 三服务遇到不可恢复的 recvfrom 异常时，必须释放旧 socket、清空旧 listener 引用并重新 bind；同一进程恢复 worker 保持唯一 | 代码/自动化/CI/真实进程 |
| F17 | UDP recvfrom 故障注入 | 分别注入 chat.py/rescue2.py/server2.py recvfrom 失败，验证旧 socket 关闭、端口重新绑定、worker 不重复启动且 Outbox 不丢失 | 自动化/CI/三服务真实进程 |
| G11 | 三服务 listener 恢复回归 | 独立运行三服务，分别验证监听重建后认证 v2 收发和后台补发继续工作；chat.py 代理模式不得额外启动 UDP listener | 自动化/CI/真实进程 |

v2.8.21 静态回归测试和 CI 已通过；真实 socket 故障注入及三服务进程互通仍未实测。


## 追加验收条目（v2.8.22：ACK端点来源与恢复冷却跨端一致性）

| ID | 验收要求 | 必须验证的行为 | 证据 |
|---|---|---|---|
| S24 | Android ACK备用端点必须有成功发送证据 | 历史 peer endpoint 只用于定向 challenge，不可直接作为 ACK 持久化备用校验；只有 UDP send 成功返回后才能写入独立 last_sent_endpoint；旧数据库迁移不得把旧候选地址复制为 ACK 证据 | 代码/自动化/CI/Android SQLite迁移注入 |
| S25 | 恢复冷却只作用于耗尽目标 | Python 与 Android 一致：只有 attempt_count 达到8次的未 ACK 目标才写入 recovery cooldown；预算未耗尽目标的恢复事件不得提前消耗其冷却 | 代码/自动化/CI/三服务真实进程 |
| F18 | 历史 endpoint 不能伪造 stored ACK | 保留历史IP/端口供 challenge；成功UDP send前，单凭历史地址的ACK必须拒绝；升级旧库后旧字段不能冒充成功发送端点 | 自动化/CI/Android SQLite故障注入 |
| F19 | 目标耗尽前后的恢复事件时序 | attempt_count小于8时恢复事件不得写入 last_recovery_at_ms；随后耗尽时，未被提前占用的冷却状态应允许按既定规则恢复 | 自动化/CI |
| G12 | Android/Python恢复冷却一致性 | Android及Python三服务均仅重置冷却到期的耗尽目标；未耗尽目标计数、退避和恢复时间戳保持不变 | 自动化/CI/真实进程 |
| G13 | Android ACK endpoint provenance 回归 | last_endpoint 继续提供 route challenge 候选；matchesExpectedAckEndpoint 只查询 last_sent_endpoint；recordSuccessfulSendEndpoint 在 socket.send 成功后同步更新两字段 | 自动化/CI/真机 |

以上 S24/S25、F18/F19、G12/G13 强化既有 S09、S21、F05、F15、G09，不替代原有验收项。


## v2.8.22 自动化与构建证据

- GitHub Actions run [45126734639](https://github.com/shua00/rebackmyphone3/actions/runs/45126734639)，对应最终代码/测试提交 `00473fb5939fc3bdba10f082b85c703cd375bec9`，workflow conclusion 为 success。
- `validate-python`：140 项自动化测试全部通过。
- `detect-android-changes`：通过；`build`：Android Debug APK 构建和 Upload APK 两步全部成功。
- artifact：`app-debug`，ID `14795169216`，大小 `177488` bytes，预计于 `2027-02-21T06:49:28Z` 过期；GitHub artifact ZIP digest：`sha256:128ea91d82a40db66b873ca442cdf349eb39394e038ad62f849199d0827b70be`。
- 本次 CI 证明 Python 自动化、Android 编译及产物上传成功；不证明 Android SQLite v7→v8 真实设备迁移、ACK端点故障注入、三服务真实进程 socket故障恢复或双手机互通已通过。上述仍按 A/S/F/G 条目保留为实机/真实进程待验收。


## 追加验收条目（v2.8.23：发现报文 network_scope 有界编码）

| ID | 验收要求 | 必须验证的行为 | 证据 |
|---|---|---|---|
| S26 | 发现报文 scope 字段有界 | Android 保留完整 Wi-Fi 路径指纹仅供本地网络变化检测；发往 UDP 的 network_scope 必须使用固定长度 SHA-256 摘要，不得直接序列化 LinkProperties/Route 列表；1200 字节协议上限保持不变；该摘要仅为提示字段，不是身份或认证依据 | 代码/自动化/CI/Android 多设备实机 |
| F20 | 超长网络路径信息导致发现包超限 | 构造包含大量接口地址/路由信息的网络快照，验证启动 Beacon、普通发现和定向 challenge 均能编码为不超过 1200 字节的认证 v2 数据报；接收端正常解码；UNAVAILABLE/ERROR/NO_WIFI 不得阻断发现流程 | 自动化/CI/Android 实机 |
| G14 | Android 发现 scope 编码一致性 | 所有 Android peer.hello 出口统一使用同一 SHA-256 scope helper；不得有原始 wifiLanFingerprint 直传；本地完整 fingerprint 比较与网络切换 endpoint 失效逻辑保持不变；ACK 拒绝日志包含真实来源 IP/端口及 route/持久化端点/discovery 状态 | 源码回归/CI/实机 |

### v2.8.23 验证边界

本条目由 2026-11-23 双手机日志中的 `LAN peer beacon creation failed: IllegalArgumentException` 与 `LAN P2P datagram exceeds size limit` 触发。修复不放宽数据报大小限制、不改变 nonce challenge、peer 身份、ACK 来源校验或消息账本。CI 通过后仍须在 Android 11 与 Android 15 双机上确认发现握手和双向 ACK 闭环。


## 11. Android 首次发现 ACK 等待计数闭环修正（2026-11-24）

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 已验证路由的首次发现 ACK | 修复 peer.ack 先于 chat.ack 到达时，chat.ack 因走 verified-route 快路径而未释放空目标发现等待计数的问题；ACK 持久化成功后，只有非预期 peer 且空目标发现窗口仍开放时才复核并释放一次等待计数；SQLite 复核失败保持等待 | 生产代码 commit `22c7df161baccf1380b1b4bf908af4fa0f13f9c9`；回归测试 commit `ce8aa1d5294dc4df62c859330380cbeb6711e55e` |
| 自动化 | 206 项 Python 回归全部通过 | Actions [#673](https://github.com/shua00/rebackmyphone3/actions/runs/45326992913)；测试提交 `ce8aa1d5294dc4df62c859330380cbeb6711e55e` |
| Android 构建 | Python validation、Android 变更检测、Gradle Debug APK 构建及 artifact 上传全部成功 | Actions [#672](https://github.com/shua00/rebackmyphone3/actions/runs/45326981438)；生产代码提交 `22c7df161baccf1380b1b4bf908af4fa0f13f9c9`；artifact `app-debug` ID `14890361498` |
| 实机状态 | 未验收 | 首次发现多设备真实时序、ACK 丢失/重发和双手机即时性仍需真机日志；不得用 CI 代替 |

**本记录不改变 A01–A10、S01–S26、M-A–M-C、F01–F20、G01–G14 的既有门槛，也不构成 LAN 整体验收通过。**


## 12. ACK 来源与多目标恢复冷却收尾复核（2026-11-24）

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 多目标恢复冷却混合批次 | 代码修复：同一恢复事件即使重开部分目标预算，也继续安排其余耗尽目标的最早冷却截止唤醒；更早截止可替换较晚定时器，旧 callback 通过 generation/CAS 校验失效 | commits `4a68816e3c92dc4e50813818b8f23d3b0f709f90`、`3a9bfbef2c9acbb6a9ad837edbaae9b3510e393f`、`dff2217a50061d354954b276fecb0999146ca0ea`；多目标不对称时序仍需行为级故障注入/实机 |
| ACK 来源证据 | Android `matchesExpectedAckEndpoint` 仅认可成功 UDP send 后记录的 `last_sent_endpoint`；尝试端点只触发一次有界延迟复核；成功发送端点异步记账队列拒绝时有同步兜底 | commits `912f4cf8e9ba65f20108e204bfc8393f2481cdd1`、`ac14c461376859db9337582f533b762aa93cc5b1`、`004238e0063c26e929e4dfb660deabea056f4521`；快速 ACK/发送竞态仍需故障注入和真机 |
| 回归测试 | 修正旧源码断言，使其验证新 ACK 证据边界、发现窗口延迟复核、存储读取失败和成功发送端点记账兜底 | 测试 commit `35734c33b0edfd06fa632be5ecb7b2be6c4f3c8c`；216 项 Python 测试通过 |
| 对应 CI | Python validation、Android 变更检测、Android Debug APK build 和 artifact upload 全部成功 | Actions [#701](https://github.com/shua00/rebackmyphone3/actions/runs/45337733751)，HEAD `dff2217a50061d354954b276fecb0999146ca0ea`；artifact `app-debug` ID `14895185726`，202328 bytes（artifact 压缩包大小），未过期 |
| PR 状态 | PR #1 最新 HEAD 与上述 CI HEAD 一致；开放、未合并，GitHub 标记 mergeable | https://github.com/shua00/rebackmyphone3/pull/1 |
| 实机状态 | 未验收 | 双向消息与 ACK、ACK 丢失/重发、部分 ACK、多目标不对称冷却、断网/IP 变化恢复、进程重启、无 Python 服务运行模式仍必须实测 |

**本记录仅证明当前 HEAD 的回归测试与 Android 构建通过。A01–A10、S01–S26、M-A–M-C、F01–F20、G01–G14 的总体验收门槛不变；任何实机要求均不得以 CI 替代。**


## 13. 外部架构对照后的改进记录（2026-11-24）

外部项目对照与取舍记录见 [LAN_P2P_ARCHITECTURE_DECISIONS.md](LAN_P2P_ARCHITECTURE_DECISIONS.md)。外部项目源码/README 仅作为设计参考，不构成本项目验收证据。

| 项目 | 当前代码结果 | 可核验证据 | 尚未覆盖 |
|---|---|---|---|
| Python ACK 来源语义统一 | 将发送尝试 endpoint 与成功发送 endpoint 分离；ACK 只匹配成功发送 endpoint 或已验证实时路由；三服务固定目标恢复发送统一走 send+record | commits `8bd9e6b`、`ef8a7c7`；新增发送失败/成功、快速 ACK 竞态和旧 schema 迁移测试 | 三服务并发真实进程、真实 UDP 极快 ACK 与磁盘故障组合 |
| Android/Python 协议解码一致 | Android 使用严格 UTF-8 decoder，非法字节不再被静默替换；与 Python strict decoder 对齐 | commit `db2a85e`；Actions run [45342369963](https://github.com/shua00/rebackmyphone3/actions/runs/45342369963) 的 Python 验证、Android Debug APK 构建和 artifact 上传成功；artifact ID `14897500187` | 真机对非法数据报的拒绝行为和正常消息互通 |
| Python UDP 接收队头阻塞 | 三服务接收循环只做数据报边界检查、认证和有界入队；ACK、peer 握手、chat.message 分 lane；每 lane 固定 worker，队列有上限 | commits `360225c`、`1c51c88`；Actions run [45343428421](https://github.com/shua00/rebackmyphone3/actions/runs/45343428421)，223 项 Python 测试全部通过 | 高并发压力、队列满时的真实重试恢复、socket 重绑期间队列任务 |
| 当前分支基线 | PR #1 最新代码 HEAD 为 `1c51c88a7b94d7abd3d69b7c1dbbb41babdecbe5`，仍开放且未合并 | GitHub PR #1 | 代码层完整验收与真机验收尚未完成 |

**边界声明：** Actions #706 对应 Python-only 提交，Android build 按路径规则跳过；最近一次包含 Android 源码的完整构建为 Actions #704（commit `db2a85e`），已成功。以上 CI 均不替代 A01–A10、M-A–M-C 和 F/G 系列的实机验收。

## 14. 安全边界与后续迁移风险（2026-11-24）


| 共享密钥安全边界 | 当前 Android APK 和 Python 服务使用编译/默认共享密钥；HMAC 提供完整性与持钥证明，但不提供机密性，也不构成设备级唯一身份认证 | 源码核查确认；server2 注释明确说明出厂值已写入存量 APK，服务端单边轮换会使旧设备 UDP/HTTP 失联 | 本轮不轮换，避免破坏兼容；后续需独立安全迁移设计、配对与密钥轮换验收 |



## 15. Android 原始 UDP 字节严格 UTF-8 边界修复（2026-11-24）

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 缺口定位 | 发现 Android v2 解码器虽然启用 CharsetDecoder REPORT，但调用链此前先将 DatagramPacket 转为 String，再重新编码为 UTF-8；上游替换字符可能掩盖原始非法字节 | 静态核查 TrackingService UDP receive loop 与 handleLanRealtimePacket 调用链 |
| 原始字节修复 | 新增带 offset/length 的 LanP2pProtocol.decode；v2 分支在 legacy String 转换之前直接解析 DatagramPacket 原始 buffer 切片；旧控制命令仍走原字符串路径 | commits 90092cc、3d87bc4；方法边界说明 commit 9412843 |
| 回归守卫 | 检查原始字节切片进入严格 decoder、v2 处理先于 legacy String 转换，并更新旧接收时延守卫的方法签名 | test commits 80c064c、57fe278 |
| CI | Python 224 项测试全部通过；Android Debug APK build 与 artifact upload 成功 | Actions #711：https://github.com/shua00/rebackmyphone3/actions/runs/45344665870；HEAD 94128434824b8012de50ac32a36e16d6f396880d；artifact app-debug ID 14897264060，202626 bytes（压缩包大小） |
| 实机状态 | 未验收 | 仍需在真实 Android 设备/协议测试中发送非法 UTF-8 数据报确认拒绝，并验证正常 Android↔Python 消息、ACK 与时延不退化 |

**本项是协议解析边界修复，不改变 v2 信封字段、HMAC、1200 字节上限、消息状态机或旧控制命令语义。CI 通过不代表真实网络协议行为已实机验收。**


## 16. Android/Python peer ID Unicode 标量一致性（2026-11-24）

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 缺口定位 | Python 对 peer ID 执行严格 UTF-8 编码，拒绝孤立 UTF-16 surrogate；Java String.getBytes(UTF-8) 会以替代字节处理孤立 surrogate，导致同一身份在两端校验结果不一致 | 对照 lan_p2p_core.py is_valid_peer_id 与 LanP2pProtocol.isValidPeerId |
| 修正 | Android 先显式校验高/低 surrogate 配对，再计算 UTF-8 字节长度；有效 supplementary Unicode 字符仍允许，C0/C1 控制字符及 128 字节上限保持一致 | LanP2pProtocol.isValidPeerId |
| 回归 | 增加 Python 孤立高/低 surrogate、DEL/C1 控制字符拒绝测试；增加合法 supplementary 字符接受测试；增加 Android 源码守卫 | tests/test_lan_p2p_core.py |
| CI/实机 | 待本提交 Actions；尚未进行 Android 真机跨端身份互通验证 | CI 结果不得替代实机验证 |

**本项只统一 peer ID 的 Unicode 标量合法性，不改变身份生成格式、已有合法 UTF-8 peer ID、消息字段、ACK、端口或重试策略。**


## 17. v2 信封全部字符串的 Unicode 标量一致性（2026-11-24）

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 缺口定位 | 孤立 surrogate 不仅可能出现在 peer ID，也可能出现在正文、元数据或 JSON 对象键；Python 严格 UTF-8 规范化会拒绝，Android 默认字符串编码可能替换 | Android/Python 编解码器静态对照 |
| 修正 | 两端在 HMAC 规范化前递归检查所有字符串值和对象键；拒绝孤立高/低 surrogate，保留合法 supplementary Unicode 字符 | lan_p2p_core.py、LanP2pProtocol.java |
| 回归 | 覆盖编码端字符串值/对象键、解码端转义 surrogate，以及 Android 递归校验源码守卫 | tests/test_lan_p2p_core.py |
| CI/实机 | 待本提交 Actions；尚未完成真实 Android↔Python Unicode 报文互通 | 不得以 CI 替代实机验收 |

**只拒绝无法形成合法 UTF-8 标量序列的字符串，不限制正常中文、emoji、换行或其他合法消息正文。**


## 18. 四类 v2 信封字段与跨端类型一致性（2026-11-24）

| 消息类型 | 必需字段 | 可选字段及类型约束 | 验收重点 |
|---|---|---|---|
| `peer.hello` | `sender_peer_id`、`nonce` | `sender_peer_type`、`service_id`、`network_scope` 为字符串；`challenge_only` 为布尔；`listen_port` 为 1–65535 整数；`capabilities` 为字符串数组 | 广播与定向 challenge 均可用；不能把字符串 `"false"` 当布尔值 |
| `peer.ack` | `sender_peer_id`、`reply_nonce` | 发送者元数据为字符串；`listen_port`（若有）为 1–65535 整数 | nonce、发送者及预期 endpoint 仍由握手状态机共同绑定 |
| `chat.message` | `sender_peer_id`、`message_id`、`target_peer_id`、字符串 `body` | `created_at_ms`（若有）为非负整数；展示/服务元数据为字符串 | 不再将对象、数组或数字正文隐式转成平台相关字符串 |
| `chat.ack` | `sender_peer_id`、`message_id`、`target_peer_id`、`status=stored` | 发送者元数据为字符串 | 必须匹配本机目标、原消息 ID、目标 peer 和已验证路由/成功发送端点；重复 ACK 幂等 |

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 缺口定位 | 四种类型的必需字段和部分类型约束此前主要分散在 Android/Python 接收处理器中；编解码器只做通用信封校验，部分错误 JSON 类型可能被 `optString`/Python `str()` 平台相关地转换 | LanP2pProtocol、lan_p2p_core 及三个 Python 服务的实际构造/消费路径 |
| 修正 | Android/Python 编码和解码统一执行类型级字段校验；不改变消息重试、发现窗口、ACK endpoint 证明或 UI 行为 | LanP2pProtocol.java、lan_p2p_core.py |
| 回归 | 四种消息类型合法往返；必需字段缺失、字段类型错误、非 `stored` ACK 状态拒绝；Android 源码守卫 | tests/test_lan_p2p_core.py |
| CI/实机 | 待本批提交 Actions；尚未完成 Android↔三个 Python 服务的实机互通 | 自动化与 CI 不替代实机验收 |

**本节字段约束以当前仓库全部发送端构造和接收端消费路径为基准；可选时间戳不因本次校验而变为必需字段。**


## 19. Android 与 Python 按设备恢复事件隔离（2026-11-24）

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| Android 恢复作用域 | 网络/监听器恢复保留全局事件；单个 peer 的 nonce challenge 完成后只重开该 peer 的耗尽目标，不影响其他 peer 的 attempt budget | commit `1c257a8`；`LanChatStore.resumeExhaustedRetriesForPeerRecoveryEvent` 按 `peer_id` 更新 |
| Android 事件所有权 | 单设备恢复事件独立排队；批次失败、执行器繁忙和批次运行期间新事件均保留；peer cooldown 使用 peer-scoped timer，不转成全局事件 | commit `1c257a8`；源码回归守卫 |
| Python 三服务事件保留 | socket 缺失/关闭、Outbox 扫描异常、peer 快照或 endpoint 暂缺、持久化读取失败时保留恢复事件；cooldown 未结束时保留到后续 worker 周期 | commit `1c257a8`；rescue2.py/chat.py/server2.py |
| B4 自动化与 Android 构建 | 237 项 Python 测试通过；Android Debug APK 构建、artifact 上传成功 | Actions [#728](https://github.com/shua00/rebackmyphone3/actions/runs/45366343675)，代码树与最终 B4 提交 `1c257a8` 相同；artifact `app-debug` |
| B4 最终提交 | B4 代码树压缩为单一提交；后续最终 squash 校验 Python 成功 | commit `1c257a8`；Actions [#729](https://github.com/shua00/rebackmyphone3/actions/runs/45366775467) |
| 实机/故障注入 | 未验收 | 仍需 SQLite 写失败、socket 重建、peer A 恢复而 peer B 耗尽、多个 peer 冷却不同步、进程重启后恢复的行为级测试 |

## 20. Python 三服务恢复入口与发送尝试边界一致性（2026-11-24）

| 项目 | 结果 | 证据与限制 |
|---|---|---|
| 恢复入口默认值 | 三服务 `_resume_pending_v2_for_peer` 默认 `recovery_event=False`；只有 worker 显式传入经过认证的 peer 恢复事件才重开有限预算 | commit `e65430b` |
| 发送尝试与网络发送 | `mark_outgoing_attempt` 失败且尚未登记尝试时保留恢复事件；尝试已登记后 UDP 发送失败则交由持久化退避，不 force 重开预算 | commit `e65430b`；三服务同一异常边界 |
| 回归测试 | 237 项 Python 测试全部通过，覆盖三服务恢复队列、默认恢复语义、attempt admission 与 send 顺序/失败语义 | Actions [#733](https://github.com/shua00/rebackmyphone3/actions/runs/45368098986) |
| Android 构建 | 本提交仅涉及 Python 服务、测试和文档；Android build 按路径规则跳过。Android 源码对应 B4 的完整 Debug APK build 已在 Actions #728 成功 | Actions #733、#728 |
| 实机状态 | 未验收 | 需要三服务真实进程在 SQLite 短时锁、UDP send 错误、服务重启与路由恢复组合下验证；CI 源码守卫不等同动态测试 |

**B4/B5 仅完成代码与自动化门槛，不代表 LAN P2P 总体验收通过。A01–A10、S01–S26、M-A–M-C、F01–F20、G01–G14 既有门槛不变；双端即时性、异常恢复和真实网络切换仍必须实测。**


## 9. 本轮继续推进记录（2026-11-24）

| 项目 | 代码/CI 结果 | 未完成边界 |
|---|---|---|
| B4 按设备恢复隔离与事件保留 | Android peer-scoped 恢复、Python 三服务恢复事件保留；全量 Python 检查与 Debug APK 构建通过，commit `f3a6723702f530802b870e6428fa24c89dc670aa`，Actions [#736](https://github.com/shua00/rebackmyphone3/actions/runs/45369701203) | Android SQLite 并发/故障注入、三服务真实进程恢复仍待验收 |
| B5 三服务重试查询公平性 | 移除恢复事件对普通目标退避的查询绕过；12 条积压、前 10 条退避时后续 due 消息可进入批次；包含在 Actions #736 | 真实多目标积压与高负载时延仍待实测 |
| Android API 21-22 网络恢复 | 默认路由非 Wi-Fi 时 LAN 快路径 fail-closed；默认网络变化撤销旧 peer 并重绑；AP/DHCP 元组进入旧系统网络代数；Debug APK 构建通过，commit `c37be937269c63617cbb7bbc2f9f766a55ec075d`，Actions [#737](https://github.com/shua00/rebackmyphone3/actions/runs/45371247699) | 尚无 API 21/22 真机或模拟器网络切换验证 |
| Android API 23+ 与 Python listener 网络恢复 | 源码审查确认 socket-bound Wi-Fi 指纹、代际重绑、Python listener 关闭后释放 socket 并重绑定 | Wi-Fi/蜂窝切换、断网恢复、路由器重启等动态故障注入未完成 |
| LAN v2 文件传输 | 当前协议仍仅包含 peer hello/ack 与 chat message/ack；文字消息保持小型 UDP 数据报 | 尚未实现文件 offer/chunk/校验/断点续传；按独立数据通道设计，不混入文字消息 UDP |

本节所列 CI 只证明对应 commit 的自动检查与 APK 构建；不替代多设备、真实网络切换和 SQLite 故障注入。
