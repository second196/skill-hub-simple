---
title: Skill 获取安装与失败回退设计
description: Skill 与 Tracker 的运行时获取、安装、切换、回退和紧急撤回技术方案
audience:
  - product-development
owner: product-development
status: confirmed
lastReviewed: 2026-09-03
sourceType: manual
---

# 方案：Skill 获取安装与失败回退

> 设计版本：v2.1。v1 是已确认并已实施到 Task 8 的基线，v2 增加已实施的 CR-022 CLI 接入；本版增加已确认的 CR-026 本地 Collector、鉴权配置和事件化 VSIX 安装修正。

## 1. 目标与非目标

### 1.1 目标

建立一个由 Java 控制面编排、运行时适配器执行实际安装的安装域，满足以下闭环：

```text
选择已发布版本 -> 校验范围/运行时能力 -> Skill 安装
-> Tracker 安装与配置 -> 健康确认 -> 启用/切换
-> 失败保持旧版本或回退 -> 事件补报与可查询
```

系统必须能回答：哪个 Skill 的哪个不可变版本被安装到哪个运行时实例、Skill 和 Tracker 各自处于什么状态、切换从何版本到何版本、失败发生在哪个阶段、回退是否成功，以及紧急撤回影响了哪些实例。

### 1.2 非目标

- Java 控制面不直接在远程主机执行任意 Shell 或 PowerShell；实际安装由运行时侧适配器完成。
- 不实现 Agent 的业务执行、Tracker 的运行事件采集、指标计算或评测 Runner。
- 不改变资产注册、Skill 版本内容、发布门禁和发布范围的权威规则。
- 不承诺未被调研材料验证的 Claude Code IDE 安装能力。
- 不使用 OAuth2、统一单点登录或 CLI Device Flow；浏览器继续使用账户密码和服务端 Session，CLI 使用既有 Bearer API Token。

## 2. 需求依据与版本

| 依据 | 版本/范围 | 用途 |
| --- | --- | --- |
| `docs/product-development/features/feature-skill-installation-recovery/requirement.md` | v2，用户已确认 | 六条安装恢复需求、三条 CR-022 CLI 接入需求和运行时矩阵 |
| `docs/product-development/work-items/work-skill-hub-platform/decomposition.md` | confirmed | Feature 边界、依赖和语义化需求 ID |
| `docs/product-development/work-items/work-skill-hub-platform/index.md` | confirmed | 资产发布、安装、观测之间的跨 Feature 契约 |
| `docs/research/skill-hub-research.md` | 3.2、4.3、6.1、6.2、6.4、7.3、8.1 | 运行时矩阵、Tracker、CLI 分发和失败恢复调研事实 |

当前增量设计和实施计划均为 `v2 confirmed`。安装域所有跨 Feature 关联使用 `version_digest`，不使用名称、默认版本或 `latest` 推断。

## 3. 架构和实施基线

| 类型 | 文档 | 版本/状态 | 本方案约束 |
| --- | --- | --- | --- |
| 后端架构 | `docs/product-development/architecture/backend-architecture.md` | v0.5-draft | JDK 8、模块化单体、控制面编排、CLI 和运行时适配器边界 |
| 前端架构 | `docs/product-development/architecture/frontend-architecture.md` | v0.3-draft，已确认作为输入 | Vue 3、TypeScript、Vite、Vue Router、Pinia；前端只发起请求和展示状态，不执行安装 |
| 实施规范入口 | `docs/product-development/standards/implementation/index.md` | v0.4-draft | 按影响范围执行 Java、Vue、Node.js CLI、TypeScript 和数据库规范 |
| Java 规范 | `docs/product-development/standards/implementation/java-best-practices.md` | active，嵩山版依据 | 明确 DTO/VO/DO、分层、异常、事务和返回值语义 |
| 数据库规范 | `docs/product-development/standards/implementation/database-design.md` | v0.6-draft | PostgreSQL 15、运行时接入实例、追加式事件、幂等和分页 |
| 组件规范 | `docs/product-development/standards/implementation/component-standard.md` | active | Vue 组件单一职责、显式加载/部分成功/失败状态和可访问性 |
| TypeScript 规范 | `docs/product-development/standards/implementation/typescript-best-practices.md` | active | 外部响应先校验、精确类型、异步错误可见 |

已识别的基线事实：后端架构明确不负责实际 Skill 安装，前端架构明确不在浏览器执行安装；因此本方案把运行时安装代理/适配器定义为外部执行边界，把控制面编排、持久化和事件契约保留在本 Feature。适配器具体语言、通信协议、对象存储产品、超时重试数值、容量、备份和 RPO/RTO 仍待实施基线确认。

## 4. 源码现状和影响范围

