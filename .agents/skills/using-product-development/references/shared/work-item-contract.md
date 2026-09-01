# Work Item 与交付层级契约

## 1. 层级

```text
Work Item -> Feature -> requirement-* -> Slice -> Task
```

- Work Item 是一次完整研发目标，可以包含一个或多个 Feature。
- Feature 必须能够独立理解、设计、验收和交付。
- `requirement-*` 只描述一个用户可验证行为。
- Slice 是可运行的最小纵向切片。
- Task 是一次可执行、可验证的修改。

## 2. 总需求控制记录

多 Feature Work Item 必须有总需求控制记录，至少包含：

```text
work_item_id
scope_decision
complexity
decomposition_status
feature_ids
feature_dependencies
requirement_mapping
cross_feature_contracts
overall_acceptance
confirmed_by
confirmed_at
```

简单单 Feature 任务也必须记录分解结果，但可使用轻量格式。

## 3. 交付边界

总需求层维护目标、Feature 关系、跨 Feature 约束、整体验收和汇总状态。Feature 层分别维护 `requirement.md`、`design.md`、`implementation-plan.md`、`verification.md` 和 `release-check.md`。

跨 Feature 存在数据、接口、状态或错误处理契约时，必须在总需求层记录提供方、消费方、责任边界、兼容规则和集成验证方式。

Work Item 控制状态至少使用以下字段：

```text
work_item: work-<workItemId>
scope_decision: single-feature | multi-feature | needs-confirmation
decomposition_status: draft | confirmed | superseded | blocked | needs-confirmation
feature_ids: feature-...
current_phase: requirement | design | implementation | review | verification | release-check | documentation
```

`decomposition.md` 与 Work Item 控制目录中的 `state.md` 必须保持 `scope_decision`、`decomposition_status` 和 Feature 清单一致。

## 4. 状态与确认

分解、需求、方案、计划和整体验收都必须分别记录状态：

```text
draft | confirmed | superseded | blocked | needs-confirmation
```

用户确认必须对应具体范围和版本，不能用一次局部回复推断整个 Work Item 已确认。
