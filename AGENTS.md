# AGENTS.md

## 仓库启动入口

本文件是本仓库的启动入口，只负责提供当前产品特有的项目约定和专项路由。

## 项目约定

本仓库特有的项目级约定，AI 必须在执行任务时遵守：

- 仓库内文档、规范和交付说明中的路径优先使用相对路径。
- 协作中形成、采纳或修正文档写作规范建议时，必须同步更新对应文档，避免只修改单篇文档而让规范继续失真。

### 架构与技术栈约定

- 后端采用 JDK 8、Spring Boot 2.7.18 模块化单体、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5 和 Maven 3.8.8；PostgreSQL JDBC 42.2.27、Flyway 8.5.13、Redis Streams 6.2.14、OpenTelemetry Java 1.32.0、Micrometer 1.10.13、Docker Compose 2.24.6、Kubernetes 1.28.15 和 Helm 3.14.4 均按 JDK 8 兼容基线确定。浏览器只支持账户密码登录和服务端会话，CLI 使用 Bearer API Token。首期运行事件和聚合结果使用 PostgreSQL 15 权威存储，Redis Streams 只传递异步聚合通知。后端代码、目录结构、分层边界和工程约束以 [后端架构基线](docs/product-development/architecture/backend-architecture.md) 为准，并必须符合组织批准版本的《阿里巴巴 Java 开发手册》嵩山版及 [Java 开发最佳实践](docs/product-development/standards/implementation/java-best-practices.md)。
- 前端采用 Vue 3、TypeScript、Vite、Vue Router 和 Pinia；前端目录结构、页面/组件边界、状态、路由和工程约束以 [前端架构基线](docs/product-development/architecture/frontend-architecture.md) 和 [Vue 组件开发规范](docs/product-development/standards/implementation/component-standard.md) 为准。
- SkillHub CLI 采用 Node.js 20、TypeScript 5.4、npm、`cac`、`fflate`、`zod` 和结构化 YAML 解析器；CLI 只在本地主机执行白名单适配器动作，通过服务端 API 处理 Skill 上传和运行数据，不直接访问 PostgreSQL、Redis 或服务端制品目录。
- iflytek SkillHub 的 React 19 仅作为控制台技术参考；本产品前端继续采用已确认的 Vue 3，不因参考项目的实现语言改变前端选型。
- 数据库采用 PostgreSQL 15、MyBatis/MyBatis-Plus 和 Flyway；表结构、索引、迁移、保留和安全约束以 [SKILL HUB 数据库设计规范](docs/product-development/standards/implementation/database-design.md) 为准。
- TypeScript 类型和注释分别遵循 [TypeScript 最佳实践规范](docs/product-development/standards/implementation/typescript-best-practices.md) 和 [TypeScript 文档注释规范](docs/product-development/standards/implementation/typescript-doc-style-guide.md)。
- 具体组件库、分析存储、对象存储、消息队列和部署方式必须在对应实施标准或 Feature 设计中确认，不得自行假定。
- 设计阶段必须先检查架构基线和实施标准基线；基线缺失、为空、版本不明或适用范围不明确时，暂停正式设计。

## 专项路由

研发阶段命中以下子领域时，AI 必须继续读取对应专项规范。**本表为本产品特有，换产品时整表替换。**

| 研发阶段 | 子领域 | 命中条件 | 读取规范 |
| -------- | ------ | -------- | -------- |
| 任意阶段 | 架构文档 | 涉及 `docs/product-development/architecture/`、前端架构、后端架构、技术栈、目录结构或分层边界 | [架构设计](docs/product-development/architecture/index.md) |
| 任意阶段 | 实施规范 | 涉及 Java、Vue、TypeScript、数据库、表结构、SQL、索引、迁移或实现规范入口 | [实现规范](docs/product-development/standards/implementation/index.md) |
| 任意阶段 | 数据库设计 | 涉及数据库、表结构、SQL 语句、索引、迁移、数据保留或恢复 | [SKILL HUB 数据库设计规范](docs/product-development/standards/implementation/database-design.md) |
| 需求阶段 | 功能需求文档 | 整理原始需求、创建 `feature-<featureId>/requirement.md`、维护功能需求 | [功能需求文档规范](docs/product-development/standards/docs-governance/feature-requirement-doc-standard.md) |
| 实施阶段 | Skill 资产与发布治理 | 命中 `feature-skill-asset-release-governance` 的 Java、PostgreSQL、Session、发布门禁、审计或 Vue 控制台实现 | [Feature 实施计划](docs/product-development/features/feature-skill-asset-release-governance/implementation-plan.md)、[后端架构基线](docs/product-development/architecture/backend-architecture.md)、[前端架构基线](docs/product-development/architecture/frontend-architecture.md)、[数据库实施规范](docs/product-development/standards/implementation/database-design.md) |
| 设计阶段 | 后端架构基线 | Feature 涉及 Java 后端、接口、持久化、权限或服务治理 | [后端架构基线](docs/product-development/architecture/backend-architecture.md) |
| 设计阶段 | 前端架构基线 | Feature 涉及 Vue 页面、组件、路由、状态或控制台交互 | [前端架构基线](docs/product-development/architecture/frontend-architecture.md) |
| 设计阶段 | SkillHub CLI | Feature 涉及 CLI 命令、凭据、本地文件、打包、适配器或遥测上报 | [后端架构基线](docs/product-development/architecture/backend-architecture.md)、[实现规范](docs/product-development/standards/implementation/index.md)、[TypeScript 最佳实践](docs/product-development/standards/implementation/typescript-best-practices.md) |

> **维护约束**：具体规范映射只维护在本表；新增或删除子领域时，只更新本表。