当前仓库已有资产与发布治理及安装恢复实现，CR-022 的 CLI 适配器和运行时接入登记尚未实现。相关事实如下：

| 事实 | 来源 |
| --- | --- |
| 发布绑定只允许 `PUBLISHED` 版本，并按资产、范围返回当前绑定 | `backend/src/main/java/com/km/skillhub/release/service/ReleaseScopeService.java:29-63` |
| 版本关联由 SHA-256 `version_digest` 校验，`latest` 会被拒绝 | `backend/src/main/java/com/km/skillhub/integration/downstream/RuntimeEvidenceContract.java:6-10`、`backend/src/test/java/com/km/skillhub/service/DownstreamContractTest.java:10-14` |
| 已有安装下游发布事件发布器和治理 Outbox | `backend/src/main/java/com/km/skillhub/integration/downstream/InstallationReleaseEventPublisher.java`、`backend/src/main/java/com/km/skillhub/integration/event/GovernanceEventPublisher.java` |
| 当前治理事件使用 Redis Stream `skillhub:governance:events`，尚无安装命令/回执消费者 | `backend/src/main/java/com/km/skillhub/integration/event/RedisStreamsOutboxDispatcher.java` |
| 数据规范已预留 `installation_instance`、`tracker_binding`、`installation_operation` 三类表，但当前迁移尚未创建 | `docs/product-development/standards/implementation/database-design.md:135-140`、`backend/src/main/resources/db/migration/V1__create_governance_scope.sql` 至 `V7__seed_default_governance_accounts.sql` |

影响范围：后端安装编排、PostgreSQL 安装元数据、Redis Streams 命令和事件、运行时适配器契约、运行观测下游契约，以及 Vue 安装实例和操作结果页面。不存在现成的运行时代理源码，因此适配器可行性需要协议级和本地最小适配器验证。

## 5. 方案概览

```text
Vue 控制台/受信客户端
          |
          v
Java Installation API
          |
校验发布绑定、运行时能力、权限和幂等键
          |
PostgreSQL 15 <-> Outbox -> Redis Streams
     |                         |
安装实例/操作/回执             运行时安装适配器
     |                         |
查询状态、审计、事件       下载、安装、配置、健康检查、回退
          |
          v
运行观测 Feature：安装/切换/回退/撤回事件
```

控制面先写入操作和期望状态，再发送命令；适配器完成本地动作后回传带 `event_id`、`operation_id`、序列号和 `version_digest` 的进度或结果事件。远程动作不参与 PostgreSQL 事务，使用补偿回退和幂等状态机保证最终可恢复。

## 6. 模块和职责边界

| 模块 | 负责 | 不负责 |
| --- | --- | --- |
| `installation` | 安装实例、操作状态、版本切换、回退和撤回编排 | 直接执行远程命令 |
| `runtime` | 运行时矩阵、适配器能力、安装命令和回执契约 | 修改治理表绕过 Service |
| `tracker` | Tracker 版本、配置摘要、安装状态和健康状态 | 采集运行事件正文 |
| `integration` | Outbox、Redis Streams、制品访问描述和下游事件 | 把消息发送成功当作安装成功 |
| `audit` | 关键操作和状态变化审计 | 普通删除历史记录 |
| `frontend` | 安装实例查询、操作发起、进度、部分失败和撤回结果展示 | 执行 Skill、Tracker 或回退 |

依赖方向保持 `controller -> service -> mapper`。运行时适配器只能通过明确的命令/事件 DTO 与控制面交互；不能直接读取或更新 PostgreSQL 表。

## 7. 接口、类型和数据结构

### 7.1 控制面 API

建议接口如下，具体序列化字段在实施时固定并生成契约测试：

```text
POST /api/v1/installations/operations
GET  /api/v1/installations/operations/{operationId}
GET  /api/v1/installations
GET  /api/v1/installations/{instanceId}
POST /api/v1/installations/{instanceId}/switch
POST /api/v1/installations/{instanceId}/rollback
POST /api/v1/installations/revocations
GET  /api/v1/runtime-matrix
```

写接口必须接收 `X-Request-Id` 或等价幂等键。安装请求至少包含 `assetId`、`versionDigest`、`runtimeKey`、运行时版本、范围 ID、目标实例标识和 Tracker 配置引用；响应包含操作 ID、当前状态、目标版本、错误码和审计关联 ID。

### 7.2 Redis Streams 事件

保留治理发布事件流作为输入，新增两个可配置流：

```text
skillhub:installation:commands   # 控制面 -> 运行时适配器
skillhub:installation:events     # 运行时适配器 -> 控制面
```

