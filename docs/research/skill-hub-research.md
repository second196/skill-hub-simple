---
title: SKILL HUB 调研报告
description: 基于 Agent-Insight/Witty Skill Insight、alibaba/skill-up 和 iflytek/skillhub 的源码与公开文档形成的产品与技术调研。
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-01
sourceType: manual
---

# SKILL HUB 调研报告

## 1. 摘要

### 1.1 结论

建议将 SKILL HUB 定位为公司内部的 **Skill 资产治理、运行观测、质量评测与持续优化平台**。它应管理 Skill 从导入到发布的完整生命周期，并将每次发布与可验证的运行和评测证据关联。

现有开源项目没有一个单独覆盖目标产品的全部范围：

| 项目 | 最强能力 | 在目标产品中的定位 |
| --- | --- | --- |
| Agent-Insight / Witty Skill Insight | 多运行时采集、Agent 轨迹、Skill 评测与优化工作台 | Tracker、轨迹归一化、部分评测/优化的参考实现 |
| alibaba/skill-up | 声明式、多引擎、可复跑的 Skill 评测和 CI 门禁 | 受控评测 Runner 的参考/集成对象 |
| iflytek/skillhub | 私有 Registry、版本、namespace、审核、RBAC、CLI 分发 | 推荐作为资产注册与发布治理控制面的候选基础 |

因此，优先方案不是从零重建全部能力，也不是直接把任一项目当作完整产品：在公司私有化部署范围内，对 iflytek SkillHub 已有且与目标功能重合的能力采用模块级复用；对其未覆盖的 Tracker、遥测、评测和自进化能力，仅作为领域参考并独立补齐；不采用直接分叉。评测和运行证据仍需接入统一的优化与发布决策闭环。

### 1.2 核心判断

1. Agent 执行链路和 Skill 执行轨迹应来自同一原始事件流，但应提供不同查询和聚合视图。
2. 只记录 Skill 调用无法还原 Agent 的任务、子 Agent、工具和总资源消耗；只记录 Agent 链路而没有可靠 Skill 版本边界，也不能准确归因到 Skill。
3. “准确率”不是天然遥测指标。没有真值、业务回执、脚本断言或批准的判定器时，只能报告判定通过率、用户反馈通过率或覆盖率。
4. iflytek SkillHub 可降低 Registry 和治理控制面的建设成本，但它当前的可观测性是平台自身的请求/Scanner 链路，不是目标所需的 Agent 生产轨迹；其安全扫描也不是动态评测和自优化。

## 2. 调研范围与方法

### 2.1 调研对象和版本基线

| 编号 | 对象 | 调研基线 | 主要材料 |
| --- | --- | --- | --- |
| R1 | `../agent-insight` | `373ec92f1e606328a64ea6b09476959796c5d819`，2026-08-27 | README、采集器、OTel Adapter、Prisma schema、评测/优化文档和源码 |
| R2 | `../witty-skill-insight` | `373ec92f1e606328a64ea6b09476959796c5d819`，2026-08-27 | README、接入脚本、Tracker、Skill 工作台源码 |
| R3 | `github.com/alibaba/skill-up` | `35d439cd7e007c333783bf7ad2cabd79a2cbfc7d` | README、固定提交文件树 |
| R4 | `github.com/iflytek/skillhub` | `ac42c2346cf0ea58b3952dfe0e2cef9eb8558e9c` | README、产品方向、系统架构、生命周期、Scanner、可观测性文档和源码目录 |

R1 的远程为 `openeuler/agent-insight.git`，R2 的远程为 `openeuler/witty-skill-insight.git`；二者当前提交相同。本报告将其作为同一源码快照的两个远程名称，而非两个独立的技术基线。

### 2.2 结论口径

本报告中的“已实现”仅指上述基线的源码或公开文档能够支持的事实。“建议”“目标产品”“推荐架构”属于面向 SKILL HUB 的方案，不应被误读为现有项目功能。未能从材料确认的范围，会明确列为限制或待决项。

## 3. 产品背景与目标

