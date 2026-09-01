# 研发产物与流程状态落位

## 1. 查找顺序

Feature 文档和流程状态的目录不是固定事实。创建或定位 Feature 时按以下顺序确认：

1. 读取当前目录及父目录的 `AGENTS.md`；
2. 使用 `AGENTS.md` 指定的产品文档目录和命名规则；
3. 检查仓库中已存在的 Feature 目录，沿用其结构、命名和 frontmatter；
4. 只有没有专项规则和既有结构时，才使用以下默认路径：

```text
docs/product-development/features/feature-<featureId>/
```

流程控制目录默认与正式产物目录分离：

```text
.product-development/features/feature-<featureId>/
```

正式产物目录和流程控制目录都找不到可靠落位时，先向用户确认，不创建猜测目录。

## 2. 默认文件

正式 Feature 文件为：

```text
index.md
requirement.md
design.md
implementation-plan.md
verification.md
release-check.md
```

流程控制文件为：

```text
.product-development/features/feature-<featureId>/
  state.md
  change-log.md
```

按阶段创建文件。需求阶段创建或更新正式 `requirement.md`，并初始化流程控制目录中的 `state.md` 和 `change-log.md`；不提前创建设计和实施计划。方案阶段必须同时创建正式 `design.md` 与 `implementation-plan.md`，除非用户明确要求只写方案。

## 3. 单 Feature 约束

- 一个 `featureId` 只能对应一个 Feature 目录；
- `featureId` 必须包含能够说明需求对象和动作的词，使用稳定的短横线格式；
- 推荐格式：`feature-<对象>-<动作>`，例如 `feature-order-export`、`feature-user-login`、`feature-message-retry`；
- 如果同时保留外部任务号，必须附带需求短语，例如 `feature-order-export-WEBCAPP-10234`，不能只使用任务号；
- 禁止使用 `feature-001`、`feature-REQ-001`、`feature-task-001`、`feature-new-feature` 或只有编号的名称；
- 没有足够信息提炼需求含义时，先询问用户，不创建无意义目录；
- 同一需求涉及多个独立交付物时，先拆成多个 Feature，并在 `index.md` 记录关系；
- 不把多个 Feature 的状态、计划或变更记录混在同一个文件中；
- 不把流程状态和过程变更日志放入正式 Feature 文档目录；
- 所有路径必须使用仓库真实存在的文件和符号，不能用示例路径冒充事实。