命令事件包括 `INSTALL_REQUESTED`、`SWITCH_REQUESTED`、`ROLLBACK_REQUESTED` 和 `REVOKE_REQUESTED`；回执事件包括 `ACCEPTED`、`STAGE_CHANGED`、`TRACKER_READY`、`HEALTH_CHECKED`、`SUCCEEDED`、`FAILED`、`ROLLED_BACK` 和 `REQUIRES_MANUAL`。

事件信封至少包含：`eventId`、`operationId`、`eventType`、`instanceId`、`assetId`、`versionDigest`、旧版本摘要、`runtimeKey`、运行时版本、范围 ID、事件序列、失败阶段、错误码、脱敏原因和发生时间。控制面按 `eventId` 去重，并拒绝同一操作的过期序列；适配器离线时在本地持久队列保留事件，恢复后按原 `eventId` 补报。

### 7.3 核心数据模型

`installation_instance` 保存目标实例的稳定身份、资产 ID、当前版本摘要、运行时、范围、期望状态、Skill 状态、Tracker 状态、总体健康状态、最近健康时间、最近错误和 `row_version`。同一目标身份只能有一个当前安装实例，版本历史由操作记录关联，不合并不同摘要。

`tracker_binding` 保存安装实例、Tracker 标识和版本、配置摘要、安装状态、健康状态、最近健康时间和失败原因。Skill 与 Tracker 状态分别返回，只有二者均为 `READY` 且健康检查通过时总体状态才为 `HEALTHY`。

`installation_operation` 保存操作 ID、幂等请求 ID、操作类型、实例 ID、切换前/后版本摘要、当前阶段、操作结果、失败阶段、错误码、脱敏原因、回退状态、操作主体和起止时间。为支持离线回执去重，实施迁移需增加不可变事件接收记录或等价的 `event_id + sequence` 唯一约束；该增量应在实施前补入数据库基线说明。

所有安装域记录通过逻辑外键关联资产、版本和发布绑定；版本不存在、摘要不匹配或关联被撤回时阻断操作。操作和审计历史不提供普通业务删除接口。

## 8. 数据流和状态变化

### 8.1 安装与切换

```text
请求 -> Session/RBAC/范围校验 -> 校验当前发布绑定和运行时矩阵
 -> 创建或锁定 installation_instance
 -> 创建 operation(REQUESTED) -> Outbox 发 INSTALL/SWITCH_REQUESTED
 -> 适配器暂存并校验制品摘要
 -> 安装 Skill -> 安装/配置 Tracker -> 健康检查
 -> 回传 SUCCEEDED -> 控制面提交当前版本和审计
```

切换采用“准备、确认、提交”三阶段。确认前旧版本仍为当前可用版本；提交只在 Skill、Tracker 和健康检查全部成功后发生。当前版本更新、操作结果和审计在一个本地事务中完成，消息发送由 Outbox 异步完成。

### 8.2 状态机

操作状态：

```text
REQUESTED -> VALIDATING -> DOWNLOADING -> INSTALLING_SKILL
 -> INSTALLING_TRACKER -> CONFIGURING -> VERIFYING -> SWITCHING -> SUCCEEDED

任一阶段失败 -> FAILED -> ROLLING_BACK -> ROLLED_BACK
                                      \-> REQUIRES_MANUAL
```

首次安装失败且没有旧版本时直接进入 `UNAVAILABLE`/`REQUIRES_MANUAL`，不启用新版本。Skill 成功但 Tracker 未完成时为 `INCOMPLETE`，不能显示为健康。撤回状态单独记录为 `REVOKE_REQUESTED -> REVOKING -> REVOKED`，多个实例部分失败时为 `PARTIAL_REVOKED`。

### 8.3 紧急撤回

控制面先校验管理员范围权限并将目标发布绑定标记为不可分发，再创建逐实例撤回操作。适配器停用实例并回报结果；控制面汇总成功、失败和未执行实例。撤回命令重复投递只返回原操作结果，不能恢复已撤回版本为当前默认版本。

## 9. 正常流程、异常、超时和重试

| 场景 | 处理 |
| --- | --- |
| 版本未发布、已撤回或范围不匹配 | 在创建操作前阻断，返回稳定错误码，不写成功安装状态 |
| 运行时未启用或能力不支持 | 返回 `RUNTIME_CAPABILITY_UNSUPPORTED`；Claude Code OTLP 仅允许上报能力时不得创建 Skill 安装任务 |
| 制品下载失败或摘要不匹配 | 记录 `DOWNLOADING` 阶段失败；旧版本保持当前，禁止启用未校验制品 |
| Tracker 安装/配置失败 | Skill 和 Tracker 分别记录失败；总体为 `INCOMPLETE`，旧版本存在时回退 |
| 健康检查超时 | 按配置重试；未达到成功条件前不切换旧版本 |
| 停用旧版本或启用新版本失败 | 执行补偿回退；回退失败进入 `REQUIRES_MANUAL`，持续上报当前状态 |
| 平台或网络离线 | 适配器本地队列保留事件；恢复后按事件 ID 补报，重复事件返回已处理结果 |
| 撤回部分失败 | 新安装立即阻断，逐实例记录结果，聚合状态为 `PARTIAL_REVOKED` |
| 重复请求或并发切换 | 按请求 ID返回原操作；按实例锁和 `row_version` 防止两个当前版本 |
| Redis/下游不可用 | 本地操作保持可查询，Outbox 重试；不能把已发送命令当作已安装 |