### 3.1 业务问题

组织在多个 Agent runtime、模型和 Skill 并存后，遇到的核心问题不是缺少一个 Skill 文件，而是缺少可治理、可比较和可演进的资产闭环。

| 问题 | 造成的影响 | 目标能力 |
| --- | --- | --- |
| Skill 来源和版本分散 | 无法确认线上安装版本、变更来源和回滚对象 | 不可变制品、版本、发布渠道和安装实例 |
| Agent 执行黑盒 | 无法定位失败是由模型、工具、子 Agent 还是 Skill 导致 | 端到端 Agent 链路和 Skill 调用归属 |
| 指标不可比较 | 任务、模型、版本和环境混合，比较结论不可信 | 统一指标口径、比较条件和样本证据 |
| 离线评测与生产脱节 | 离线通过无法解释线上效果，线上失败无法沉淀回归案例 | 评测资产、运行证据和回归闭环 |
| 自动修改缺少治理 | 修改直接覆盖生产版本，易带来安全和质量回归 | 候选、复测、审批、灰度和回滚 |

### 3.2 产品定位与边界

SKILL HUB 是公司内部员工使用的 Skill 控制面和质量闭环平台，首期采用公司私有化部署，不提供 SaaS，不将多租户作为首期范围。平台包含三类相互协作的能力：

| 产品面 | 目标 | 不承担的职责 |
| --- | --- | --- |
| 资产与发布控制面 | 管理资产、版本、权限、渠道、安装和审计 | 不替代 Agent runtime |
| Tracker 与观测数据面 | 采集、脱敏、缓冲、上传和关联运行证据 | 不替代全部通用日志/APM 功能 |
| 评测与优化面 | 管理可复现实验、质量问题、候选版本和决策证据 | 不允许无约束覆盖生产版本 |

核心闭环为：

```text
导入/创建 -> 静态检查 -> 版本化 -> 受控评测 -> 审批发布
-> 安装 Skill + Tracker -> 运行上报 -> 观测和归因
-> 优化候选 + 回归案例 -> 复测 -> 灰度 -> 发布或回滚
```

本产品不负责实现 Agent 本身；不把没有真值的模型输出称为准确率；不在未经策略允许的情况下自动发布高风险变更。

## 4. 现有项目事实分析

### 4.1 Agent-Insight / Witty Skill Insight

该源码基线定位为自托管开源 AgentOps 平台。README 说明其覆盖观测、评测、诊断和优化，并将 Skill 作为一等公民。

| 已证实能力 | 事实依据 | 对目标产品的价值 |
| --- | --- | --- |
| 多运行时接入 | README 列举 OpenCode、Claude Code、Qwen Code、Hermes、Trae IDE、JiuwenSwarm、LangGraph、LlamaIndex；源码含 Codex 采集器。 | 为 Tracker 的“标准协议 + runtime Adapter”提供参考。 |
| OTLP 归一化 | 接收 OTLP，解码、规范化为 `OtelTraceEvent[]`，通过 Adapter 聚合为 `ExecutionRecord`。 | 可参考统一事件与 runtime 语义映射。 |
| Codex/Claude 采集 | Codex 有 Hook、Relay、安装器和 VS Code 扩展；Claude 有 OTEL 解码、spool、聚合、上下文补传和归因保护。 | Codex、Claude Code 可作为首批接入运行时。 |
| Agent 树和指标 | Codex Adapter 处理 root/sub-agent、LLM、tool、MCP、Skill，输出 Token、时延、工具错误和 `invokedSkills`。 | 证明 Agent 链路与 Skill 归属可从同一事件流构建。 |
| Skill 工作台 | 有版本、静态/动态评测、优化候选、复测、发布 API；发布创建新版本。 | 可参考候选、质量阻断、diff、来源证据和版本化原则。 |

限制：其公开定位是自托管平台；本次材料未证明其已有面向多组织/项目/环境的中心化资产控制面，也未证明已统一实现目标产品要求的四维趋势和可比较性规则。不同 runtime 的采集方式和字段完整度不同，不能将某一 Adapter 的能力泛化到所有 Agent。

