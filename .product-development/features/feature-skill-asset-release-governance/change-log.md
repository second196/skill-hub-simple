# Feature Change Log

## CR-001

- Date: 2026-09-01
- User request: Create formal requirements with configurable runtime, release, rollback, and retention defaults.
- Type: requirement-clarification
- Reason: Formalize the asset and release governance Feature from the confirmed Work Item decomposition.
- Affected requirements: all requirements in requirement.md
- Affected design: none
- Affected tasks: none
- Code scope: none
- Approval status: approved
- Verification status: not-run
- Replacement relation: none

## CR-002

- Date: 2026-09-01
- User request: 对 `feature-skill-asset-release-governance` 再次进行设计。
- Type: design-change
- Reason: 在已确认的 Feature 拆分、可配置发布策略和分层保留策略基础上形成正式设计与实施计划。
- Affected requirements: all requirements in requirement.md
- Affected design: create design.md v1
- Affected tasks: create implementation-plan.md v1
- Code scope: none; greenfield design only
- Approval status: approved
- Verification status: not-run
- Replacement relation: none

## CR-003

- Date: 2026-09-01
- User feedback: 指出设计阶段必须先检查 `docs` 下的架构和实施标准基线。
- Type: documentation-correction
- Reason: 设计基线检查未在正式设计写入前完成；补充阻塞记录，防止无基线草稿被当作正式方案。
- Affected requirements: none
- Affected design: design.md v1 marked invalid draft pending baselines
- Affected tasks: implementation-plan.md v1 marked invalid draft pending baselines
- Code scope: none
- Approval status: approved
- Verification status: pass for repository path inventory; unavailable for Python checker because Python runtime is unavailable
- Replacement relation: supersedes CR-002 as the active design handoff state

## CR-004

- Date: 2026-09-01
- User request: 创建 Java 后端和 Vue 前端架构基线，并调整 `AGENTS.md`。
- Type: documentation-correction
- Reason: 按设计基线规则补充架构文档和项目级架构路由；实施标准入口仍缺失，正式设计继续阻塞。
- Affected requirements: none
- Affected design: current design.md remains invalid draft pending baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft pending baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline creation; baseline confirmation pending
- Verification status: not-run
- Replacement relation: none

## CR-005

- Date: 2026-09-01
- User request: 参考 `kmplm-development-process` 的架构与实施规范，补充 SKILL HUB 数据库设计并更新 `AGENTS.md` 路由。
- Type: documentation-correction
- Reason: 建立 Java/Vue/数据库实现基线入口，按 SKILL HUB 四个 Feature 的总体需求设计事务数据、高吞吐运行数据和制品存储边界。
- Affected requirements: `requirement-skill-asset-registration`, `requirement-skill-metadata-completeness`, `requirement-skill-catalog-search`, `requirement-skill-version-immutability`, `requirement-skill-lifecycle-state`, `requirement-skill-release-scope`, `requirement-skill-release-gate`, `requirement-skill-governance-audit`, `requirement-skill-data-retention`
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline creation; baseline confirmation pending
- Verification status: not-run
- Replacement relation: none

## CR-006

- Date: 2026-09-01
- User request: 将数据库从 MySQL 调整为 PostgreSQL 15。
- Type: architecture-baseline-correction
- Reason: 统一 SKILL HUB 事务数据库技术基线，并将数据库类型、结构化字段、布尔字段、时间字段和当前绑定唯一索引调整为 PostgreSQL 15 语义。
- Affected requirements: `requirement-skill-asset-registration`, `requirement-skill-metadata-completeness`, `requirement-skill-catalog-search`, `requirement-skill-version-immutability`, `requirement-skill-lifecycle-state`, `requirement-skill-release-scope`, `requirement-skill-release-gate`, `requirement-skill-governance-audit`, `requirement-skill-data-retention`
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline correction; baseline confirmation pending
- Verification status: not-run
- Replacement relation: supersedes the PostgreSQL-related parts of CR-005

## CR-007

- Date: 2026-09-02
- User request: 其他技术栈参考 iflytek SkillHub 所用的技术栈。
- Type: architecture-baseline-correction
- Reason: 将 iflytek SkillHub 已由调研报告证实的 Java 21、Spring Boot 模块化单体、Redis Streams、OAuth2/CLI Device Flow/API Token、OpenTelemetry/Micrometer、Docker Compose、Kubernetes 和 Helm 纳入 SKILL HUB 技术基线参考；保留用户已确认的 Vue 3 前端选型。
- Affected requirements: all requirements in requirement.md
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline correction; baseline confirmation pending
- Verification status: not-run
- Replacement relation: supersedes the corresponding technology-stack assumptions in CR-005 and CR-006

## CR-008

- Date: 2026-09-02
- User request: JDK 必须是 8；其他技术栈按 JDK 8 确定版本；去掉 OAuth2 和统一单点登录，仅支持账户密码登录。
- Type: architecture-baseline-correction
- Reason: 将 Java 运行时从 JDK 21 收敛到 JDK 8 兼容基线，固定 Spring Boot 2.7.18、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5、PostgreSQL JDBC 42.2.27、Flyway 8.5.13、Redis 6.2.14、OpenTelemetry Java 1.32.0、Micrometer 1.10.13、Docker Compose 2.24.6、Kubernetes 1.28.15 和 Helm 3.14.4；认证收敛为账户密码和服务端会话。
- Affected requirements: all requirements in requirement.md
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline correction; baseline confirmation pending
- Verification status: not-run
- Replacement relation: supersedes the JDK 21 and OAuth2-related assumptions in CR-007
