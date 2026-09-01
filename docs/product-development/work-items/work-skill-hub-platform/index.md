---
title: SKILL HUB 平台 Work Item
description: 从 Skill 资产登记到运行观测、质量评测和发布回退的公司内部闭环需求分解
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-01
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

## 4. 整体验收路径

1. 有权限的用户可以登记或导入 Skill，生成不可变版本并在授权范围内发布。
2. 运行时可以获取已发布版本和对应 Tracker，安装状态可查询；更新失败时旧版本保持或恢复可用。
3. 已支持运行时的调用可以关联到 Agent 链路、Skill 版本和 Tracker 版本；网络中断时状态事件可补报且不重复记账。
4. 指定版本可以在固定评测条件下复测，结果、问题和候选差异可追溯到证据。
5. 发布或扩大灰度前必须满足扫描、评测、审核和风险门禁；触发回退条件时自动回退并保留完整证据。

## 5. 非目标

- 不实现 Agent 本身的任务执行，不替换 Agent runtime。
- 不替代公司通用日志、APM 或统一观测平台。
- 不在需求阶段规定 API 字段、数据表、组件选型、部署脚本或开发任务。
- 没有真实业务判定依据时，不把模型评分、通过率或用户反馈通过率称为业务准确率。

## 6. 状态

四个 Feature 的拆分及依赖关系、首期默认运行时、可配置发布策略、分层数据保留规则和正式需求文档写入均已确认。
