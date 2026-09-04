---
title: SKILL HUB 后端架构基线
description: SKILL HUB Java 后端控制面、分层边界、目录结构和工程约束
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-03
sourceType: manual
---

# SKILL HUB 后端架构基线

## 1. 基线元信息

| 项目 | 内容 |
| --- | --- |
| 基线版本 | v0.5-draft |
| 适用产品 | SKILL HUB，公司内部私有化部署 |
| 适用 Feature | `feature-skill-asset-release-governance`、`feature-skill-installation-recovery`、`feature-skill-runtime-observability` 及其后端相关 Feature |
| 后端语言 | Java |
| Java 版本 | JDK 8 |
| 核心框架 | Spring Boot 2.7.18 模块化单体 + Spring MVC 5.3.31 |
| 安全框架 | Spring Security 5.7.11 |
| 维护责任 | product-development |
| 确认状态 | draft，用户已确认 JDK 8、列出的 Java 8 兼容技术版本、PostgreSQL 15、Redis Streams、浏览器账户密码 Session 和首期运行事件存储方案；正式部署拓扑、容量、备份和 RPO/RTO 待确认 |

本文件是设计阶段架构基线。当前仓库已经存在 `backend/` 模块化单体、Flyway V1-V11、资产治理、API Token 和安装恢复实现；运行观测接收、事件存储、聚合和查询仍未实现。

JDK 8 兼容技术栈：

| 技术 | 基线版本 | 说明 |
| --- | --- | --- |
| MyBatis-Plus | 3.5.5 | 数据访问 |
| Maven | 3.8.8 | 构建工具 |
| PostgreSQL | 15.x | 事务数据库，具体补丁版本由部署基线确定 |
| PostgreSQL JDBC | 42.2.27 | Java 8 兼容驱动 |
| Flyway | 8.5.13 | 数据库迁移 |
| Redis | 6.2.14 | Redis Streams 异步任务基础设施 |
| OpenTelemetry Java | 1.32.0 | 平台请求、任务和适配器观测 |
| Micrometer | 1.10.13 | 指标采集与 Spring Boot 集成 |
| SLF4J | 1.7.36 | 日志门面 |
| Logback | 1.2.13 | 日志实现 |
| JUnit 5 | 5.8.2 | 测试框架，与 Spring Boot 2.7.18 兼容 |
| Mockito | 4.8.1 | 单元测试 Mock |
| SpringDoc | 1.7.0 | OpenAPI 文档，使用 Spring Boot 2.x 兼容线 |

部署工具 Docker Compose 2.24.6、Kubernetes 1.28.15 和 Helm 3.14.4 参考 iflytek SkillHub 的私有化方向，不受 Java 版本直接约束。上述异步任务、可观测性和部署方向参考 iflytek SkillHub；数据库结构以 [SKILL HUB 数据库设计规范](../standards/implementation/database-design.md) 为准，需要偏离时必须记录原因、影响和兼容策略。

## 2. 架构目标与边界

### 2.1 架构目标

- 用 Java 建设 Skill 资产、版本、发布、门禁、权限、审计和策略控制面。
- 以模块化单体作为首期控制面形态，保持明确的领域模块和事务边界。
- 将运行时遥测摄入、评测执行等具有不同吞吐和安全边界的能力通过契约接入，不塞入 Registry 的同步请求流程。
- 让不可变版本摘要、发布范围、门禁证据、策略版本和审计记录成为跨 Feature 的稳定关联依据。
- 所有关键操作经过身份、范围和角色校验；关键状态变化可追溯、可恢复。
- 首期在 PostgreSQL 15 中追加写入并按时间分区保存运行事件和聚合结果，使用 Redis Streams 触发异步聚合，不把 Redis 当作权威事实源。

### 2.2 后端负责范围

```text
资产注册/导入、元数据检查、版本和差异
生命周期、目录查询、公司/项目/环境发布范围
静态扫描/评测/审核证据关联、发布策略和门禁决策
RBAC、审批人分离、审计、保留策略
OTLP/标准事件接收、归一化、幂等落库、Trace/调用投影和指标查询
```

### 2.3 后端不负责范围

