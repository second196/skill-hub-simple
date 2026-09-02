# 设计阶段基线文档

## 1. 目的

设计阶段不能只依据当前需求和源码。前后端架构边界、技术选型、实施约束和团队编码标准必须先有可追溯的基线文档，设计方案和实施计划必须引用它们的版本。

这些基线文档与 `AGENTS.md` 的“阶段 × 子领域”专项路由是两类信息：专项路由决定还要读取哪些规范；本文件定义设计阶段必须存在的产品级架构和实施基线。不能因为 `AGENTS.md` 没有列出它们就跳过检查。

## 2. 默认位置和最低集合

默认从以下相对路径检查：

```text
docs/product-development/architecture/
  backend-architecture.md       # 涉及后端时必需
  frontend-architecture.md      # 涉及前端时必需

docs/product-development/standards/implementation/
  index.md
  <本 Feature 涉及的具体实施标准>.md
```

`standards/implementation/index.md` 是实施标准入口，必须存在且不能是空文件。`architecture/index.md` 如果存在则必须非空，但不是架构基线的强制文件；`backend-architecture.md`、`frontend-architecture.md` 是否必需，依据当前 Feature 的真实影响范围判断；全新系统或前后端均受影响时两者都必需。只涉及前端的 Feature 不因无后端需求而阻塞，反之亦然。

实施标准的具体文件由 `standards/implementation/index.md` 和当前 Feature 共同确定。例如：

- 涉及 UI、组件或交互，读取 `component-standard.md`；
- 涉及数据库、表结构、迁移或持久化，读取 `database-design.md`；
- 涉及 Java 或 TypeScript，读取对应的最佳实践和代码规范；
- 其它语言、框架、部署或测试标准，读取索引中与本 Feature 匹配的文档。

不能按文件名相似度猜测替代文档。索引引用了不存在的标准文件时，视为基线缺失。

## 3. 设计阶段检查顺序

1. 从已确认的 `requirement.md`、源码事实地图和 Feature 范围判断影响层：`frontend`、`backend`、`database`、`infrastructure`、`documentation` 等。
2. 检查架构目录和所有相关层架构文档；若存在架构入口则一并检查；检查实施标准目录、入口和当前 Feature 涉及的具体标准。
3. 检查文档不是空文件，版本、状态、适用范围和维护责任明确，并记录实际读取路径。
4. 将架构选型、实施标准、文档版本和设计约束写入 `state.md` 的事实地图，并在 `design.md` 中建立基线引用。
5. 基线缺失、为空、过期、互相冲突或无法判断适用范围时，停止正式设计，不得用 AI 推断补齐后继续。

推荐使用：

```text
python <skill-root>/scripts/check_design_baselines.py \
  --repo-root <repository-root> \
  --layers frontend backend \
  --standards component-standard.md database-design.md typescript-best-practices.md
```

命令中的 `--layers` 和 `--standards` 必须由当前 Feature 的影响范围决定，不能机械地对所有项目要求全部标准。

## 4. 基线缺失时的处理

发现缺失时，向用户展示明确清单：

| 类型 | 路径 | 缺失原因 | 影响 | 处理选择 |
| --- | --- | --- | --- | --- |
| 架构 | `docs/product-development/architecture/...` | 不存在、为空或冲突 | 设计边界和选型无法确认 | 用户创建 / Skill 引导创建 |
| 实施标准 | `docs/product-development/standards/implementation/...` | 不存在、为空或未覆盖 | 计划和实现规范无法确认 | 用户创建 / Skill 引导创建 |

用户可以自行补齐文档后重新进入 `design`。如果用户选择由 Skill 引导，先逐项询问事实和决策，再展示文档草稿；没有明确写入授权前只输出草稿，不创建正式基线文件。

获得写入授权后，Skill 可以只创建缺失的基线文档，不能顺带创建 `design.md`、`implementation-plan.md` 或代码。新文档先标记为 `draft`，必须由用户确认内容、适用范围和版本后才能作为设计输入。架构或实施标准存在不确定项时，继续保持 `needs-confirmation`，设计阶段仍然阻塞。

## 5. 引导创建内容

### 架构入口

至少收集：适用产品和版本、架构范围、前后端边界、部署拓扑、关键依赖、数据流、外部系统、非功能目标、已确认选型、未决决策和维护责任。

### 后端架构

至少收集：运行时和框架、模块分层、接口边界、数据访问和事务、异步任务、错误处理、鉴权、日志与监控、性能约束、兼容策略、部署方式和回滚约束。

### 前端架构

至少收集：框架和构建方式、路由、页面和组件边界、状态管理、数据请求、权限、错误与加载状态、组件库、浏览器兼容、可访问性、性能、测试入口和发布方式。

### 实施标准入口和具体标准

至少收集：适用语言和框架、目录和命名、格式化与静态检查、组件和数据库约束、测试要求、提交与评审要求、禁止事项、例外申请方式、标准版本和维护责任。

来源没有说明的内容必须写为“来源未明确”或“待确认”，不能由 Skill 自行决定技术选型。

## 6. 设计产物要求

`design.md` 至少包含“架构和实施基线”章节，列出：

- 使用的架构文档路径和版本；
- 使用的实施标准路径和版本；
- 设计如何遵循这些基线；
- 发现的冲突、例外和人工确认；
- 基线缺失或未验证时的剩余风险。

`implementation-plan.md` 的每个 Slice/Task 必须引用适用的实施标准，并把标准要求转成可执行的检查或测试。设计不能引用不存在、空白或尚未确认的基线。
