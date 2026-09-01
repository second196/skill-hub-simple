# Feature 状态与上下文恢复

## 1. 目标

`state.md` 是跨轮次、跨会话和上下文压缩后的最小恢复包。它不替代需求、方案和计划，也不保存源码全文。

## 2. 固定格式

```markdown
# Feature 状态

feature: feature-<featureId>
work_item: work-<workItemId>
decomposition_status: confirmed
artifact_dir: docs/product-development/features/feature-<featureId>
control_dir: .product-development/features/feature-<featureId>
current_phase: requirement
requirement_status: draft
design_status: not-started
implementation_status: not-started
review_status: not-requested
verification_status: not-requested
release_check_status: not-requested
requirement_version: v1
design_version: n/a
plan_version: n/a
active_change: none

## Work Item 边界
- work_item: work-<workItemId>
- scope_decision: single-feature | multi-feature
- allowed_requirements: requirement-...
- forbidden_requirements: requirement-...
- dependency_features: feature-...

## 已确认决策
- [DEC-001] ...

## 未决问题
- [ ] ...

## 源码事实地图
- `path:Symbol`：入口、调用关系、数据或状态、相关测试、事实依据。

## 任务进度
- [ ] Task 1：pending

## 验证状态
- pass: none
- fail: none
- not-run: ...
- unavailable: none

## 本轮边界
- allowed: ...
- forbidden: ...

## 阻塞与交接
- none
```

## 3. 更新时机

以下时机必须更新：

- 阶段开始和结束；
- 用户确认需求、方案或风险；
- 计划任务完成、失败或阻塞；
- 发现源码事实与计划冲突；
- 验证结果变化；
- 上下文即将切换、压缩或交给其它 Agent。

每轮只改受影响章节，保持短小。已被替代的决策移入控制目录的 `change-log.md`，不要删除正式产物中的历史版本。

## 4. 恢复协议

恢复任务时按顺序读取：

1. `AGENTS.md` 和协作规则；
2. `state.md`；
3. 当前阶段正式产物；
4. 控制目录中 `active_change` 对应的 `change-log.md` 条目；
5. 当前 diff；
6. 仅与当前任务相关的源码符号。

读取后先复述当前阶段、已确认决策、未决问题、任务进度和允许范围，再继续执行。

## 5. 状态写入规则

状态文件是索引和控制面，不是工作日志全文。每次更新只保留当前有效结论，并为历史变化引用 `CR-*` 或证据路径。至少维护：

- 当前阶段和阶段状态；
- 当前需求/设计/计划版本及其确认状态；
- 当前活动变更、任务和阻塞；
- 与当前任务有关的源码事实及来源；
- 已执行、未执行、无法执行的验证；
- 下一次恢复所需的最小读取范围。

如果状态文件与正式产物或工作区 diff 冲突，以可验证的工作区事实为准，暂停并修正状态；不得默默选择其中一个继续。

## 6. Work Item 恢复

如果 `work_item` 不为空，恢复时还必须读取 Work Item 的 `index.md`、`decomposition.md` 和总需求 `state.md`，核对当前 Feature 是否仍在已确认的 Feature 清单中、需求映射是否完整、跨 Feature 契约是否有变化。Work Item 总状态不能由单个 Feature 的状态推断。

## 7. 两类记录的边界

- `state.md`：高频更新的当前状态、任务进度、阻塞、验证结果和恢复索引；
- `change-log.md`：低频追加的、会改变正式需求/方案/计划基线的变更及审批状态；
- 普通任务完成、上下文切换和临时命令结果只更新 `state.md`，不追加 `change-log.md`；
- 正式产物中的历史版本通过版本号、状态和变更编号追溯，不在文档目录中维护执行日志。