- Agent runtime 执行、Skill 安装、安装失败回退和实际灰度流量调度。
- 目标 Agent 主机上的插件、Hook、Collector 执行和本地 spool；这些动作由 SkillHub CLI 的白名单适配器负责。
- 评测 Runner、判定器、Finding 生成和 Skill 内容自动生成。
- 替代公司统一身份、日志、APM 或基础设施平台。

## 3. 总体拓扑与模块边界

```text
客户端/控制台/CLI
        |
        v
API 层 -> 应用层 -> 领域层 -> 持久化/外部适配器
  |          |         |              |
鉴权       用例编排   业务规则       数据库/制品存储/搜索/Scanner
  |
审计与请求关联
```

首期推荐采用模块化单体：

- `asset`：资产注册、来源、制品清单和元数据完整性。
- `version`：不可变版本、内容摘要、版本差异和生命周期。
- `catalog`：授权范围内的搜索、筛选和详情查询。
- `release`：发布绑定、公司/项目/环境范围和当前版本解析。
- `gate`：静态扫描、评测、风险、审核、灰度观察证据和发布决策。
- `policy`：发布、灰度、回退和数据保留策略的版本化和生效。
- `governance`：角色、范围授权、审批人分离和机器主体。
- `audit`：追加式操作审计和状态还原查询。
- `integration`：iflytek 能力适配器、安装/观测/评测跨 Feature 契约。
- `telemetry`：运行事件接收、归一化、脱敏复检、幂等存储、Trace/Skill 调用投影和指标聚合查询。

模块之间只能通过应用服务或明确的领域契约交互，禁止跨模块直接访问对方持久化表。`version_digest` 是跨模块和跨 Feature 的不可变关联键。

## 4. 后端目录结构

以下目录约束覆盖当前模块化单体和 CR-022 增量，遵循 Controller/Service/Mapper/Model 分层。`com.km.skillhub` 是仓库当前实际包名；如需切换正式反向域名，必须作为独立兼容迁移评审，不在 CR-022 中顺带改名。

```text
backend/
├─ pom.xml
├─ src/main/java/com/km/skillhub/
│  ├─ SkillHubApplication.java                    # 应用启动入口
│  ├─ controller/<module>/                        # Controller、请求参数和接口入口
│  ├─ service/<module>/                           # Service 接口
│  │  └─ impl/                                    # Service 实现、事务和流程编排
│  ├─ mapper/<module>/                            # MyBatis Mapper 接口
│  ├─ model/
│  │  ├─ entity/                                  # DO，与表结构对应
│  │  ├─ dto/                                     # 请求和层间传输对象
│  │  ├─ vo/                                      # 面向前端的响应对象
│  │  └─ query/                                   # 查询条件对象
│  ├─ common/
│  │  ├─ constant/                                # 公共常量
│  │  └─ enums/                                   # 状态和类型枚举
│  ├─ config/                                     # Spring Bean 和组件配置
│  ├─ exception/                                  # 业务和系统异常
│  ├─ interceptor/                                # 鉴权、请求和上下文拦截器
│  ├─ aspect/                                     # 审计、日志等横切能力
│  ├─ filter/                                     # Web 过滤器
│  ├─ util/                                       # 无业务语义工具
│  ├─ handler/                                    # 全局异常和响应处理
│  ├─ integration/                                # 外部 Registry、存储和下游适配
│  └─ telemetry/                                  # 运行事件接收、查询和聚合领域
├─ src/main/resources/
│  ├─ application.yml                             # 公共配置
│  ├─ application-dev.yml                         # 开发配置
│  ├─ application-test.yml                        # 测试配置
│  ├─ application-prod.yml                        # 生产配置
│  ├─ mapper/                                     # MyBatis XML
│  ├─ db/migration/                               # Flyway 迁移脚本
│  └─ logback-spring.xml                          # 日志配置
└─ src/test/java/com/km/skillhub/
   ├─ controller/                                 # Controller 测试
   ├─ service/                                    # Service 测试
   ├─ mapper/                                     # Mapper 测试
   ├─ integration/                                # 跨模块集成测试
   ├─ contract/                                   # 跨 Feature 契约测试
   └─ support/                                    # 测试数据和 Mock 工具
```