超时、重试次数、退避、适配器离线队列容量和人工处理 SLA 不在需求中指定，必须作为可配置部署参数并在实施前确认。

## 10. 权限、安全、性能和兼容性

- 安装、切换和回退至少要求目标范围内的发布/安装权限；紧急撤回要求治理管理员权限。
- 每次操作重新校验 Session、角色、范围、版本生命周期、当前发布绑定和运行时能力；前端按钮隐藏不替代后端校验。
- 运行时适配器只接收明确版本摘要和短时限制品访问描述，不接收长期数据库凭据；适配器必须在本地再次校验摘要。
- 禁止控制面根据用户输入拼接 Shell/PowerShell；命令由适配器按运行时白名单解释。
- 日志和错误不得包含密码、密钥、完整制品内容、本地绝对路径或完整运行文本。
- 安装实例和操作列表使用分页、稳定排序和按需加载；不一次返回制品内容或完整事件正文。
- 历史安装记录引用不可变 `version_digest`；停用运行时矩阵不删除历史实例、操作或事件。
- 运行观测 Feature 只消费安装、切换、回退、健康和撤回事件；它不反向修改安装状态。

## 11. 观测、迁移和回滚

每个操作关联请求 ID、操作 ID、实例 ID、版本摘要、运行时、阶段、结果和审计 ID。平台指标至少区分操作成功率、回退率、Tracker 不完整率、适配器离线积压和撤回部分失败数量；具体指标名称和容量待确认。

首个迁移建议为 `backend/src/main/resources/db/migration/V8__create_installation_recovery_metadata.sql`，创建三张核心表、事件幂等约束、当前实例索引和操作查询索引。Flyway 脚本不可修改；如需增加事件接收表，应作为同一兼容增量或下一版本迁移明确记录。

回滚分为两层：

1. 代码/页面回滚：恢复上一后端和前端构建；保留已写入操作和审计。
2. 运行时回滚：通过旧版本摘要创建回退操作，由适配器执行并回报；不能删除新版本记录或伪造成功。

对象存储、适配器协议和备份恢复方案未确认前，只验证接口和状态机，不宣称完成真实主机恢复演练。

## 12. 备选方案与取舍

| 方案 | 取舍 | 结论 |
| --- | --- | --- |
| Java 后端直接 SSH/远程执行脚本 | 开发快，但扩大凭据、命令注入和网络故障边界，违背后端架构责任 | 不采用 |
| 运行时侧适配器 + Java 控制面编排 | 保持后端边界，能针对 Codex/扩展/Bash/PowerShell 做能力适配，需建设事件协议和补偿状态机 | 推荐 |
| 只提供下载 API，由用户手工安装 | 实现简单，但无法满足 Tracker 配对、失败恢复、实例状态和紧急撤回 | 不采用 |
| 把安装状态全部放 Redis | 适合短期队列，但无法满足 PostgreSQL 权威元数据、审计和历史查询 | 不采用 |

## 13. 可行性证据

- 已静态确认发布绑定只允许已发布版本，且现有跨 Feature 契约拒绝 `latest`。
- 已静态确认现有治理 Outbox/Redis Streams 可作为发布事件和安装命令的异步基础，但当前没有安装回执消费者。
- 已确认数据库规范预留安装实例、Tracker 绑定和操作表，PostgreSQL 15 可使用 `BIGINT`、部分索引、JSONB 和时间字段实现模型。
- 未验证：真实 Codex CLI/扩展安装路径、Claude OTLP 配置方式、运行时代理通信协议、对象存储访问方式和离线队列容量。上述内容必须通过最小本地适配器 PoC 或协议测试验证。

## 14. 可测试性与验证策略