### 4.2 alibaba/skill-up

skill-up 是以 CLI 和 CI 为中心的 Skill 评估与演进工具。

| 已证实能力 | 对目标产品的价值 |
| --- | --- |
| `eval.yaml` 与 `cases/*.yaml` 声明环境、引擎、模型和案例 | 评测套件可以作为版本化资产。 |
| 内置 Qoder CLI、Claude Code、Codex，支持 custom engine | 同一 Skill 可在多个运行时做受控比较。 |
| rule-based、script、agent_judge | 规则、领域脚本和模型裁判可并存。 |
| JSON/JUnit/HTML 报告、`evals.json` 导入、GitHub Action | 能产生机器可读的发布门禁证据。 |
| skill-upper 读取失败、修复/扩展评测、重跑 | 可辅助作者形成回归案例和迭代。 |

限制：该项目没有被材料证明为资产目录、生产 Tracker、组织治理、运行趋势、灰度或回滚平台。合理用法是由 SKILL HUB 调度隔离 Runner 执行 skill-up，并保存固定环境和结构化报告；不应将其作为 Hub 后端替代品。

### 4.3 iflytek SkillHub

iflytek SkillHub 定位为单实例、namespace 隔离的企业私有 Agent Skill Registry。它的产品重点是发布、发现、治理和分发，而不是多租户 AgentOps。

| 已实现能力 | 事实依据 |
| --- | --- |
| Skill 注册、版本和分发 | `SKILL.md` 包、语义版本、`beta`/`stable` 标签、`latest`、下载、CLI 安装。 |
| 搜索和社区 | PostgreSQL 全文搜索、namespace/下载/评分/新近度筛选、收藏、评分和下载统计。 |
| namespace 治理 | 全局与团队空间、成员、Owner/Admin/Member、团队审核、提升至全局的二次审核。 |
| 身份和审计 | OAuth2、CLI Device Flow、API Token、平台/namespace RBAC、关键治理操作审计。 |
| 兼容与交付 | 自有 CLI、ClawHub Registry API 兼容、常见 Skill 目录约定、Docker Compose、Kubernetes、Helm。 |
| 安全扫描 | 发布进入 `SCANNING`，Redis Stream 异步触发 scanner，写入安全审计，再进入审核或 `SCAN_FAILED`。 |
| 平台可观测性 | request ID、OTel/Micrometer、内部 HTTP/Redis Stream 上下文传播、日志关联。 |

重要限制：其可观测性设计是 SkillHub 服务本身的请求、异步任务和 Scanner 调用链，不是用户 Agent 的生产执行轨迹。Scanner 是发布前静态安全链路，不是多引擎效果评测、Skill 调用归因或自优化系统。其产品方向还明确以 namespace 而非 tenant 作为隔离边界；这一模型可作为公司内部项目、环境和授权范围治理的参考，不等同于 SaaS 多租户。

## 5. 能力对比与复用判断

### 5.1 目标能力对照

| 目标能力 | Agent-Insight/Witty | skill-up | iflytek SkillHub | 建议 |
| --- | --- | --- | --- | --- |
| 导入、标准格式、制品存储 | 部分 | 不适用 | 强 | 以 iflytek SkillHub 为主。 |
| 版本、标签、发布、审核、审计 | 部分 | 不适用 | 强 | 以 iflytek SkillHub 为主并扩展证据关联。 |
| CLI 分发和兼容目录 | 部分 | skill-upper 安装 | 强 | 以 iflytek SkillHub 为主，补安装实例。 |
| Codex/Claude Tracker | 强 | 不适用 | 未证实 | 参考/移植 Agent-Insight 适配器。 |
| Agent/Skill 双轨迹 | 强 | 不适用 | 未证实 | 新增统一事件、摄入和查询域。 |
| Token、时延、错误分析 | 强 | 评测运行可输出报告 | 未证实 | 以 Tracker 数据面建设。 |
| 静态安全扫描 | 部分 | 不适用 | 强 | 复用 iflytek Scanner 发布门。 |
| 多引擎受控评测 | 部分 | 强 | 未证实 | 以 skill-up Runner 为主。 |
| 优化候选与回归 | 强 | 辅助迭代 | 未证实 | 参考 Witty，接入评测与运行证据。 |
| 组织/权限/发布治理 | 未证实完整控制面 | 不适用 | 强，但单实例 namespace 模型 | 以 iflytek 为主；多租户另行设计。 |