目录约束：Controller 只做参数校验和响应封装；Service 承载业务逻辑和事务；Mapper 只做数据访问；DO/DTO/VO/Query 不混用；公共模块不反向依赖业务模块；测试目录按测试层级组织，不把集成测试伪装成单元测试。

### 4.1 SkillHub CLI 客户端边界

CLI 是与 `backend/`、`frontend/` 并列的 Node.js 20 + TypeScript 客户端，使用 npm 构建。它负责本地凭据、Skill 目录/ZIP 校验打包、目标 Agent 白名单适配器安装、诊断、恢复、本地事件缓冲和批量上报；不承载服务端治理状态，不直接连接 PostgreSQL、Redis 或服务端制品目录。人工输出使用中文，`--json` 输出稳定错误码和结构化结果。

```text
cli/
├─ package.json
├─ tsconfig.json
├─ src/
│  ├─ index.ts
│  ├─ commands/                 # login、publish、telemetry install/status/repair
│  ├─ clients/                  # SkillHub HTTP/OTLP 客户端
│  ├─ adapters/                 # Codex、编辑器、Claude Code 白名单适配器
│  ├─ telemetry/                # 归一化、脱敏、spool、checkpoint 和上传器
│  ├─ platform/                 # 文件权限、原子写、ZIP 和进程探测
│  ├─ stores/                   # 配置、凭据和安装状态
│  └─ shared/                   # 错误码、输出和稳定标识
└─ test/{unit,integration,fixtures}/
```

## 5. 分层、接口和事务

### 5.1 分层依赖

```text
controller -> service -> mapper -> database
     |          |          |
    VO/DTO    model       entity/query
service -> integration / config / audit adapters
```

`service` 负责导入、发布、撤回、策略生效和审计流程；`model` 中的枚举和值对象表达稳定业务语义；`mapper` 只负责 MyBatis 数据访问；对象存储、搜索、消息和外部 Registry 通过 `integration` 适配。

### 5.2 事务边界

- 创建版本、写入内容摘要、元数据检查结果和审计记录必须在一个可恢复的一致性边界内完成。
- 发布绑定、发布决策、策略版本和审计记录必须保证决策可追溯；外部通知失败不能伪造 active binding。
- 多范围发布按范围独立处理，返回逐范围结果；不使用跨范围的大事务掩盖部分失败。
- 外部 Scanner、评测和撤回通知采用幂等键、超时和可重试任务；重复消费不重复生成有效状态。
- 遥测批次、事件去重记录、原始事件和聚合 Outbox 在同一 PostgreSQL 事务中提交；事务提交后才能返回已受理。Redis Streams 发布失败只形成可重试积压，不能回滚或丢失已受理事件。

### 5.3 稳定接口契约

应用接口至少需要表达：对象 ID、`version_digest`、目标范围、策略版本、证据摘要、当前状态、失败原因和审计关联 ID。具体 HTTP 路径、Java 方法签名和序列化格式待实施基线确认。

## 6. 数据与存储边界

- 权威元数据、状态、范围绑定、策略版本、原始运行事件、Trace/调用投影、指标聚合和检查点使用 PostgreSQL 15；运行事件按事件时间分区，表结构以数据库设计规范为准。
- Skill 制品、来源快照和大型门禁报告使用不可变制品/对象存储；具体组件待确认。
- 目录查询可使用派生搜索索引；索引丢失时可从权威数据重建，不能反向修改权威状态。
- 审计数据追加写入并受限修改；永久数据不能通过普通业务删除接口删除。
- Redis Streams 只携带已提交批次 ID 和聚合唤醒信息；消费者始终回读 PostgreSQL，Stream 删除或重复投递不得改变权威数据。
- 运行观测域的原始事件默认保留 365 天、聚合指标永久保留；分区清理由运行观测域执行，策略契约由治理域提供。

## 7. 安全、错误处理与可观测性

### 7.1 安全

