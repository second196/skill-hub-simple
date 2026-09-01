# 文档阶段

阶段入口：`documentation`

## 1. 阶段目标

把已经确认的 Work Item、Feature、需求、方案、测试、验证、发布检查、规范、入口页和交叉链接沉淀到正确的 `docs/` 位置。

文档阶段不替用户确认未经采纳的 AI 推断，也不自动改变需求、方案或实现。

## 2. 阶段流程

```text
确认文档范围和来源
-> 读取文档规范和目录地图
-> 判断文档落位
-> 检查版本、frontmatter、入口和交叉链接
-> 只修改授权文档
-> 检查 diff 和引用
-> 输出依据和未验证项
-> 停止
```

## 3. 写入条件

必须同时满足：

1. 结论来源明确；
2. 内容已被用户采纳或用户明确要求记录；
3. 落位符合仓库文档目录地图；
4. 已区分基线、变更、草稿和未决事项；
5. 用户已授权本轮要修改的文件。

## 4. 文档落位

优先检查真实目录和 `AGENTS.md` 路由，不凭经验猜路径：

- Feature 需求：`docs/product-development/features/feature-<featureId>/requirement.md`；
- Work Item 总览：`docs/product-development/work-items/work-<workItemId>/index.md`；
- Work Item 分解：同目录 `decomposition.md`；
- Feature 方案：同目录 `design.md`；
- Feature 实施计划：同目录 `implementation-plan.md`；
- Feature 流程状态和基线变更：`.product-development/features/feature-<featureId>/state.md`、`change-log.md`；
- Feature 验证和发布：同目录 `verification.md`、`release-check.md`；
- 产品内部设计、架构和规范：以仓库目录地图为准。

多 Feature 文档维护必须保持总览与子 Feature 一致：总览记录 Feature、依赖、跨 Feature 契约和整体验收；子 Feature 只记录自己的需求、方案、计划和验证。新增、删除、合并或迁移 Feature 先走 Work Item 变更管理。

## 5. 执行检查

1. 检查文档类型、来源、版本和状态；
2. 检查标题、frontmatter、术语和链接；
3. 检查入口页和交叉引用；
4. 检查没有把草稿或推断写成已确认结论；
5. 只在授权范围内写入；
6. 检查 diff 没有无关改动；
7. 记录未执行的链接、格式或渲染验证。

如果当前修改会影响团队后续写法，同步更新对应规范；只影响单篇文档时，不扩散为全局规则。

## 6. 阶段停止

文档更新和检查完成后停止。不得自动进入其它阶段。
