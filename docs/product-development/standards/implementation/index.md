---
title: 实现规范
description: SKILL HUB Java、Vue、TypeScript、数据库和组件实现规范入口
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-03
sourceType: manual
---

# 实现规范

本目录收录 SKILL HUB 代码、组件、类型、数据库、注释和测试实现规范。设计阶段和实施阶段必须根据 Feature 影响范围读取对应标准。

| 基线版本 | v0.4-draft |
| --- | --- |
| 技术栈依据 | iflytek SkillHub 调研事实与 `kmplm-development-process` 实施规范 |
| 确认状态 | draft，用户已确认本入口和当前 Java/Vue/TypeScript/PostgreSQL 实施约束作为设计输入；组件库、供应链和部署参数待确认 |

## 推荐阅读顺序

1. [数据库设计规范](./database-design.md)
2. [Java 开发最佳实践](./java-best-practices.md)
3. [Vue 组件开发规范](./component-standard.md)
4. [TypeScript 最佳实践规范](./typescript-best-practices.md)
5. [TypeScript 文档注释规范](./typescript-doc-style-guide.md)

## Feature 适用标准

三个 CLI 增量 Feature 均涉及 JDK 8 后端、Vue 控制台、Node.js 20 + TypeScript CLI、PostgreSQL 15、Flyway 8.5.13、Redis Streams、Bearer API Token、接口契约和分层测试，因此至少读取本入口、数据库设计规范、Java 开发最佳实践、Vue 组件开发规范、TypeScript 最佳实践和 TypeScript 文档注释规范。CLI 使用 npm、`cac`、`fflate`、`zod` 和结构化 YAML 解析器；外部输入先校验，凭据与 spool 使用受限权限和原子写入。浏览器账户密码 Session 与 CLI Bearer 认证保持分离。

## 维护要求

标准文件必须包含版本、状态、适用范围和维护责任。技术选型或工程约束变更时，先升级标准版本，再同步检查架构基线、Feature 设计和实施计划。
