---
title: 架构设计
description: SKILL HUB 前后端架构、工程结构、模块边界和关键技术基线入口
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-03
sourceType: manual
---

# 架构设计

本目录收录 SKILL HUB 的前端架构、后端架构、工程目录、分层职责和关键技术边界。

## 推荐阅读顺序

1. [后端架构基线](./backend-architecture.md)
2. [前端架构基线](./frontend-architecture.md)

## 适用范围

- 新增 Feature 的技术设计和实施计划。
- Java 后端服务、Vue 控制台、数据访问和跨 Feature 契约。
- 架构、技术栈、目录结构或模块边界变更。

后端架构基线当前为 `v0.5-draft`，前端架构基线当前为 `v0.3-draft`。用户已确认 JDK 8、Vue 3、PostgreSQL 15、Redis Streams 和账户密码 Session 等既有基线，并于 2026-09-03 确认首期运行事件采用 PostgreSQL 15 权威存储、Redis Streams 异步通知；未确认的容量、备份、RPO/RTO 和部署参数仍须在后续阶段补齐。
