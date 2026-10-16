# SkillHub 扩展 API 速查（质量 / 优化 / 工作台 / 观测增强 / AI 配置）

> 覆盖对齐实施（V12-V17）新增的后端端点。基础端点（`/api/skills`、`/api/discovery`、观测 ingest 等）见根 README。
> 所有端点无鉴权，错误结构统一为 `{message, code}`（质量报告错误另带降级骨架字段，见下）。
> 通用错误码：`VALIDATION_ERROR`、`SKILL_NOT_FOUND`、`VERSION_NOT_FOUND`、`OPTIMIZE_TASK_NOT_FOUND`、`CANDIDATE_NOT_FOUND`、`OPTIMIZE_TASK_CONFLICT`、`OPTIMIZE_ADOPT_FAILED`、`AI_CONFIG_ERROR`、`NO_WAITING_TASK`。

---

## 1. AI 模型配置 `/api/ai-config`

| 方法 | 路径 | 参数 / 请求体 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/api/ai-config` | 无 | 当前生效配置；`apiKey` 掩码返回。字段：`enabled`、`endpoint`、`apiKey`、`model`、`embeddingModel`、`timeoutMs`、`maxTokens`、`contextWindow`、`reasoningEffort` 等 |
| PUT | `/api/ai-config` | body 部分更新（如 `{model, contextWindow}`）；`apiKey` 为空串或 `"******"` 表示不修改 | 更新后 LLM 客户端即时生效；返回掩码后完整配置 |
| GET | `/api/ai-config/models` | 可选 `endpoint`（覆盖已存配置探活） | 代理拉取 OpenAI 兼容 `{endpoint}/models`，返回字符串模型 id 列表；过滤非对话模型 |

错误：

- 未配置接口地址 / 模型服务非 2xx / 无可用模型 → `400` + `{"message":"...","code":"AI_CONFIG_ERROR"}`

## 2. 质量报告 `/api/observations/skills/{slug}/quality-report*`

| 方法 | 路径 | 参数 | 响应要点 |
| --- | --- | --- | --- |
| GET | `.../quality-report` | 可选 `version`（缺省最新） | 报告：`overallScore`、`conclusion`、`analyzedAt`、`skillVersion`、`fileCheck`、`wording`、`effect`、`suggestions` 等 |
| GET | `.../quality-report/summary` | 无 | 按版本汇总数组：`[{version, analyzedAt, overallScore, fileScore, wordingScore, effectScore}]` |
| POST | `.../quality-report/analyze` | body：`version?`、`scope=static\|chain\|all`（缺省 chain） | `{accepted:true, message:"分析完成", report:{...}}`；`static` 只重算格式+内容，`all` 静态+链路全量 |

契约细节：

- **null 字段保留策略**：本域响应使用独立 ObjectMapper 序列化，**保留契约中的 null 字段**（不受全局 `jackson.default-property-inclusion: non_null` 影响），前端可依赖 `overallScore=null` 等降级形态。
- 错误时返回结构体 + `status`（`404=not_found`，`400=error`），body 含 `message`、`code` 与报告降级骨架（`overallScore=null`、`conclusion=no_data`、`fileCheck`/`wording`/`effect` 空对象、`suggestions` 空数组）。
- `SKILL_NOT_FOUND` / `VERSION_NOT_FOUND` → 404；其余 → 400。

## 3. 优化流水线

### 3.1 任务 `/api/observations/skills/{slug}/optimize-tasks` 与 `/api/observations/optimize-tasks/{id}`

| 方法 | 路径 | 参数 / 请求体 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/skills/{slug}/optimize-tasks` | query：`mode?`、`version?` | 任务数组（含 `candidates`）。**`version` 过滤**：先按 SemVer 校验版本存在，再按 `versionId` 过滤 |
| POST | `/skills/{slug}/optimize-tasks` | body：`version`（必填语义，缺失报 VERSION_NOT_FOUND）、`mode=repair\|edit`、`priorities?`、`createdBy?`、`targetIssueIds?`、`runMode=auto\|stepwise`、`conversationId?` | 创建后的任务视图。同 skill+version 已有进行中任务（queued/running/waiting_input）时 `400` + `OPTIMIZE_TASK_CONFLICT` |
| POST | `/api/observations/optimize-tasks/{id}/cancel` | body：`operator?`、`reason?` | 取消后的任务视图 |

任务视图字段：`id`、`skillId`、`versionId`、`mode`、`runMode`、`targetIssueIds`、`targetScope`、`conversationId`、`status`、`progressStage`、`progressPercent`、`progressMessage`、`priorityList`、`failReason`、`createdBy`、`createdAt`、`updatedAt`、`candidates`（仅列表接口带）。

状态机（节选）：`queued → running → waiting_input | pending_review | gated_fail | rejected | adopted | canceled`。

### 3.2 候选 `/api/observations/optimize-candidates/{id}`