- 每个关键用例先校验身份、角色、授权范围和对象状态。
- 浏览器仅支持账户密码登录，使用 Spring Security 5.7.11 的服务端会话和 HttpOnly/Secure/SameSite Cookie；CLI 使用带作用域的 Bearer API Token，不引入 OAuth2、统一单点登录或设备授权。
- 密码只保存 BCrypt 哈希，不保存明文或可逆密文；登录失败、锁定和登出操作必须写入审计。
- 人工申请人与人工审批人必须分离；自动发布使用明确的服务主体类型。
- 版本和证据通过内容摘要关联；摘要不匹配时拒绝发布。
- 敏感配置不写入源码、配置仓库或普通日志；密钥管理方式待确认。
- 外部适配器不能直接修改权威版本、发布状态和审计记录。

### 7.2 错误处理

统一返回可定位的错误码、对象标识、失败阶段、可重试标志和审计关联 ID。领域规则失败不重试；网络、限流和临时依赖失败按幂等任务重试；策略缺失、门禁证据缺失和条件无法判断默认阻断。

### 7.3 可观测性

后端日志和指标至少关联请求 ID、操作主体、对象 ID、`version_digest`、范围、策略版本和决策 ID。平台服务自身的请求、异步任务、Scanner 和策略决策观测与用户 Agent 运行观测分开存储和呈现。可观测性优先采用 OpenTelemetry 与 Micrometer；具体日志框架、指标系统和告警阈值待确认。

## 8. Java 与《阿里巴巴 Java 开发手册》约束

后端代码必须遵循组织批准版本的《阿里巴巴 Java 开发手册》。当前仓库没有该手册的本地副本和版本声明，因此本节先固定必须落地的检查方向，正式设计前需补充实施标准文件和手册版本。

- 包、类、方法、变量和常量遵循手册命名规范，禁止无意义缩写和拼音混用。
- 常量集中管理，禁止魔法值；枚举和值对象表达有限状态和业务语义。
- 优先使用明确的参数校验、空值处理和异常类型；禁止吞异常、用异常控制正常分支或返回含义不清的 `null`。
- 日志包含必要上下文但不得打印凭据、密钥、完整制品内容或敏感运行文本；禁止在日志中使用字符串拼接造成不可控内容。
- 数据访问必须参数化，事务边界显式，禁止在循环中无控制地执行批量查询或更新。
- 公共方法、线程池、锁、缓存、重试和异步任务必须说明并发模型、资源上限和关闭方式。
- 领域规则使用可测试的纯逻辑；Controller 不承载发布门禁和状态机业务规则。
- 单元测试、集成测试、契约测试和安全测试分层，测试不得依赖共享不可控环境。
- 格式化、静态检查、依赖漏洞检查、单元测试和构建命令必须在实施标准入口确认后固定。

本节不能替代《阿里巴巴 Java 开发手册》全文，也不能在未确认手册版本时宣称已完成合规验证。

## 9. 发布、兼容和回滚

- 首期采用私有化部署；容器编排采用 Docker Compose 2.24.6，生产环境采用 Kubernetes 1.28.15 和 Helm 3.14.4，具体部署拓扑和环境数量待确认。
- 数据库迁移必须前向可执行、可检查，破坏性变更与应用发布分离，并提供恢复路径。
- 发布策略、保留策略和权限策略均版本化；策略错误可恢复到上一已审核版本。
- 外部适配器不可用时停止自动发布，不绕过门禁；搜索索引异常不改变权威版本状态。
- 发布前必须验证旧版本读取、策略回退、部分范围失败和紧急撤回路径。
- 当前没有吞吐、延迟、容量或可用性数值基线，实施前不得自行补充为已确认指标。

## 10. 待确认项与维护要求

- 制品存储、搜索、消息组件的准确版本和部署方式。
- 《阿里巴巴 Java 开发手册》的组织批准版本及本地实施标准路径。
- 身份认证、密钥管理、部署拓扑、备份和灾难恢复约束。
- 包名中的公司反向域名和服务命名。

本基线由 `product-development` 维护。v0.5-draft 的增量是首期 PostgreSQL 15 运行事件存储、Redis Streams 聚合通知和 SkillHub CLI 客户端边界。技术选型、边界或标准发生变化时必须升级基线版本，并同步检查引用它的设计、实施计划和 Feature 状态。
