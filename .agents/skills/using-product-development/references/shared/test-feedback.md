# 测试与文档反馈契约

## 1. 目标

测试既要证明代码或配置产生了预期行为，也要暴露需求、设计和实施计划之间的不一致。测试结果不能单独决定哪份文档有错，必须沿着以下链路核对：

```text
requirement-* -> design.md -> implementation-plan.md -> test expectation -> actual behavior
```

## 2. 测试义务

测试强度与变更风险匹配：

| 变更类型 | 最低验证要求 |
| --- | --- |
| 有用户可观察行为的代码变更 | 自动化单元、集成、契约或端到端测试，至少选择能证明该行为的层级 |
| 配置、构建、发布或迁移变更 | 真实配置/构建/迁移演练，必要时增加回滚验证 |
| 纯文档或 Skill 变更 | 结构、链接、引用、格式和可执行脚本检查；不强行编写无意义的功能测试 |
| 无法自动化的行为 | 最小复现、人工演练或其他替代证据，并记录原因和剩余风险 |

不能用“已运行测试”替代测试与需求的对应关系，也不能因为测试难写就直接标记通过。

## 3. 验证记录

每个 Task 或 Slice 至少维护一条验证记录：

```markdown
validation: validation-<meaningful-outcome>
requirement: requirement-<semantic-name>
slice: slice-<meaningful-result>
task: task-<meaningful-change>
kind: unit | integration | contract | e2e | manual | static | build | migration
test_path: tests/... 或替代证据路径
command: <真实命令>
expected: <可观察预期>
actual: <实际结果>
status: pass | fail | not-run | unavailable
evidence: <日志、报告或复现路径>
```

`validation`、`slice` 和 `task` 也应表达含义。需求标识只能使用语义化 `requirement-*`，禁止 `REQ-*`、纯数字或 `requirement-001`。

`last_test_result` 只能记录 `not-run`、`pass`、`fail` 或 `unavailable`。`feedback_status` 只能记录 `none`、`open`、`awaiting-confirmation`、`resolved` 或 `accepted-risk`。当 `feedback_status` 不是 `none` 时，必须同时记录反馈标识、来源、失败分类和受影响的 `requirement-*`；`awaiting-confirmation` 不能作为阶段通过条件。

## 4. 执行顺序

对有代码行为的 Task，优先执行：

```text
准备测试或最小复现
-> 确认失败原因符合预期
-> 修改最小代码
-> 执行定向验证
-> 执行 Slice 回归验证
-> 检查需求、设计、计划和实际行为是否一致
```

不能先修改全部代码，最后只用一次整体测试推断每项需求都正确。无法先写测试时，必须记录替代验证方式。

## 5. 失败归因

测试失败时先保留失败证据，再按预期来源判断：

| 现象 | 分类 | 动作 |
| --- | --- | --- |
| 代码没有实现已确认设计 | `implementation-defect` | 回到当前 implementation Task 修复并回归 |
| 设计不能实现已确认需求 | `design-defect` | 停止实施，回到 design，更新设计基线 |
| 计划遗漏文件、依赖、顺序或验证 | `plan-defect` | 停止实施，修订 implementation-plan.md 并重新确认 |
| 需求或验收口径发生变化 | `requirement-change` | 回到 requirement，升级需求基线并重新确认 |
| 断言、夹具或测试前置条件错误 | `test-defect` | 修正测试，重新执行，不改变需求标准 |
| 依赖、环境、权限或基础设施不可用 | `environment-failure` | 标记 `unavailable`，记录替代检查和风险，不标记 `pass` |
| 间歇性失败或超时 | `flaky-or-timeout` | 先按失败处理，定位原因后才能重新通过 |

不能通过降低断言、删除测试或修改需求来掩盖实现缺陷。分类不确定且会改变设计或需求时，必须暂停并请求人工确认。

## 6. 文档反馈记录

发现测试与文档不一致时，在控制目录的 `state.md` 记录当前反馈；影响正式基线时追加 `change-log.md`：

```markdown
feedback: feedback-<meaningful-finding>
source: validation-<meaningful-outcome>
classification: implementation-defect | design-defect | plan-defect | requirement-change | test-defect | environment-failure | flaky-or-timeout
affected_requirements: requirement-...
affected_artifacts: requirement.md | design.md | implementation-plan.md | code | test
baseline_versions: requirement=vN, design=vN, plan=vN
decision: <下一步动作>
approval: pending | approved | rejected | not-needed
```

只改变代码实现时更新 `state.md`；改变需求、设计、计划、验收或风险接受时，必须经过对应阶段和人工确认门。

## 7. 完成条件

一个 Task 或 Slice 只有在以下条件都满足时才能完成：

- 有与 `requirement-*` 对应的验证记录；
- 关键自动化测试或替代验证通过；
- 定向验证和受影响回归验证已执行，或明确记录 `not-run`/`unavailable` 及原因；
- 需求、设计、计划、测试预期和实际行为一致；
- 所有失败、环境问题和文档反馈均已处理或获人工风险接受。

实现阶段的验证是快速反馈；独立 `verification` 阶段必须重新审查目标、证据和风险，不能只复述实现者的测试结果。