| 层级 | 场景 | 可观察结果 |
| --- | --- | --- |
| 单元 | 状态转移、版本/范围/能力校验、重复事件、回退决策 | 非法状态被拒绝；重复事件不重复计账 |
| Mapper/迁移 | 三张安装表、唯一约束、当前实例查询、V8 空库迁移 | PostgreSQL 15 迁移成功；同一目标只有一个当前实例 |
| Service 集成 | 首次安装、切换成功、Tracker 失败、无旧版本失败、回退失败 | Skill/Tracker/总体状态和失败阶段一致 |
| Redis 契约 | 命令出站、事件补报、过期序列、重复事件和 Outbox 失败 | 事件按 ID 幂等；发送成功不等于安装成功 |
| 安全 | 越权安装、越权撤回、运行时停用、Session 过期 | 后端拒绝并产生审计，不泄露对象信息 |
| 前端集成/E2E | 安装实例查询、进度、部分失败、撤回结果 | 中文页面正确展示成功、失败、部分成功和人工处理 |

主要命令：

```text
mvn -f backend/pom.xml -Dtest=InstallationStateMachineTest,InstallationServiceTest,InstallationRecoveryIntegrationTest,InstallationEventContractTest test
npm --prefix frontend run test
npm --prefix frontend run build
```

真实 PostgreSQL 15 和 Redis Streams 不可用时，必须标记 `unavailable` 并保留协议级替代证据，不能写成集成验证通过。

## 15. 需求覆盖矩阵

| requirement-<semantic-name> | 方案响应 | 计划任务 | 验证方式 | 状态 |
| --- | --- | --- | --- | --- |
| `requirement-skill-distribution-installation` | 发布绑定、版本摘要和运行时能力校验后创建安装操作 | Task 2、Task 3 | Service 集成、契约测试 | covered |
| `requirement-skill-tracker-companion-installation` | Skill/Tracker 分状态，均就绪并健康后才完整 | Task 4 | 状态机和集成测试 | covered |
| `requirement-skill-installation-instance` | 按目标实例、运行时、范围和版本摘要记录安装实例 | Task 1、Task 2 | PostgreSQL 迁移、查询集成测试 | covered |
| `requirement-skill-version-switching` | 准备/确认/提交切换，旧版本保持可回退 | Task 4、Task 5 | 切换成功和并发测试 | covered |
| `requirement-skill-installation-failure-recovery` | 失败阶段留痕、旧版本恢复、离线事件幂等补报 | Task 5、Task 6 | 回退、重复事件、Redis 契约测试 | covered |
| `requirement-skill-emergency-revocation` | 先阻断新增，再逐实例撤回并汇总部分失败 | Task 7 | 权限、撤回和部分成功集成测试 | covered |

## 16. 风险、待确认事项和不覆盖项

### 风险与待确认

- 运行时适配器的通信协议、身份认证、命令签名和版本兼容方式尚未确认；没有这些事实不能进行真实主机安装验证。
- Bash/PowerShell 的实际目录、原子切换方式、进程重载方式和回退安全性需要 PoC；方案只规定行为契约。
- 适配器离线队列的容量、事件保留期、超时、重试和人工处理 SLA 未确认。
- 数据库规范 v0.6-draft 已补充 V13 运行时接入元数据、事件幂等和与 V14 运行观测的迁移边界；具体列和索引仍须在 Task 9 的 PostgreSQL 15 迁移测试中验证。
- 对象存储、制品签名/访问、备份、容量、RPO/RTO 和正式 Java 包名仍按架构基线保持待确认。

### 不覆盖项

- 不实现未验证的 Claude Code IDE 安装。
- 不把安装成功等同于运行观测健康；健康状态由安装回执和下游运行数据分别表达。
- 不实现 Agent 业务执行、运行事件采集、指标聚合、评测和发布门禁计算。

## 17. CR-022 增量设计：CLI 运行时接入安装、诊断与恢复

### 17.1 责任边界与命令

CLI 复用资产治理 Task 33 建立的 Node.js 20 + TypeScript 工程、凭据、HTTP 客户端和中文/JSON 输出。Java 后端不通过 SSH、WinRM 或任意 Shell 远程操作目标主机；所有配置写入、扩展安装、进程探测和恢复都由用户在目标主机执行 CLI 后完成。

```text
skillhub telemetry install --runtime <runtime-key> [--dry-run] [--json]
skillhub telemetry status  [--runtime <runtime-key>] [--json]
skillhub telemetry repair  --runtime <runtime-key> [--json]
```

首期 `runtime-key` 固定为 `codex-cli`、`codex-vscode`、`codex-cursor`、`codex-windsurf` 和 `claude-code-otlp`。命令只接受枚举值和显式选项，不能拼接用户输入执行任意命令。`status` 是严格只读操作；`install --dry-run` 只展示检查和预计变更。

### 17.2 适配器注册表和本地目录

```text
cli/src/adapters/
├─ adapter-registry.ts
├─ types.ts
├─ codex/codex-cli-adapter.ts
├─ codex/editor-extension-adapter.ts
└─ claude/claude-code-otlp-adapter.ts
cli/src/telemetry/
├─ collector-installer.ts
├─ integration-health.ts
└─ integration-event-spool.ts
cli/src/stores/runtime-integration-store.ts
```

