---
title: Skill 获取安装与失败回退设计
description: Skill 与 Tracker 的运行时获取、安装、切换、回退和紧急撤回技术方案
audience:
  - product-development
owner: product-development
status: active
lastReviewed: 2026-09-02
sourceType: manual
---

# 方案：Skill 获取安装与失败回退

> 设计版本：v1。需求基线、方案和实施计划已由用户确认，可作为后续实现依据。

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
- 不使用 OAuth2、统一单点登录或长期 API Token；继续使用账户密码和服务端 Session。

## 2. 需求依据与版本

| 依据 | 版本/范围 | 用途 |
| --- | --- | --- |
| `docs/product-development/features/feature-skill-installation-recovery/requirement.md` | v1，用户已确认 | 六条功能需求、运行时默认矩阵和验收条件 |
| `docs/product-development/work-items/work-skill-hub-platform/decomposition.md` | confirmed | Feature 边界、依赖和语义化需求 ID |
| `docs/product-development/work-items/work-skill-hub-platform/index.md` | confirmed | 资产发布、安装、观测之间的跨 Feature 契约 |
| `docs/research/skill-hub-research.md` | 3.2、4.3、6.1、6.2、6.4、7.3、8.1 | 运行时矩阵、Tracker、CLI 分发和失败恢复调研事实 |

设计版本和实施计划版本均为 `v1-draft`。安装域所有跨 Feature 关联使用 `version_digest`，不使用名称、默认版本或 `latest` 推断。

## 3. 架构和实施基线

| 类型 | 文档 | 版本/状态 | 本方案约束 |
| --- | --- | --- | --- |
| 后端架构 | `docs/product-development/architecture/backend-architecture.md` | v0.4-draft，已确认作为输入 | JDK 8、Spring Boot 2.7.18、Spring MVC 5.3.31、模块化单体、Controller/Service/Mapper 分层；实际安装属于运行时适配器边界 |
| 前端架构 | `docs/product-development/architecture/frontend-architecture.md` | v0.3-draft，已确认作为输入 | Vue 3、TypeScript、Vite、Vue Router、Pinia；前端只发起请求和展示状态，不执行安装 |
| 实施规范入口 | `docs/product-development/standards/implementation/index.md` | v0.3-draft，已确认作为输入 | 按影响范围执行 Java、Vue、TypeScript 和数据库规范 |
| Java 规范 | `docs/product-development/standards/implementation/java-best-practices.md` | active，嵩山版依据 | 明确 DTO/VO/DO、分层、异常、事务和返回值语义 |
| 数据库规范 | `docs/product-development/standards/implementation/database-design.md` | v0.5-draft，已确认作为输入 | PostgreSQL 15、Flyway、追加式操作记录、逻辑跨域关联、幂等和分页 |
| 组件规范 | `docs/product-development/standards/implementation/component-standard.md` | active | Vue 组件单一职责、显式加载/部分成功/失败状态和可访问性 |
| TypeScript 规范 | `docs/product-development/standards/implementation/typescript-best-practices.md` | active | 外部响应先校验、精确类型、异步错误可见 |

已识别的基线事实：后端架构明确不负责实际 Skill 安装，前端架构明确不在浏览器执行安装；因此本方案把运行时安装代理/适配器定义为外部执行边界，把控制面编排、持久化和事件契约保留在本 Feature。适配器具体语言、通信协议、对象存储产品、超时重试数值、容量、备份和 RPO/RTO 仍待实施基线确认。

## 4. 源码现状和影响范围

当前仓库已有资产与发布治理实现，安装域尚未实现。相关事实如下：

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
- 数据库规范 v0.5-draft 只给出安装表的关联要求，事件接收幂等字段和完整列定义需要在实施前补充为数据库增量基线。
- 对象存储、制品签名/访问、备份、容量、RPO/RTO 和正式 Java 包名仍按架构基线保持待确认。

### 不覆盖项

- 不实现未验证的 Claude Code IDE 安装。
- 不把安装成功等同于运行观测健康；健康状态由安装回执和下游运行数据分别表达。
- 不实现 Agent 业务执行、运行事件采集、指标聚合、评测和发布门禁计算。
