---
title: SKILL HUB 平台 Work Item
description: 从 Skill 资产登记到运行观测、质量评测和发布回退的公司内部闭环需求分解
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-03
sourceType: manual
---

# SKILL HUB 平台 Work Item

## 1. 目标

基于 `docs/research/skill-hub-research.md`，提炼公司内部 Skill 资产管理、运行观测、质量评测和持续优化的可验收需求，为后续各 Feature 的正式需求、方案和实现提供输入。

首期范围是公司私有化部署，仅服务公司内部员工，不建设 SaaS 多租户能力，不替代 Agent runtime、通用日志或 APM 平台。

## 2. Feature 清单

| Feature | 目标 | 独立交付物 | 依赖 |
| --- | --- | --- | --- |
| `feature-skill-asset-release-governance` | 统一登记、版本化、授权和发布 Skill | 可追溯的 Skill 资产、不可变版本、发布门禁和审计记录 | 无；评测证据由 `feature-skill-evaluation-evolution` 提供 |
| `feature-skill-installation-recovery` | 让运行时获取 Skill 和 Tracker，并可安全更新、回退和撤回 | 可查询的安装实例、切换记录、失败恢复和撤回结果 | `feature-skill-asset-release-governance` |
| `feature-skill-runtime-observability` | 采集 Agent/Skill 运行证据并形成双向追踪和指标 | 标准化运行事件、Skill 调用轨迹、指标聚合和上报状态 | `feature-skill-installation-recovery` |
| `feature-skill-evaluation-evolution` | 管理评测资产，形成进化候选并支持复测、灰度和质量回退 | 可复现评测报告、问题归因、候选版本和回归证据 | `feature-skill-asset-release-governance`、`feature-skill-runtime-observability` |

## 3. 跨 Feature 契约

- 资产治理产出不可变的 Skill 版本标识；安装、运行观测和评测均必须引用该标识，不能用“最新版本”补齐缺失归属。
- 安装 Feature 同时管理 Skill 与 `skill-tracker` 的状态，并向运行观测 Feature 发送安装、切换、回退和撤回事件。
- 运行观测 Feature 产出原始 Trace、Skill 调用记录和指标证据；评测 Feature 只能使用带明确条件的证据形成结论。
- 评测 Feature 产出静态检查、评测、回归和人工审核证据；资产治理 Feature 使用这些证据执行发布门禁。
- 任何跨 Feature 关联缺失时，系统必须显式标记缺失原因，不得构造 Trace、版本归属或质量结论。

### 3.1 SkillHub CLI 增量契约

- CLI 是本地客户端交付工具，不是新的业务治理边界；安装恢复、运行观测和资产治理仍分别拥有自己的状态、权限和审计责任。
- 浏览器控制台继续使用账户密码、服务端 Session 和 CSRF；CLI 使用 API Token Bearer，不引入 OAuth2、统一单点登录或 CLI Device Flow。
- API Token 至少按 `skill:read`、`skill:publish`、`telemetry:write` 和 `token:manage` 划分作用域。运行数据上报必须使用 `telemetry:write`，Skill 包上传和发布申请必须使用 `skill:publish`。
- CLI 不直接访问 PostgreSQL、Redis 或服务端制品目录，不使用 S3；所有资产、版本、运行事件、审核和审计数据必须通过 SkillHub 服务端接口处理。
- 运行数据链路为“目标 Agent 事件 -> 插件/Hook/Collector -> 本地 spool -> OTLP 上报 -> 服务端接收与标准化 -> 服务端 spool -> Session 聚合 -> AgentTrace/SkillInvocation/指标”。
- Skill 上传链路为“本地目录或 ZIP -> CLI 校验与打包 -> 服务端二次校验 -> 资产/版本创建 -> 扫描和门禁 -> 草稿或审核申请”；上传成功不等于版本已发布。
- 首期运行时默认承诺 Codex CLI、VS Code/Cursor/Windsurf 扩展和 Claude Code OTLP；适配器矩阵可配置，其他运行时必须经过验证后才可标记为支持。
- Skill 上传默认创建草稿版本；只有显式提交审核且满足现有发布门禁时，版本才可进入候选或已发布流程。CLI 不得绕过审批人分离和紧急撤回规则。

### 3.2 CR-026 本地运行事件入口契约

- 运行观测 Feature 拥有只绑定 `127.0.0.1:43191` 的本地 Collector、`/health`、`/hook`、`/v1/logs`、`/ide-event` 路由、事件转换、数据最小化和本地 spool；安装恢复 Feature 拥有进程安装/恢复、本地密钥分发、Hook/OTLP/VSIX 配置和安装探针。
- `/hook`、`/v1/logs` 和 `/ide-event` 必须使用至少 256 bit 的本地受管密钥；该密钥不是 CLI API Token，不得上传服务端或进入普通状态、日志、事件和输出。`/health` 只返回最小存活和协议信息。
- 请求正文默认最大 1 MiB且先限制再解析；只接受固定运行时、来源、结构化字段，不采集原始 prompt、transcript、代码、工具输入输出、终端正文和绝对路径。
- 本地 Collector 只追加运行观测 Task 3 的 spool，不访问远程 SkillHub、PostgreSQL 或 Redis；远程上传继续使用 `telemetry:write` Bearer Token 和既有补报链路。
- VS Code/Cursor/Windsurf 扩展必须发送真实的无正文结构化编辑器事件，不能用 `/status` 轮询冒充运行采集；Collector 故障不得改变 Hook、Agent 或编辑器业务操作结果。
- CR-026 实施顺序为运行观测 Task 4B -> 安装恢复 Task 14-16 -> 运行观测 Task 4C -> 运行观测 Task 5-10；已完成的 Task 4A 和安装 Task 9-13 不回退或重编号。

## 4. 整体验收路径

1. 有权限的用户可以通过控制台或 CLI 登记、导入 Skill，生成不可变版本并在授权范围内发布。
2. 运行时可以通过控制台或 CLI 获取已发布版本和对应 Tracker，安装状态可查询；更新失败时旧版本保持或恢复可用。
3. 已支持运行时的调用可以通过 CLI 安装的插件、Hook 或 OTLP 配置关联到 Agent 链路、Skill 版本和 Tracker 版本；网络中断时事件可补报且不重复记账。
4. CLI 上传的目录和 ZIP 必须经过本地及服务端双重校验；失败包不生成可发布版本，重复上传不生成重复资产或版本。
5. 指定版本可以在固定评测条件下复测，结果、问题和候选差异可追溯到证据。
6. 发布或扩大灰度前必须满足扫描、评测、审核和风险门禁；触发回退条件时自动回退并保留完整证据。

## 5. 非目标

- 不实现 Agent 本身的任务执行，不替换 Agent runtime。
- 不替代公司通用日志、APM 或统一观测平台。
- 不在需求阶段规定 API 字段、数据表、组件选型、部署脚本或开发任务。
- 没有真实业务判定依据时，不把模型评分、通过率或用户反馈通过率称为业务准确率。
- CLI 不执行 Agent 业务任务，不替代目标 Agent 平台，不直接修改服务端数据库或制品存储。
- CLI 不负责绕过审核直接发布 Skill，不负责 S3、微服务拆分、OAuth2、统一单点登录或设备授权流程。

## 6. 状态

四个 Feature 的拆分及依赖关系、首期默认运行时、可配置发布策略、分层数据保留规则和正式需求文档写入均已确认。