### 5.2 是否基于 iflytek SkillHub 优化

结论是“**功能重合部分采用模块级复用，其余仅作领域参考**”。其 Registry、版本、namespace、审核、RBAC、CLI、审计和私有化部署能力与目标产品重合，可作为模块级复用对象；其缺少的 Agent Tracker、运行轨迹、动态评测、优化候选和运行证据灰度决策能力，则作为领域参考并由目标产品补齐。这能避免直接分叉带来的整体升级和维护责任，同时保留既有治理能力。

不应将其直接作为完整目标产品上线，原因是它缺少 Agent Tracker、Skill 调用版本归属、运行 trace 摄入、多维指标、动态评测、优化候选和基于运行证据的灰度决策。由于目标首期是公司私有化内部平台，不再把 SaaS tenant、跨租户数据驻留和跨租户密钥隔离作为首期需求；namespace 仍不能简单等同租户。

Apache-2.0 为二次开发提供许可路径；实际采用前仍应完成 NOTICE、第三方依赖、商标和供应链审查，本报告不构成法律意见。

## 6. 目标产品的核心数据与行为要求

### 6.1 轨迹模型

原始事件必须保留 task/session、Agent、sub-agent、LLM、tool、MCP 和 Skill 调用的关系。基于同一事件流生成三种视图：

```text
AgentTrace        任务树、总 Token/时延/错误/结果
SkillInvocation   Skill 版本、触发、归因 Token/时延/结果
MetricAggregate   按 Skill/版本/框架/模型/任务/环境/时间聚合
```

每个可识别 Skill 调用至少关联 `skill_id`、`version_digest`、`invocation_id`、`trace_id`、`parent_span_id`、触发方式和 Tracker 版本。无可靠归属时应标记“版本未知”，而不是关联到最新版本。

### 6.2 发布和质量闭环

发布必须将版本、静态检查、受控评测、审批、灰度范围和运行观察建立关联。优化只能创建候选版本，不能覆盖已发布版本。权限变化、外部写操作、质量退化、新 runtime 兼容性变化和高风险 Skill 必须经过人工审批。

### 6.3 指标与可比较性

平台应区分调用量、判定通过率、准确率、Token、成本、时延、错误率、触发率和判定覆盖率。每个指标显示数据范围、时间范围、分子、分母和计算条件。比较版本或模型时，需固定或展示 SkillVersion、runtime/版本、模型/参数、工具环境、任务集或标签、判定器和样本数；条件不一致时标记不可直接比较。

### 6.4 已确认决策及其证据