`RuntimeAdapter` 固定提供 `detect`、`plan`、`install`、`status`、`repair` 和 `rollback`。注册表按运行时、版本范围、操作系统和能力选择唯一适配器；无匹配项返回 `RUNTIME_UNSUPPORTED`，不使用通用 Shell 兜底。每个适配器包内声明版本、文件摘要、支持矩阵和可写目标，安装前复核摘要。

CLI 状态目录位于用户状态目录的 `skillhub/runtime-integrations/<runtime-key>/`，只保存适配器版本、配置摘要、受管文件清单、安装前快照定位、最后自检和未上报事件；Token 保持在独立凭据文件中。状态和快照使用当前用户权限、文件锁和临时文件原子替换。

### 17.3 首期适配器行为

| 运行时 | 安装动作 | 自检和人工边界 |
| --- | --- | --- |
| Codex CLI | 安装 CLI 自带 Collector/relay；合并 `%USERPROFILE%/.codex/hooks.json` 和 `config.toml` 的带标记 OTel 配置块 | 校验 Hook、配置块、处理器摘要、relay 和服务端；Hook 信任必须由用户在 Codex 内确认 |
| VS Code/Cursor/Windsurf | 使用 CLI 包内摘要固定的 VSIX；仅调用已探测到的 `code`、`cursor`、`windsurf --install-extension <fixed-vsix> --force` | 校验扩展版本和 Collector 连通；不存在的编辑器标记未安装，不伪造成功 |
| Claude Code OTLP | 在已支持版本下合并 `~/.claude/settings.json` 的受管 `env` 配置，设置遥测启用、OTLP HTTP/JSON endpoint 和 Bearer header 引用 | 校验 JSON、配置摘要、版本和服务端鉴权；冲突的用户自定义 OTLP 配置要求人工确认，不静默覆盖 |

Codex 适配器参考 Witty 的 managed block、安装状态、摘要校验和 self-check 设计，但配置名、目录和 endpoint 使用本产品 `skillhub` 命名。编辑器命令参数由适配器内部构造。Claude Code 未通过版本/字段兼容测试时，矩阵项保持禁用。

### 17.4 原子安装和恢复状态机

```text
DETECTED -> PLANNED -> SNAPSHOTTED -> APPLYING -> VERIFYING -> ACTIVE
                                 |          |          |
                                 +----------+----------+-> ROLLING_BACK
                                                          |-> RESTORED
                                                          +-> REQUIRES_MANUAL
```

1. 检测运行时版本、目标文件、写权限、端口、SkillHub 地址和 `telemetry:write` 凭据，只记录 Token 前缀。
2. 获取本地锁并计算目标文件修改前摘要；保存最小快照和计划，不复制无关用户目录。
3. 安装 CLI 自带且摘要固定的文件，使用结构化 JSON/TOML 修改器写入受管块，再原子替换目标文件。
4. 执行自检。全部通过后保存 `ACTIVE` 状态并上报接入事件；需要 Codex Hook 信任时返回 `ACTION_REQUIRED`，不能标记健康。
5. 任一步失败时，仅当当前文件仍匹配本次写入摘要才恢复快照；发现用户并发修改时停止覆盖并进入 `REQUIRES_MANUAL`。

`repair` 先执行只读诊断，只修复缺失或摘要不匹配的受管内容；不删除用户配置。采集器、上传器和网络异常必须捕获并写本地事件 spool，不能改变目标 Agent 的退出码或阻塞业务调用。

### 17.5 服务端登记和 Vue 展示

新增兼容接口：

```text
POST /api/v1/runtime-integrations
POST /api/v1/runtime-integrations/{integrationId}/events
GET  /api/v1/runtime-integrations
GET  /api/v1/runtime-integrations/{integrationId}
```

CLI 写接口使用 `telemetry:write`，并校验账号、公司/项目/环境范围；Vue 查询继续使用 Session/CSRF 和范围 RBAC。Flyway `V13__create_runtime_integration_metadata.sql` 新增 `runtime_integration_instance` 和 `runtime_integration_event`，对 `(scope_id,runtime_key,target_key)` 和 `event_id` 建立唯一约束。实例保存适配器版本、配置摘要、安装状态、健康状态和最近上报时间，不保存 Token、用户主目录或原始配置正文。

现有安装管理页增加“Skill 安装”和“运行时接入”页签；接入列表展示运行时、目标逻辑标识、适配器版本、配置/健康状态、最后检查和失败阶段。服务端不可达时 CLI 事件进入本地待上报队列，恢复后按 `event_id` 补报；发送成功不等于本机安装成功。

