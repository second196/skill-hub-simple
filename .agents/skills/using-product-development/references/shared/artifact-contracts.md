# 阶段产物契约

| 阶段 | 必需输入 | 默认产物 | 写入确认 | 完成检查 |
| --- | --- | --- | --- | --- |
| `requirement` | 原始需求、需求规范 | 正式 `requirement.md`；控制 `state.md`、`change-log.md` | 需求口径和路径 | 原子需求、范围、验收、待确认项 |
| `design` | 已确认需求、源码事实 | 正式 `design.md`、`implementation-plan.md`；控制 `state.md`、`change-log.md` | 方案与计划 | 双产物、覆盖矩阵、可行性、验证任务 |
| `implementation` | 需求、方案、计划、状态 | 源码、测试、配置；控制 `state.md`，必要时 `change-log.md` | 实现范围和风险 | 逐任务结果、验证、无计划外偏离 |
| `review` | 评审对象和标准 | 对话结论或授权记录 | 仅记录文件时 | 问题优先、证据、严重级别 |
| `verification` | 验证对象和真实命令 | 证据或授权 `verification.md` | 仅记录文件时 | 通过/失败/未执行/无法执行 |
| `release-check` | 变更、验证、回滚信息 | 对话结论或授权记录 | 仅记录文件时 | 风险、灰度、监控、回滚、人工项 |
| `documentation` | 已确认结论、文档规范 | 指定文档 | 明确文件范围 | 路径、frontmatter、链接、diff |

方案双产物是一个事务：任一文件缺失或与另一文件版本不一致时，`design` 阶段不得标记完成。

## 2. 版本和确认状态

正式产物必须能看出版本与状态，至少在标题或 frontmatter 中记录：

```text
feature: feature-<featureId>
status: draft | confirmed | superseded
version: vN
```

`implementation-plan.md` 必须引用具体的 `design` 版本和需求版本。实现阶段只能执行 `confirmed` 的方案和计划；微调或冲突必须通过控制目录中的 `change-log.md` 形成新的变更记录，不得直接覆盖已确认内容。