| 方法 | 路径 | 请求体 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/optimize-candidates/{id}` | 无 | `summary`、`diffText`、`edits`、`gates`（五门禁结果）、`paired`、`coverage`、`decision`、`decisionReason`、`decidedBy`、`decidedAt`、`resultVersionId/Label` |
| POST | `/optimize-candidates/{id}/decision` | body：`decision=adopt\|reject`、`reason?`、`operator?` | 决策后候选视图；`adopt` 尝试采纳为新版本，失败时 `OPTIMIZE_ADOPT_FAILED` |

五门禁默认阈值：`improvement ≥ 0.02`、`regression ≤ 0.02`、tests 全过、edit budget ≤ 4、paired 比较非多数变差。

### 3.3 ReAct 轨迹 `/api/observations/optimize-tasks/{id}/react-steps`

| 方法 | 路径 | 参数 | 响应要点 |
| --- | --- | --- | --- |
| GET | `.../react-steps` | query：`after?`（exclusive stepNo 游标）、`size?`（默认 500，上限 2000） | `[{stepNo, thought, actionType, actionJson, observationJson, durationMs, createdAt}]` |

任务不存在 → `404` + `OPTIMIZE_TASK_NOT_FOUND`。

## 4. 分析优化工作台会话 `/api/observations/conversations*`

| 方法 | 路径 | 参数 / 请求体 | 响应要点 |
| --- | --- | --- | --- |
| POST | `/skills/{slug}/versions/{versionId}/conversations` | path：`slug`、`versionId`（数字版本 id） | `{id, slug, versionId, createdAt, updatedAt}`；服务端写欢迎消息（assistant/text）+ 快捷操作卡（system/action_card） |
| GET | `/skills/{slug}/conversations` | 可选 `versionId` | 摘要数组，`updatedAt` 倒序 |
| GET | `/conversations/{id}/messages` | 可选 `after`（exclusive seq） | 消息数组：`id`、`conversationId`、`seq`、`role`、`msgType`、`content`、`relatedTaskId`、`relatedCandidateId`、`createdAt` |
| GET | `/conversations/{id}/events` | 无 | **SSE**（`text/event-stream`） |
| POST | `/conversations/{id}/messages` | body：`content` | 落库并广播后的用户消息视图 |
| POST | `/conversations/{id}/advance` | body：`note?` | 推进 `waiting_input` 任务；无等待任务 → `400` + `NO_WAITING_TASK` |

SSE 契约细节：

- 事件名固定为 **`message`**，data 为完整 WorkbenchMessage JSON。
- 心跳为 **15s 注释帧 `:ping`**（非命名事件），用于对抗代理/空闲切断。
- **事件以数据库为准，SSE 只加速**：断连或丢事件后，客户端用 `GET /conversations/{id}/messages?after=seq` 补齐；EventSource 自动重连。

## 5. 观测增强 `/api/observations`（dashboard / analysis / search）

| 方法 | 路径 | 参数 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/sessions/{id}/analysis` | **`skill` 必填**；`version?`、`refresh?` | 会话级规则 + 覆盖分析。缺 `skill` 时 Spring 报 400（参数缺失） |
| GET | `/sessions/{id}/dashboard` | `skill?`、`version?` | 单页仪表盘：graph / waterfall / stats / transcript / files 组装；分层响应（旧字段别名 + `raw` / `analysis` / `analysisStatus`） |
| GET | `/sessions/{id}/dashboard/node` | **`nodeId` 必填** | 节点完整 payload（args / result / files / usage），同样分层包装 |
| GET | `/search` | **`q` 必填**；`skill?`、`version?`、`limit?`（默认 30） | 会话检索结果：`session_id`、`session_key`、`title`、`snippet`、`started_at` 等 |

契约细节：

- **dashboard `nodeId` 为 URL-safe base64**（`Base64.getUrlEncoder().withoutPadding()`），内部形如 `step|sessionRowId|turnIndex|stepId` 或 `subagent|childSessionRowId`。非法 nodeId → `400` + `VALIDATION_ERROR`（`节点标识无效`）。
- `GET /sessions/{id}` 与 `/chain` 也改为分层响应，旧客户端字段别名保留在顶层。

## 6. T30 集成验证发现的契约细节汇总

| # | 细节 | 说明 |
| --- | --- | --- |
| 1 | `sessions/{id}/analysis` 必带 `skill` | `@RequestParam String skill` 无默认值，缺失即 400 |
| 2 | dashboard `nodeId` URL-safe base64 | 无 padding；解码前勿做 URL 二次编码时丢字符 |
| 3 | SSE 事件名 `message` + 15s `:ping` 注释帧 | 前端 EventSource 需监听 `message`；忽略注释行即可 |
| 4 | `optimize-tasks` 支持 `version` 过滤 | GET 列表先校验版本存在再按 versionId 过滤 |
| 5 | quality 端点 null 字段保留 | 独立序列化器；错误体也带降级骨架 |