### 17.6 异常、安全、兼容和回滚

- 运行时不存在、版本不支持、权限不足、端口占用、配置语法错误、用户配置冲突、扩展命令失败、服务端不可达和凭据拒绝必须使用不同错误码。
- 适配器输出、服务端事件和日志必须脱敏 Token、Bearer header、本地用户名和绝对路径；服务端只接收散列后的目标逻辑 ID。
- 重复 `install` 在配置摘要一致时返回原状态；不同版本升级必须保留旧适配器和配置快照，健康确认后才清理临时文件。
- CLI/适配器版本回退使用本地快照；服务端表和事件追加保留。后端可暂停接收新接入登记，但不能远程卸载或宣称目标主机已恢复。

### 17.7 可行性证据和验证

- 当前后端已存在 `RuntimeInstaller`/`RuntimeInstallerRegistry` 接口、安装事件接收和状态机，但没有真实主机安装器，适合保持控制面与本地执行分离。
- Witty Codex 安装器已证明 managed JSON/TOML 修改、VSIX 安装、安装状态、摘要校验、relay 自检和恢复路径可实现；Hook 信任仍需人工操作。
- 当前 V8/V9 安装表以具体 Skill 资产和版本为必填，不能承载纯遥测接入，因此新增独立 `runtime_integration_*` 表而不放宽既有不变量。
- 验证必须在临时 HOME 中覆盖幂等安装、用户并发修改、坏配置、升级失败、恢复、只读诊断和凭据脱敏；真实 Codex/编辑器/Claude Code 主机验证不可用时标记 `unavailable`。

### 17.8 CR-022 需求覆盖矩阵

| requirement-<semantic-name> | 方案响应 | 计划任务 | 验证方式 | 状态 |
| --- | --- | --- | --- | --- |
| `requirement-cli-agent-integration-installation` | 白名单适配器、Collector/密钥预配、预检、快照、原子写、自检和服务端登记 | Task 9-11、Task 14-16 | CLI 单元/集成和临时 HOME 安装测试 | covered |
| `requirement-cli-agent-integration-check` | 严格只读 `status`，区分配置、Collector、网络、凭据、spool 和健康 | Task 10、Task 12、Task 14、Task 16 | 只读副作用检查、错误码测试 | covered |
| `requirement-cli-agent-integration-recovery` | 摘要保护的快照恢复、Collector 恢复、密钥轮换和幂等事件补报 | Task 10、Task 12-16 | 故障注入、恢复和服务端幂等测试 | covered |

### 17.9 风险和不覆盖项

- Codex、VS Code/Cursor/Windsurf 和 Claude Code 的最终支持版本需要实现阶段以真实二进制验证；未验证版本不进入 ACTIVE 矩阵。
- npm 发布、CLI 签名、企业软件分发和管理员提权策略待确认；首期不自动提权。
- 不实现远程任意命令、Java 后端主机控制、未验证的 Claude Code IDE 扩展或 Agent 业务任务执行。

## 18. CR-026 增量设计：Collector 安装与真实事件接入

### 18.1 跨 Feature 边界

运行观测 Feature 拥有 `LocalCollectorServer`、本地路由、事件转换、脱敏和 spool 语义；安装恢复 Feature 只负责本地 Collector 的受管安装、密钥分发、运行时配置、进程自检和失败恢复。Java 后端继续只保存接入状态，不启动目标主机进程，也不接收本地 Collector 密钥。

安装顺序固定为：

```text
检查运行时/文件/端口
-> 创建或读取本地 Collector 密钥
-> 启动并鉴权检查 LocalCollectorServer
-> 快照并写入 Hook/OTLP/VSIX 受管配置
-> 发送无正文探针事件并确认 spool 增量
-> 保存本地状态并登记服务端
```

任一步失败都进入既有 `ROLLING_BACK`；只回退本次摘要匹配的受管内容。已有健康 Collector 为其他运行时服务时不得因单个适配器失败而停止。

### 18.2 本地密钥和配置注入

本地入口密钥由运行观测 Task 4B 的 `CollectorConfigStore` 首次创建，至少 256 bit，位于 `<user-home>/.skillhub/collector/collector.secret`。安装适配器只能通过受限读取接口取得，不得写入 `runtime-integrations.json`、接入事件、CLI 输出、服务端、日志、spool 或 VSIX 包体。

