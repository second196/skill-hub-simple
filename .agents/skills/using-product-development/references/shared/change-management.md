# 变更与基线管理

## 1. 基线

用户确认后的 `requirement.md`、`design.md` 和 `implementation-plan.md` 是版本化基线。后续消息默认是增量变更，不表示授权重写全部历史产物。

## 2. 变更分类

| 类型 | 默认影响 |
| --- | --- |
| `implementation-fix` | 代码和测试；不改需求与方案 |
| `implementation-preference` | 局部实现；行为不变时不改上游文档 |
| `requirement-clarification` | 先记录澄清，再判断受影响产物 |
| `scope-change` | 停止并评估需求、方案、计划和验证影响 |
| `design-change` | 用户确认后更新方案和计划 |
| `documentation-correction` | 只改指定文档 |

## 3. 控制目录中的 change-log.md

`change-log.md` 位于：

```text
.product-development/features/feature-<featureId>/change-log.md
```

它不是任务日志。普通实现偏好、任务完成、上下文恢复和单次验证结果只写入 `state.md`；只有影响需求、设计、实施计划或风险接受的变更才追加记录。

每次基线变更只追加一条：

```markdown
## CR-<序号>

- 日期：
- 用户原话：
- 类型：
- 原因：
- 影响需求：
- 影响设计：
- 影响任务：
- 代码范围：
- 审批状态：pending | approved | rejected
- 验证状态：not-run | pass | fail | unavailable
- 替代关系：none | supersedes DEC/CR
```

## 4. 冲突处理

新请求与历史基线冲突时：

1. 不立即覆盖旧文档。
2. 指出冲突的需求 ID、设计章节或任务。
3. 判断属于实现修复、偏好调整还是需求/设计变化。
4. 说明最小修改范围和下游影响。
5. 等待用户确认后更新对应基线版本。

没有改变行为、范围或验收标准的实现微调，不得反向修改正式需求、设计或实施计划，也不得追加基线变更日志。
