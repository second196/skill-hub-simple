# Feature 状态
feature: feature-skill-hub
artifact_dir: docs/product-development/features/feature-skill-hub
control_dir: .product-development/features/feature-skill-hub
current_phase: requirement
requirement_status: draft
design_status: not-started
implementation_status: not-started
review_status: not-requested
verification_status: not-requested
release_check_status: not-requested
requirement_version: v0.6
design_version: n/a
plan_version: n/a
active_change: CR-004

## 已确认决策
- [DEC-001] SKILL HUB 定位为公司内部集中管理 Skill 的平台，覆盖生命周期、发布、下线、多版本、观测和持续进化闭环。
- [DEC-002] 从平台安装 Skill 时必须同时安装或配置 skill-tracker，用于收集运行数据。
- [DEC-003] Skill 进化版本支持从平台获取；更新成功后本地旧 Skill 停用，最新下载版本启用，并保留切换记录。
- [DEC-004] 需求范围以调研报告已确认的资产治理、Tracker/运行观测、评测和进化闭环为依据，不把推测的技术实现写成需求事实。
- [DEC-005] 首期采用公司私有化部署，仅服务公司内部员工；不做 SaaS，不做多租户。
- [DEC-006] 首批接入形态按 Witty Skill Insight：Codex CLI Hook + 原生 OTel Logs 及 VS Code/Cursor/Windsurf VSIX，Claude Code 使用 OTLP；安装主机脚本沿用 Bash 和 PowerShell 路径。
- [DEC-007] 运行数据按 Witty 基线最小化和脱敏；默认不读 Codex `transcript_path`，敏感字段和本地路径脱敏，Token 数值保留；评测采用 LLM Judge，人工修正优先于机器分数。
- [DEC-008] 发布治理按 iflytek 基线执行安全扫描、团队审核和提升至全局的二次审核；高风险候选不得绕过人工审核。
- [DEC-009] iflytek SkillHub 中与目标功能重合的能力采用模块级复用；未覆盖的能力仅作为领域参考；不采用整体直接分叉。
- [DEC-010] 默认仅允许低风险白名单候选自动发布；自动发布主体及规则版本必须审计。人工申请人与审批人分离，团队审核人与全局提升审核人不得重复；默认灰度 10%、观察 24 小时且至少 30 次调用，达到错误率、有效评分或安全阈值时自动回退并上报。
- [DEC-011] Skill 普通下线仅阻断新增安装，已安装实例继续运行并上报；高危安全问题可紧急撤回存量实例。安装或启用新版本失败时保持或回退旧版本；无可用旧版本时不得启用未确认版本，并将失败和回退结果上报 SKILL HUB。
- [DEC-012] Skill 制品、运行证据、评测/分析报告和审计数据永久保留，不设置自动过期或普通删除。

## 未决问题
- 暂无本轮用户决策遗留的需求待确认事项。

## 源码事实地图
- 暂无业务源码；当前 Feature 仅维护调研和需求文档。
- `docs/research/skill-hub-research.md`：调研结论、能力边界、待决策项和证据索引。
- `../witty-skill-insight/docs/qa.md`、`../witty-skill-insight/docs/user-guide/observability/index.md`：Codex 接入、脱敏和估算边界。
- `../witty-skill-insight/docs/user-guide/evaluation/evaluators.md`、`../witty-skill-insight/src/lib/engine/experiment/detail-agg.ts`：LLM Judge 和人工修正优先规则。

## 任务进度
- [x] 读取项目约定、需求文档规范和需求阶段流程。
- [x] 对照调研报告重新梳理 Feature 定位和需求边界。
- [x] 更新 `requirement.md` 至 v0.4 草稿。
- [x] 初始化 Feature 控制状态和变更记录。
- [x] 同步调研决策：私有化范围、Witty 接入/数据/评测基线和 iflytek 分级发布治理。
- [x] 固化 iflytek 模块复用边界、发布默认参数、失败自动回退上报和数据永久保留规则。
- [x] 根据需求评审修订发布主体、基线比较、回退可靠性、紧急撤回和运行时兼容性边界。
- [ ] 获取人工需求评审确认。

## 验证状态
- pass: 文档结构、REQ 编号、决策交叉一致性、路径约定、自动发布主体、基线比较、自动回退上报、紧急撤回、灰度默认值、永久保留和待确认事项静态检查通过。
- fail: none
- not-run: 项目专项校验脚本尚未执行。
- unavailable: 当前目录不是 Git 工作树，无法提供 Git diff 或基线比较。

## 本轮边界
- allowed: `docs/research/skill-hub-research.md`、`docs/product-development/features/feature-skill-hub/requirement.md`、`.product-development/features/feature-skill-hub/state.md`、`.product-development/features/feature-skill-hub/change-log.md`。
- forbidden: `design.md`、`implementation-plan.md`、业务源码、接口设计和技术架构实现。

## 阻塞与交接
- 当前阶段在人工需求评审确认前停止，不进入 design 或 implementation。