| 消费方 | 密钥使用方式 | 配置约束 |
| --- | --- | --- |
| Codex Hook | Hook handler 运行时读取 `collector.secret`，发送 `X-SkillHub-Collector-Key` | handler 不内嵌密钥；读失败时静默放弃采集并以 0 退出 |
| Codex OTLP | 受管 OTel 配置写本地 endpoint、runtime key 和本地密钥头引用/值 | 不得写 SkillHub API Token；不覆盖用户非受管 exporter |
| Claude Code OTLP | 受管 `OTEL_EXPORTER_OTLP_HEADERS` 保存本地 Collector 密钥和 runtime key | 这是唯一允许密钥值进入运行时配置的情况；settings 必须当前用户可读写，输出/快照索引只保存摘要 |
| VSIX | 扩展运行时从固定受管密钥文件读取并发送本地头 | 包体和扩展日志不含密钥；读取失败显示 Collector 未连接 |

本地密钥不是 SkillHub API Token，不能用于访问服务端。轮换采用“双密钥短窗口”：生成新密钥、原子更新受管配置、逐个探针验证、撤销旧密钥；失败则恢复旧密钥和摘要匹配的配置。首期不提供用户输入固定密钥的选项。

### 18.3 Hook、OTLP 和 VSIX 安装契约

- Codex Hook 固定发送 `POST /hook`，正文限制 1 MiB，本地请求超时 150 ms；任何 Collector 错误都不能改变 Codex 退出码。
- Codex/Claude Code OTLP 固定发送 HTTP/JSON `POST /v1/logs`，同时携带本地鉴权头和编译期白名单 runtime key；不配置 gRPC 或任意远程 endpoint。
- VS Code、Cursor、Windsurf 使用同一摘要固定 VSIX，但安装时写入各自 runtime key。扩展必须发送真实编辑器生命周期事件到 `POST /ide-event`，不再用周期 `/status` 请求冒充采集。
- VSIX 首期采集扩展会话开始/结束、文件修改计数、终端打开/关闭和任务开始/结束；只发送时间、会话/事件稳定标识、编辑器类型、语言/终端种类等结构化枚举，不发送文件名、绝对路径、代码差异、终端命令/输出、窗口标题或工作区名称。目标 API 不可用的事件显式标记能力缺失。
- 扩展使用短超时、有界内存队列和丢弃计数；本地服务不可用时不得阻塞编辑器保存、终端和任务操作，不做无限重试。

每个适配器安装完成后必须发送一条不含用户内容的探针 envelope，并通过 spool 计数或请求关联 ID确认入口可用。仅 `/health` 成功不足以将运行采集标记为健康。

### 18.4 状态、诊断和恢复

`telemetry install` 和 `telemetry repair` 可以启动或恢复受管 Collector；`telemetry status` 仍严格只读。状态至少区分：服务停止、陈旧 PID、未知端口占用、协议不兼容、鉴权不匹配、运行时配置缺失、密钥摘要不匹配、探针失败、spool 不可写和健康。

端口冲突时只允许接管通过进程标识、协议版本和密钥验证的既有 Collector；未知进程不得结束、覆盖或自动改端口。Collector 启动失败时不继续写运行时配置。进程在安装后退出时，`repair` 清理陈旧 PID/锁、启动同版本 Collector、验证探针并恢复 ACTIVE；用户并发修改配置则进入 `REQUIRES_MANUAL`。

本地安装结果上报服务端时只包含 Collector 协议版本、运行时/适配器版本、配置摘要、探针结果、失败阶段和稳定错误码。不得上传 PID、用户目录、密钥、配置正文或本地事件正文。Vue 继续展示服务端最近登记状态，并明确“服务端接入状态不等于目标主机当前进程实时状态”。

### 18.5 兼容、迁移和回滚

已有无鉴权 Hook、OTLP 或只轮询 `/status` 的 VSIX 标记 `ACTION_REQUIRED`，由 `repair` 在保存快照后升级；不得通过开放无鉴权路由维持兼容。适配器和 Collector 协议使用显式版本，主版本不兼容时阻断安装。

回滚按“禁用新事件发送 -> 恢复 Hook/OTLP/VSIX 受管快照 -> 验证 Agent/编辑器正常 -> 在没有其他运行时依赖时停止 Collector”执行。spool、checkpoint、服务端接入历史和审计保留。回滚不得删除用户非受管配置、其他扩展或其他运行时正在使用的 Collector。

### 18.6 验证和实施顺序

自动化在临时用户目录覆盖密钥权限/轮换、Hook 退出码隔离、OTLP Header、VSIX 无正文事件、安装探针、只读 status、进程恢复、未知端口、并发修改和多运行时共享 Collector。主机 PoC 至少对当前可用 Codex 与 VS Code 执行真实事件；Claude Code、Cursor、Windsurf 缺失时逐项标记 `unavailable`。

实施依赖顺序为运行观测 Task 4B -> 安装恢复 Task 14 -> Task 15 -> Task 16 -> 运行观测 Task 4C。CR-026 不改变已完成 Task 9-13 的服务端登记、离线补报和 Vue 页签契约。
