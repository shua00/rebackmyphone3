# Rescue2 新诊断成果封存记录

封存日期：2026-11-27  
仓库：`shua00/rebackmyphone3`  
封存快照分支：`archive/rescue2-diagnostics-2026-11-27`  
封存快照提交：`f610cdbb9767fcce65657cbf178d79ff90b07d0b`

## 封存目的与状态

Rescue2 新诊断从当前开发主线暂停，退回后续 TODO。LAN P2P V2 双机基础闭环和真机验收优先。此封存是保留成果，不是宣布功能完成，也不是删除代码。

**当前状态：未完成、未通过端到端验收、不得视为可用的远程诊断功能。** 不继续扩建管理员认证、任务调度、设备端授权、网络传输和诊断回传链路，直到 LAN P2P V2 达到恢复门槛。

## 已有成果（保留，不继续扩展）

- 本地诊断模型与执行器：`DiagnosticRun`、`DiagnosticExecutor`、Probe Catalog。
- JSON/Markdown/HTML 报告生成、查看、保存、分享和历史记录。
- 多设备诊断批次 envelope、协调器和 app-private 本地 spool；Transport 仍为抽象接口。
- Rescue2 管理员 P-256 身份密钥、Windows DPAPI 私钥保护、公钥导出。
- Android 管理员公钥显式信任/撤销、指纹展示、签名验证、防重放、任务 envelope、consent store 等原型。
- Python/Android 协议模型与对应测试。
- 旧日志功能保护测试：`tests/test_rescue2_legacy_log_pull_diagnostics.py`。
- 相关实现及历史提交保留在封存快照中，不做删除或破坏性回退。

## 关键边界

1. 旧版 Rescue2 `pull_logs_multi()` 是 P1 既有能力，必须完整保留；本次封存不修改该函数、旧菜单、旧 UDP 命令、逐设备派发、上传接收、超时统计、保存/清理行为。
2. 新诊断日志采集（单设备/批量）属于 P2，尚未实现完整远程链路，暂不开发。
3. Rescue2 超级管理端不受应用层业务白名单或 Shell/ADB 命令白名单限制；Root 仍必须遵循 Android 设备自身授权、UID、SELinux 和系统安全边界。
4. Probe 白名单只约束普通 Android 诊断参与端的自动诊断任务，不得反向限制 Rescue2 管理端。
5. 不得把诊断 Transport 抽象、单元测试或管理员签名组件描述为远程诊断已可用。

## 已知未完成/质量状态

封存时主线 HEAD 为 `f610cdbb9767fcce65657cbf178d79ff90b07d0b`。最近 CI Run [45883687354](https://github.com/shua00/rebackmyphone3/actions/runs/45883687354) 失败：Python 校验与 Android 变更检测通过，Android build job 中 `DiagnosticTaskConsentStoreTest` 有 2 项失败（`requiresExplicitApprovalAndEnforcesTerminalTransitions`、`expiresAndPrunesOldPendingTasks`）。该失败随封存记录保留，不在当前阶段继续追逐诊断功能；恢复开发前必须先复现、修复并通过完整 CI。

## 恢复开发的前置条件

- LAN P2P V2 至少完成双 Android 真机双向发现、刷新、配对、文字/长消息、双向文件传输、最终送达 ACK。
- 完成断网/重连、Wi-Fi/AP/子网切换、进程重启恢复等异常验收。
- 验收报告区分 Android 单测、Python 单测、模拟集成、双机真机；不得用 CI 代替真机。
- 重新审计封存代码与当前主线的依赖和兼容性，确定最小恢复范围。
- 重新修复并验证封存时遗留的失败测试，再讨论诊断功能是否拆为可选构建/可关闭模块。

## 相关历史提交锚点

以下提交历史保留在仓库中，供后续逐项追溯（不是功能验收证明）：

- `6769538e5fe10c0ac71460cb9f7f2138da819fad` — 诊断任务 Envelope 初始实现。
- `4340aa94726a436a89f49df8781c4a1f7b8bf6f7` — TODO 文案测试锚点修复。
- `0251fe2d75c6e9e7bd3af58418251a9e758ddf0f` — Java JSON 字段校验测试。
- `43fc41015fd93576722e100a959d2c7c9ab4cf0d` — Rescue2 超级管理员权限约定与测试调整。
- `17865c698fedddb7aaf0526e64bfc38757d7f819` — Consent Store / Task Run Adapter。
- `753da302254e6291655e174aa572610e939f0e3d` — 验证结果与 consent 绑定。
- `6686042d463eb7abe461a3de86715ab464ebb367` — 管理员指纹测试修正。
- `0a05543482d2df05c68e7c328aa9c97e21a4fa23` — 执行时检查管理员信任撤销。
- `f610cdbb9767fcce65657cbf178d79ff90b07d0b` — Run Adapter 传入实时管理员信任状态。

旧日志保护恢复提交：`751ae6b06836ad1d3d7bcb835fc7b17bde4631ff`。  
旧日志保护/TODO 审计提交：`52fe22819bf04d2bc892eafe1482153789bb29f2`。

## 变更策略

封存快照分支用于保留当时完整代码状态；当前功能分支只更新计划和封存索引，不删除诊断代码、不改动旧日志功能、不做大范围回退。后续若需真正从正式 APK 剥离诊断代码，应另立明确批次，先做依赖分析和构建验证。