| 决策 | 当前口径 | 证据或限制 |
| --- | --- | --- |
| 部署范围 | 首期为公司私有化部署，仅服务公司内部员工；不做 SaaS，不做多租户。 | 用户确认；与 Witty 的自托管实践和 iflytek 的企业私有 Registry 方向一致。 |
| 首批运行时接入 | Codex 采用 CLI Hook + 原生 OTel Logs 双通道，并支持 VS Code、Cursor、Windsurf VSIX；Claude Code 按 Witty 的 OTLP 上报方式接入。安装和主机脚本沿用 Witty 已有 Bash/PowerShell 路径。 | `../witty-skill-insight/docs/qa.md` Q23、`../witty-skill-insight/docs/user-guide/observability/index.md` Codex 章节、`../witty-skill-insight/README.md` 支持平台和安装章节。Witty 未在本报告材料中给出独立的 Claude Code IDE 扩展，因此首批不额外承诺该形态。 |
| 运行数据 | 默认不读取 Codex `transcript_path`，完整 prompt/transcript 和完整代码片段不作为默认采集内容；工具输入输出、FileEdit、Terminal 和摘要仅在对应适配器暴露且策略允许时采集。上传前递归脱敏 API Key、token、secret、password 等敏感赋值以及 Windows、UNC、`/Users/...`、`/home/...` 路径；Token 用量保留数值。适用采集器的文本内容最长 2000 字符，实验性可见 transcript 估算必须显式开启并标记为估算。 | Witty `../witty-skill-insight/docs/qa.md` Q23、`../witty-skill-insight/docs/user-guide/observability/index.md` Codex 章节、`../witty-skill-insight/scripts/qwencode-collector/privacy.mjs` 及脱敏脚本。各 runtime 的字段完整度仍以适配器实际能力为准。 |
| 评测真值 | 沿用 Witty 当前口径：以 LLM Judge 生成结构化机器分数；存在人工修正时，人工分数优先于机器分数。业务回执和脚本断言尚未在 Witty 材料中形成更高优先级规则，不在本期擅自补充。 | Witty `../witty-skill-insight/docs/user-guide/evaluation/evaluators.md`、`../witty-skill-insight/docs/user-guide/skills/evaluation/use-case-analysis.md`、`../witty-skill-insight/src/lib/engine/experiment/detail-agg.ts`。 |
| 发布治理 | 沿用 iflytek 的发布前安全扫描和分级审核基线：扫描失败不得发布，团队空间审核后，提升到全局需二次审核。SKILL HUB 默认仅允许低风险白名单候选自动发布，白名单要求无高危/严重安全问题、无权限或外部写操作变化、无新运行时兼容性变化，至少完成 30 个有效评测案例且回归与有效评分不低于同条件稳定基线；自动发布主体、命中的规则版本和门禁证据必须审计。人工申请人与审批人必须分离，团队审核人与全局提升审核人不得重复。默认灰度为符合条件实例的 10%（向上取整且至少 1 个实例）、观察 24 小时且至少 30 次调用；错误率较基线增加 2 个百分点，或基线大于 0 时相对增加 20%，或基线为 0 时灰度错误率达到 2%，有效评分下降 5 个百分点、或出现高危/严重问题时自动回退并上报。 | iflytek 材料支撑扫描和分级审核流程；未发现数值默认参数，因此上述数值为本项目合理默认值。 |
| 安装失败与回退 | Skill 下线仅阻断新增安装，已安装实例继续运行并持续上报状态；新版本或 Tracker 安装、配置、可用性确认、停用旧版本或启用新版本失败时，旧版本保持启用，或在已停用时自动回退到此前可用的 Skill/Tracker 版本。无可用旧版本时不得启用未确认的新版本，必须标记不可用并要求人工处理；失败阶段、回退结果和当前状态必须上报 SKILL HUB，离线时进入待上报队列。高危安全问题可触发紧急撤回，强制停用授权范围内的存量实例。 | 用户确认；与既有安装实例和 Tracker 状态要求衔接。 |
| 数据保留 | Skill 制品、运行证据、评测/分析报告和审计数据永久保留，不设置自动过期或普通删除。 | 用户确认；覆盖此前 Witty 用量统计 365 天规则在 SKILL HUB 数据上的适用范围。 |

## 7. 推荐架构

### 7.1 总体原则

采用“三平面、模块化优先”的架构：控制面初期为模块化单体；遥测摄入和评测执行从第一期独立部署。控制面有强事务边界，摄入和评测有不同吞吐、安全和失败模型，不能全部塞进 Registry 的同步请求流程。

```text
Browser -> Control Plane: Asset / Release / Policy / RBAC / Audit
Agent Host -> Tracker -> Ingest Gateway -> Queue -> Trace Pipeline
                                      |-> Analytics Store / Artifact Store
Control Plane -> Eval Orchestrator -> Isolated Runner -> skill-up
```

### 7.2 推荐选型

