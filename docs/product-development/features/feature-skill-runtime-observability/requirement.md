---
title: Skill 运行观测与证据
description: 多运行时运行事件采集、Skill 调用归属、Trace 视图和可比较指标需求
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-01
sourceType: manual
---

# Skill 运行观测与证据

## 1. 版本修订记录

| 版本 | 日期 | 修订人 | 修订说明 |
| --- | --- | --- | --- |
| v1 | 2026-09-01 |  | 根据调研报告形成运行证据和数据治理初稿；运行时适配器和采集策略支持配置。 |

## 2. 需求来源

来源：`docs/research/skill-hub-research.md` 第 1.2、4.1、5.1、6.1、6.3、6.4、7.1、7.2 和 7.3 节，以及用户对首期运行时范围和数据策略可配置的确认。

## 3. 功能描述

### 3.1 功能目标与范围

平台应从已支持的 Agent runtime 获取运行事件，识别 Agent 执行链路和 Skill 调用，关联不可变 Skill 版本与 Tracker 版本，提供双向 Trace 视图、指标聚合和数据上报状态。首期能力以安装 Feature 确认的 Codex CLI/IDE 扩展和 Claude Code OTLP 适配为默认范围，运行时能力矩阵和采集策略可配置。

本 Feature 不替代通用日志或 APM，不负责 Agent 执行，不在缺失原始数据时推测事件或归属，也不负责发布决策。

### 3.2 原子需求清单

#### `requirement-agent-runtime-event-collection`

Tracker 应尽可能采集任务或会话、Agent、子 Agent、模型、工具、MCP 和 Skill 调用事件。运行时不提供的事件类型必须明确标记缺失，不得用推测值填充。

验收条件：已支持运行时能产生可查询的事件；不支持的事件显示能力缺失而非虚构值。

#### `requirement-skill-invocation-version-attribution`

可识别的 Skill 调用应关联 Skill、不可变版本标识、调用标识、Trace 标识、父 Span、触发方式和 Tracker 版本。无法可靠归属版本时必须标记“版本未知”，不得自动绑定最新版本。

验收条件：用户能从调用记录确认实际运行版本；关联不完整时能看到缺失原因。

#### `requirement-tracker-buffered-upload`

网络或平台暂不可用时，Tracker 应保留待上报的运行数据和安装、切换、回退、撤回等状态事件；恢复后继续上报。系统应展示成功、待上报、失败、丢弃、策略拒绝、超限和格式非法状态，重复重试不得生成重复状态记录。

验收条件：服务不可用期间不会被标记为成功；恢复后状态能补报并保持唯一。

#### `requirement-trace-dual-view`

平台应从同一原始事件流提供 AgentTrace 和 SkillInvocation 两种视图，并支持二者双向定位。指标、问题、评测结论和发布决策应能回溯到原始 Trace、Skill 版本、Tracker 版本和采集时间；关联缺失时显示原因。

验收条件：用户能从一次 Skill 调用定位 Agent 链路，也能从 Agent 链路定位调用及版本。

#### `requirement-skill-observability-metrics`

平台应按 Skill、版本、运行时、模型、任务标签、项目、环境和时间窗口查看调用量、判定通过率、可用时准确率、Token、成本、延迟、错误率、触发率和判定覆盖率。每项指标必须展示数据范围、时间范围、分子、分母和计算条件。

验收条件：用户能查看指标定义和筛选条件；没有真实判定依据时不显示为业务准确率。

#### `requirement-observability-comparison-validity`

比较不同版本或运行条件时，平台必须固定或展示 Skill 版本、运行时、模型参数、工具环境、任务集、判定器和样本数。条件不一致、样本不足或缺少真值时，结果必须标记不可直接比较。

验收条件：比较结果包含条件和样本信息；不可比数据不会生成误导性结论。

#### `requirement-runtime-data-minimization`

默认不采集完整 transcript、prompt 和代码片段。工具输入输出、FileEdit、Terminal 和摘要仅在适配器暴露且策略允许时采集；上传前必须脱敏 API Key、token、secret、password 等凭据以及 Windows、UNC、`/Users/...`、`/home/...` 等本地路径，策略允许的文本内容最长 2000 字符。实验性 transcript 估算必须由用户显式开启并标记为估算。

验收条件：受保护字段不会按普通运行内容上传；被脱敏、拒绝或截断的数据状态可识别。

#### `requirement-platform-observability`

平台自身应能观测数据接入、队列、分析、评测 Runner 和发布失败，并与用户 Agent 运行观测分开呈现。

验收条件：平台管理员能定位平台链路失败和用户运行失败的边界。

### 3.3 可配置项与默认值

| 配置项 | 默认值 | 调整约束 |
| --- | --- | --- |
| 首期运行时 | Codex CLI、VS Code/Cursor/Windsurf 扩展、Claude Code OTLP | 运行时和适配器可按组织/项目/环境启停 |
| 采集事件 | 任务/会话、Agent、子 Agent、模型、工具、MCP、Skill 调用 | 不支持的字段标记缺失 |
| 完整 transcript/prompt/代码采集 | 关闭 | 开启需明确策略并标记风险或估算 |
| 单条文本采集上限 | 2000 字符 | 只能通过治理策略调整并审计 |
| 原始运行事件保留 | 365 天 | 由资产治理 Feature 的数据策略统一管理 |

### 3.4 异常与边界

- runtime 没有提供的字段不得通过其他事件或最新版本推断。
- 事件无法关联到 Skill 版本时保留事件并标记未知，不丢弃或错误归属。
- 脱敏失败、凭据命中或超过策略上限时，内容应被拒绝或截断并记录原因。
- 平台自身指标不能替代 Agent 运行指标，二者必须分开查询和展示。

## 4. 非功能性需求

| 类型 | 要求 | 说明 |
| --- | --- | --- |
| 数据安全 | 默认最小化采集并在上传前脱敏 | 采集策略调整必须授权和审计 |
| 可追溯性 | 事件、版本、Trace、Tracker 和指标可关联 | 无法关联时显式标记 |
| 可靠性 | 离线缓冲和恢复补报 | 重试不重复记账 |
| 可比较性 | 指标必须带计算条件和样本信息 | 不满足条件时标记不可比 |
| 兼容性 | 运行时能力矩阵可配置 | 运行时差异不能被隐藏 |

## 5. 需求评审记录

暂无人工评审记录。