| 层 | 推荐选型 | 选择理由 |
| --- | --- | --- |
| 资产与发布控制面 | 基于 iflytek SkillHub 的 Java 21/Spring Boot 模块化单体，React 19 控制台 | 已有 Registry、发布、RBAC、审计、存储和部署能力。 |
| 事务元数据 | PostgreSQL | 适合版本、发布、权限、策略和审计等关系型事务。 |
| 制品和原始证据 | S3/MinIO | 保存 Skill 包、报告、脱敏 trace bundle、日志和 diff。 |
| Tracker | Node.js 适配 Codex/Claude CLI Hook，Python 适配 Python Agent；统一 OTLP/HTTP 和本地 spool | 适配多语言 runtime 生态，避免单语言强行重写。 |
| 摄入网关 | OTel Collector 或 Go Gateway | 负责认证、限流、批处理、schema 校验、脱敏和入队。 |
| 事件与任务 | 初期 Redis Streams/BullMQ 或 NATS JetStream | 处理上传、聚合、扫描、评测和通知；有容量证据后再评估 Kafka。 |
| Trace 分析 | ClickHouse | 支撑大量时序和多维度聚合，不将所有 span 分析压在事务库。 |
| 评测执行 | Kubernetes Job 或等价隔离容器 Runner | 不在 Hub API 进程执行不受信 Skill、Shell 或脚本判定器。 |
| 平台自身观测 | OpenTelemetry metrics/logs/traces | 监控摄入丢失、积压、Runner 和发布失败。 |

### 7.3 安全边界

Tracker 以本地缓冲和数据最小化原则采集；Gateway 进行凭据、schema、大小和速率校验；原始内容、制品和报告根据策略脱敏、加密和保留。Runner 使用一次性凭据、受限网络、文件系统和资源配额。发布服务只接收带有必需检查、报告和审批的候选版本。应使用短期 token 或 mTLS、内容哈希/签名、组织级密钥隔离和不可篡改审计。

## 8. 实施策略与决策项

### 8.1 建议阶段

| 阶段 | 交付重点 | 成功判据 |
| --- | --- | --- |
| P0 | 验证 iflytek 控制面扩展点；定义统一事件和 Codex/Claude 能力矩阵 | 任务可关联到不可变 Skill 版本，且缺失数据显式可见。 |
| P1 | 资产、版本、审核、发布、安装实例、Codex/Claude Tracker、Gateway | 发布版本能安装并上报最小 Agent/Skill 链路。 |
| P2 | Trace 查询、双视图、对象存储证据、ClickHouse 聚合、指标字典 | 指标能钻取到证据，比较条件可见。 |
| P3 | skill-up Runner、评测资产、结构化报告、发布门禁 | 两版本可在固定环境和案例集下复跑比较。 |
| P4 | Finding、候选优化、回归、审批、灰度和回滚 | 版本变更具备 diff、评测、审批和线上证据。 |

### 8.2 待决问题

暂无本轮用户决策遗留的需求待确认事项。iflytek 未提供的自动发布和灰度数值已按本报告第 6.4 节固化为 SKILL HUB 默认参数；模块边界、策略配置和实现细节在方案阶段展开。

## 9. 证据索引

| 对象 | 关键证据 |
| --- | --- |
| Agent-Insight/Witty | `../witty-skill-insight/README.md`、`../witty-skill-insight/docs/qa.md`、`../witty-skill-insight/docs/user-guide/observability/index.md`、`../witty-skill-insight/src/lib/ingest/otel/adapters/codex.ts`、`../witty-skill-insight/src/lib/ingest/claude-otel/`、`../witty-skill-insight/src/lib/skill-workbench/`、`../witty-skill-insight/skills/skill-optimizer/` |
| skill-up | 固定提交 README、`skills/skill-upper/`、`action.yml`、`docs/` 文件树 |
| iflytek SkillHub | `README.md`、`docs/00-product-direction.md`、`docs/01-system-architecture.md`、`docs/02-domain-model.md`、`docs/14-skill-lifecycle.md`、`docs/security-scanning.md`、`docs/observability-developer-guide.md` |
