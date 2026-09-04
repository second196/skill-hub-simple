# SKILL HUB 平台需求分解

complexity: complex
scope_decision: multi-feature
decomposition_status: confirmed

## 1. 输入事实

以下内容直接来自 `docs/research/skill-hub-research.md`，不代表现有代码已经具备这些能力：

- 产品定位为公司内部 Skill 资产治理、运行观测、质量评测和持续优化平台，首期私有化部署，不做 SaaS 多租户，见 `docs/research/skill-hub-research.md:17`、`docs/research/skill-hub-research.md:54`。
- 目标闭环包含导入或创建、静态检查、版本化、受控评测、审核发布、安装 Skill 与 Tracker、运行上报、观测归因、候选优化、复测、灰度、发布或回滚，见 `docs/research/skill-hub-research.md:62`。
- Agent 链路和 Skill 调用轨迹应来自同一原始事件流，并支持 `AgentTrace`、`SkillInvocation` 和 `MetricAggregate` 三类视图，见 `docs/research/skill-hub-research.md:130`。
- 每个可识别 Skill 调用至少需要 Skill、不可变版本、调用、Trace、父 Span、触发方式和 Tracker 版本等关联信息；无法可靠归属时必须标记未知，见 `docs/research/skill-hub-research.md:134`。
- 报告确认首批运行时接入范围和采集边界：Codex 走 CLI Hook/OTel Logs，Claude Code 走 OTLP；默认不采集完整 transcript、prompt 或代码片段，并要求敏感信息脱敏，见 `docs/research/skill-hub-research.md:147`。
- 评测和发布治理需要复用 iflytek SkillHub 的资产、版本、namespace、审核、RBAC、CLI 分发和静态扫描思路，复用 skill-up 的受控评测 Runner 能力，吸收 Witty 的 Tracker、运行证据和评测能力，见 `docs/research/skill-hub-research.md:109`。
- 报告建议的首期分阶段交付为 P0 事件模型和运行时矩阵，P1 资产/发布/安装/Tracker，P2 Trace 和指标，P3 评测 Runner，P4 Finding、候选、回归、灰度和回滚，见 `docs/research/skill-hub-research.md:187`。

## 2. 事实、建议、假设与待确认项

### 2.1 已有事实

- 调研报告记录了 R1/R2/R3/R4 的调研对象、基线和证据索引，见 `docs/research/skill-hub-research.md:29`、`docs/research/skill-hub-research.md:202`。
- iflytek SkillHub 已有 Registry、版本、发布、审核、RBAC、CLI 分发和发布前扫描能力，但其可观测性主要是平台服务自身链路，不等同于目标产品的 Agent 生产 Trace，见 `docs/research/skill-hub-research.md:95`。
- skill-up 能声明评测环境、引擎、模型和案例，并输出 JSON/JUnit/HTML 报告；材料未证明其是资产 Registry 或生产 Tracker，见 `docs/research/skill-hub-research.md:83`。
- Agent-Insight/Witty 已有多运行时采集、OTLP 归一化、Codex/Claude 适配器和 Skill 工作台参考实现；材料未证明其已经覆盖目标产品的完整组织治理和发布闭环，见 `docs/research/skill-hub-research.md:69`。

### 2.2 本次分解建议

- 将总需求拆成四个 Feature，按资产治理、安装恢复、运行观测、评测进化划分；该拆分及依赖关系已获用户确认，以保证每个 Feature 可独立评审和验收。
- 以 iflytek SkillHub 作为资产和治理复用候选，以 Witty/Agent-Insight 作为 Tracker 和运行证据复用候选，以 skill-up 作为受控评测 Runner 复用候选。
- 报告中的自动发布、灰度和回退数字已作为可配置的首期默认值写入 Feature 需求；后续可按授权范围调整，并必须记录策略版本、生效时间和审计信息。

### 2.3 待确认项

- 已确认：接受以上四个 Feature 的拆分和依赖关系（2026-09-01）。
- 已确认：首期默认运行时为 Codex CLI、VS Code/Cursor/Windsurf 扩展和 Claude Code OTLP；运行时矩阵可配置。
- 已确认：发布、灰度和回退采用报告中的默认参数，且按公司、项目和环境可配置、可调整、可审计。
- 已确认：数据保留采用分层默认策略，制品/评测/发布审计永久保留，原始运行事件默认 365 天；策略可配置。
- 已确认：创建四个 Feature 的正式 `requirement.md`；不创建 `design.md`、`implementation-plan.md` 或代码。
- 已确认：SkillHub CLI 作为跨 Feature 客户端增量，不新增独立 CLI Feature；安装接入归属安装恢复，运行数据采集与上报归属运行观测，Skill 包上传与发布申请归属资产治理。
- 已确认：CLI 使用 API Token Bearer，新增 `telemetry:write` 作用域；浏览器继续使用账户密码、服务端 Session 和 CSRF。
- 已确认：CLI Skill 上传默认创建草稿，提交审核和正式发布继续受现有门禁、审批人分离和审计约束。

## 3. Feature 与需求映射

### 3.0 CR-015 增量映射

用户确认参考项目对齐增量继续归属 `feature-skill-asset-release-governance`，不新增 Feature，不改变安装、运行观测和评测 Feature 的责任边界。

| 需求 | 对齐目标 | 验收重点 | 状态 |
| --- | --- | --- | --- |
| `requirement-skill-package-content` | 多文件 Skill 包和内容清单 | 真实包可导入、失败可追溯、文件可浏览/下载 | confirmed |
| `requirement-skill-semantic-version-tags` | 语义化版本和 `latest/stable/beta` | 标签指向具体 digest，禁用版本不能分发 | confirmed |
| `requirement-skill-discovery-search` | Skill 发现和筛选 | 授权范围、稳定分页、空/延迟状态 | confirmed |
| `requirement-skill-file-browse-download` | 文件目录、预览和下载 | 路径安全、版本明确、越权拒绝 | confirmed |
| `requirement-skill-version-comparison` | 版本比较 | 同资产双 digest 的元数据、文件和文本差异 | confirmed |
| `requirement-skill-publish-review-lifecycle` | 发布审核和生命周期工作台 | 审批人分离、门禁、撤回/归档/恢复 | confirmed |
| `requirement-skill-namespace-governance` | 命名空间和成员角色 | Owner/Admin/Member 与范围权限联动 | confirmed |
| `requirement-governance-account-management` | 管理员账户管理 | 停用会话失效、普通用户无权管理 | confirmed |
| `requirement-skill-governance-console-branding` | 参考项目同类信息架构和企业主题 | 中文界面、品牌 token、响应式可用 | confirmed |

### 3.0.1 CR-022 SkillHub CLI 增量映射

本次增量不新增 Feature，调整三个既有 Feature 的允许边界。CLI 只提供本地配置、适配器安装、数据补报和 Skill 包上传入口；业务状态仍由对应 Feature 的服务端治理流程产生。

| 需求 | 归属 Feature | 目标 | 验收重点 | 状态 |
| --- | --- | --- | --- | --- |
| `requirement-cli-agent-integration-installation` | `feature-skill-installation-recovery` | 安装目标 Agent 的插件、Hook、Collector 或 OTLP 配置 | 安装幂等、配置正确、结果可查询 | confirmed |
| `requirement-cli-agent-integration-check` | `feature-skill-installation-recovery` | 检查运行时、适配器、上传器和本地缓冲状态 | 不支持和异常状态显式展示 | confirmed |
| `requirement-cli-agent-integration-recovery` | `feature-skill-installation-recovery` | 处理接入失败、升级失败和旧配置恢复 | 不破坏可用接入，失败可追溯 | confirmed |
| `requirement-cli-runtime-collection` | `feature-skill-runtime-observability` | 通过适配器采集 Agent 和 Skill 运行事件 | 事件可查询，缺失字段不伪造 | confirmed |
| `requirement-cli-runtime-upload` | `feature-skill-runtime-observability` | 将本地标准化事件通过 OTLP 上传到 SkillHub | API Token 作用域、批量上传和结果可见 | confirmed |
| `requirement-cli-runtime-buffer-recovery` | `feature-skill-runtime-observability` | 离线缓冲、重试、补报和幂等 | 恢复后不重复记账 | confirmed |
| `requirement-cli-runtime-data-minimization` | `feature-skill-runtime-observability` | 上传前脱敏、截断和敏感数据拒绝 | 凭据和本地路径不泄露 | confirmed |
| `requirement-cli-skill-package-validation` | `feature-skill-asset-release-governance` | 校验目录或 ZIP 的 Skill 结构和元数据 | 缺少 `SKILL.md`、非法路径和不可读文件被拒绝 | confirmed |
| `requirement-cli-skill-upload` | `feature-skill-asset-release-governance` | 上传本地 Skill 包并创建资产或版本 | 进度、结果、摘要和控制台链接可见 | confirmed |
| `requirement-cli-skill-upload-idempotency` | `feature-skill-asset-release-governance` | 保证重复上传不生成重复资产或版本 | 请求 ID 和版本摘要可追溯 | confirmed |
| `requirement-cli-skill-review-submit` | `feature-skill-asset-release-governance` | 将草稿版本提交审核 | 不绕过发布门禁和审批人分离 | confirmed |

### 3.0.2 CR-017 API Token 增量映射

API Token 是账户自动化访问契约，继续归属资产治理 Feature；浏览器账户密码、服务端 Session 和 CSRF 流程不受影响。

| 需求 | 归属 Feature | 目标 | 验收重点 | 状态 |
| --- | --- | --- | --- | --- |
| `requirement-skill-api-token-access` | `feature-skill-asset-release-governance` | 为控制台和 CLI 提供按作用域隔离的 Bearer 访问凭据 | 创建只返回一次原文、作用域隔离、撤销/过期生效、审计可追溯 | confirmed |

### 3.1 `feature-skill-asset-release-governance`

| 需求 | 来源证据 | 可观察行为与验收条件 | 状态 |
| --- | --- | --- | --- |
| `requirement-skill-asset-registration` | 报告 3.1、6.2 | 有权限用户可注册或导入 Skill；来源、导入结果和失败原因可查询；不可读取或缺少必要内容时不能生成可发布版本。 | confirmed |
| `requirement-skill-metadata-completeness` | 报告 3.2、4.3 | 系统展示 Skill 主描述、附属内容、名称、描述、来源、许可、依赖和适用运行时的完整性；缺失或未知项必须显式标记，不得猜测补齐。 | confirmed |
| `requirement-skill-catalog-search` | 报告 4.3、5.1 | 用户可在授权范围内按名称、描述、标签、来源、运行时、状态和版本搜索，并查看版本、发布、评测和安装关联信息。 | confirmed |
| `requirement-skill-version-immutability` | 报告 3.1、6.2 | 每次内容变化生成独立版本；已创建版本的内容、来源和内容标识不可原地修改；用户可查看版本差异。 | confirmed |
| `requirement-skill-lifecycle-state` | 报告 3.2、4.3 | 至少区分草稿、候选、已发布、已下线、紧急撤回和已废弃；状态变化、原因和操作人可追溯；不可安装状态不能作为默认分发版本。 | confirmed |
| `requirement-skill-release-scope` | 报告 3.2、6.2 | 版本可发布到公司、项目和环境等授权范围；发布目标、当前版本、发布时间和范围可查询。 | confirmed |
| `requirement-skill-release-gate` | 报告 6.2、6.4、8.1 | 发布前必须关联静态扫描、受控评测、审核、风险和灰度证据；扫描失败或必需证据缺失时阻断发布；高风险候选不能绕过人工审核。 | confirmed |
| `requirement-skill-governance-audit` | 报告 4.3、7.3 | 按授权范围和角色控制导入、版本、发布、下线、撤回、评测和治理配置；关键操作记录主体、时间、对象、前后状态和原因；申请人与人工审批人分离。 | confirmed |
| `requirement-skill-data-retention` | 报告 6.4、7.3 | 数据保留策略按范围可配置；默认 Skill 制品、评测报告、发布决策和审计数据永久保留，原始运行事件保留 365 天，聚合指标永久保留；调整需授权、生效时间、策略版本和审计记录。 | confirmed |

### 3.2 `feature-skill-installation-recovery`

| 需求 | 来源证据 | 可观察行为与验收条件 | 状态 |
| --- | --- | --- | --- |
| `requirement-skill-distribution-installation` | 报告 3.2、4.3、6.4 | 用户可指定 Skill、版本、运行时获取已发布版本；草稿、门禁失败或不可分发版本不能作为安装目标；结果包含目标和失败原因。 | confirmed |
| `requirement-skill-tracker-companion-installation` | 报告 3.2、6.4、8.1 | 获取 Skill 时同时安装或配置对应 Tracker；安装结果分别展示 Skill、Tracker、配置、运行时支持和最近健康状态；Tracker 未完成时不算完整安装。 | confirmed |
| `requirement-skill-installation-instance` | 报告 6.1、6.4 | 记录 Skill、不可变版本、Agent、主机/逻辑实例、运行时、环境、安装时间、启用状态和最近健康信息；不同内容版本不能合并。 | confirmed |
| `requirement-skill-version-switching` | 报告 3.2、6.2 | 更新前识别当前版本并保留可回退版本；新 Skill 和 Tracker 完成安装、配置及可用性确认后才切换；记录切换前后版本和结果。 | confirmed |
| `requirement-skill-installation-failure-recovery` | 报告 6.4 | 下载、安装、配置、确认、停用旧版本或启用新版本失败时，旧版本保持或恢复启用；无可用旧版本时禁止启用未确认版本并要求人工处理；失败阶段、回退结果和当前状态必须补报且不重复记账。 | confirmed |
| `requirement-skill-emergency-revocation` | 报告 6.2、7.3 | 发现高危安全或行为问题时，授权管理员可阻断新增安装并停用授权范围内存量实例；停用结果、原因和影响范围可查询。 | confirmed |

#### CR-022 CLI 接入增量

| 需求 | 来源证据 | 可观察行为与验收条件 | 状态 |
| --- | --- | --- | --- |
| `requirement-cli-agent-integration-installation` | Witty CLI、安装指导和运行时矩阵 | CLI 可按运行时安装或配置插件、Hook、Collector 或 OTLP；重复执行不破坏现有配置，并输出安装阶段和结果。 | confirmed |
| `requirement-cli-agent-integration-check` | Witty `status`/安装检查链路 | CLI 可检查目标运行时、适配器版本、配置地址、凭据状态、本地 spool、上传器和最近健康状态；不支持、未安装和异常必须区分。 | confirmed |
| `requirement-cli-agent-integration-recovery` | Witty 安装失败和恢复链路 | 安装或升级失败时保留可用旧配置；CLI 可重新执行检查或恢复，并记录失败阶段、原因和结果；采集器异常不得阻塞 Agent 业务执行。 | confirmed |

### 3.3 `feature-skill-runtime-observability`

| 需求 | 来源证据 | 可观察行为与验收条件 | 状态 |
| --- | --- | --- | --- |
| `requirement-agent-runtime-event-collection` | 报告 6.1、8.1 | Tracker 尽可能采集任务/会话、Agent、子 Agent、模型、工具、MCP 和 Skill 调用事件；运行时不提供的事件必须标记缺失，不得用推测值填充。 | confirmed |
| `requirement-skill-invocation-version-attribution` | 报告 6.1 | 可识别调用关联 Skill、版本摘要、调用 ID、Trace、父 Span、触发方式和 Tracker 版本；无法可靠归属时标记“版本未知”，不绑定最新版本。 | confirmed |
| `requirement-tracker-buffered-upload` | 报告 6.2、7.3 | 网络或服务不可用时本地缓冲运行和状态事件；恢复后补报；成功、待上报、失败、丢弃、拒绝、超限和非法格式状态可查询；重试不重复记账。 | confirmed |
| `requirement-trace-dual-view` | 报告 6.1、6.2 | 从同一事件流提供 AgentTrace 和 SkillInvocation 两种视图，并支持双向定位；指标、问题、评测和发布决策可回溯到原始 Trace 和版本。 | confirmed |
| `requirement-skill-observability-metrics` | 报告 6.3 | 按 Skill、版本、运行时、模型、任务、项目、环境和时间查看调用量、通过率、可用时准确率、Token、成本、延迟、错误率、触发率和覆盖率；每项显示范围、分子、分母和条件。 | confirmed |
| `requirement-observability-comparison-validity` | 报告 1.2、6.3 | 比较时固定或展示 Skill 版本、运行时、模型参数、工具环境、任务集、判定器和样本数；条件不一致、样本不足或缺少真值时标记不可直接比较，不显示伪准确率。 | confirmed |
| `requirement-runtime-data-minimization` | 报告 6.4、7.3 | 默认不采集完整 transcript、prompt 和代码片段；仅在适配器暴露且策略允许时采集工具输入输出、FileEdit、Terminal 和摘要；上传前脱敏凭据、密钥和本地路径，受策略限制的文本最长 2000 字符。 | confirmed |
| `requirement-platform-observability` | 报告 7.1、7.2 | 平台自身可观测数据接入、队列、分析、评测 Runner 和发布失败；平台服务观测与用户 Agent 运行观测分开呈现。 | confirmed |

#### CR-022 CLI 运行观测增量

| 需求 | 来源证据 | 可观察行为与验收条件 | 状态 |
| --- | --- | --- | --- |
| `requirement-cli-runtime-collection` | Witty 插件、Hook、Collector 和统一事件模型 | CLI 安装的适配器应采集会话、Agent、子 Agent、模型、工具、MCP 和 Skill 调用事件，并保留 Session、Trace、Span 和父子关系；运行时未提供的字段必须标记缺失。 | confirmed |
| `requirement-cli-runtime-upload` | Witty OTLP Trace/Logs 上报链路 | CLI 或其上传器应使用 `telemetry:write` 凭据向 SkillHub OTLP 接口批量上报；服务端返回的受理、拒绝、超限和格式错误结果可查询。 | confirmed |
| `requirement-cli-runtime-buffer-recovery` | Witty 本地 JSONL spool、checkpoint 和重试链路 | 网络或服务不可用时事件写入本地 spool；恢复后按 checkpoint 补报，成功后推进位置，重复重试不得生成重复事件。 | confirmed |
| `requirement-cli-runtime-data-minimization` | Witty 脱敏、截断和最小采集链路 | 默认不采集完整 transcript、prompt 和代码；上传前脱敏 API Key、Token、密码、Secret 和本地路径，并对策略允许的文本执行长度限制。 | confirmed |

### 3.4 `feature-skill-evaluation-evolution`

| 需求 | 来源证据 | 可观察行为与验收条件 | 状态 |
| --- | --- | --- | --- |
| `requirement-evaluation-asset-versioning` | 报告 4.2、8.1 | 评测套件、案例、数据集、判定器、运行配置和报告可独立版本化；结果关联 Skill 版本、评测版本、运行时、模型、环境和执行时间。 | confirmed |
| `requirement-multi-runtime-controlled-evaluation` | 报告 4.2、6.3 | 指定 Skill 版本可在受控运行时、模型和环境中重复评测；条件不同的结果不能无条件合并。 | confirmed |
| `requirement-static-quality-findings` | 报告 4.3、6.2 | 静态质量检查记录规则、严重程度、证据、说明和修复建议；区分静态问题、评测失败、运行失败、模型异常、工具异常和采集异常。 | confirmed |
| `requirement-evolution-candidate-version` | 报告 3.1、6.2 | 基于用户反馈、确认问题或评测结果生成候选；候选包含基线版本、内容差异、来源证据、生成时间和状态，不能覆盖已发布版本。 | confirmed |
| `requirement-evolution-regression-and-review` | 报告 6.2、8.1 | 候选发布前执行适用静态检查、回归案例和评测；权限、外部写操作、质量下降、新运行时兼容性或高风险变化必须人工审核并保留证据。 | confirmed |
| `requirement-release-canary-and-rollback` | 报告 6.2、6.4 | 建议默认灰度符合条件实例的 10%（向上取整且至少 1 个），观察 24 小时且至少 30 次调用；错误率较基线增加 2 个百分点、基线大于 0 时相对增加 20%、基线为 0 时达到 2%、有效评分下降 5 个百分点或出现高危/严重问题时自动回退并上报证据。 | confirmed |
| `requirement-evaluation-truth-disclosure` | 报告 1.2、6.3、6.4 | 首批沿用结构化 LLM Judge 评分；人工修正分优先，未修正时使用机器评分；没有业务回执或脚本断言时不能把评分命名为业务准确率，估算值必须显式标注。 | confirmed |

## 4. 合并或拆分理由

### 4.1 拆分理由

- 资产注册/发布、运行时安装恢复、生产运行观测、受控评测进化分别拥有不同用户角色、生命周期、失败模型和独立验收结果。
- 安装和运行观测需要跨主机、网络和运行时边界；评测需要隔离 Runner 和固定环境；将它们塞入单一 Feature 会无法独立验证。
- 资产版本是所有下游证据的稳定主键，必须先作为跨 Feature 契约定义。

### 4.2 保留在同一 Work Item 的理由

四个 Feature 共同服务一个 Skill 生命周期闭环，共享版本、发布范围、运行证据和整体验收；拆成四个 Work Item 会丢失跨 Feature 依赖和发布闭环约束。

### 4.3 CLI 增量不单独建 Feature 的理由

- CLI 不产生独立业务对象，而是把既有资产治理、安装恢复和运行观测能力交付到目标主机。
- Skill 上传与运行数据上报拥有不同权限、数据模型、失败处理和验收标准；放在同一 CLI Feature 会掩盖服务端领域边界。
- 公共 CLI 配置、凭据和退出码可以通过跨 Feature 契约统一，但具体业务状态必须由对应 Feature 维护。

## 5. 整体风险与边界

- 报告中的复用结论是调研和方案建议，不代表对应开源项目可直接作为目标平台上线。
- 各运行时事件字段完整度不同；缺失数据必须可见，不能通过默认值或最新版本推断。
- 自动发布、灰度阈值、分层数据保留和紧急撤回会影响权限、合规、运营和成本；默认值已确认，后续调整必须通过版本化策略和审计完成。
- 当前没有吞吐、延迟、数据量、恢复时限等数值型非功能指标，不能自行补齐。

## 6. CR-022 用户确认

本次增量确认采用三个既有 Feature 扩展方式：CLI 接入安装归属安装恢复，运行数据采集与上报归属运行观测，Skill 包校验、上传和审核申请归属资产治理。CLI 使用 API Token Bearer，新增 `telemetry:write` 作用域；不引入 OAuth2、统一单点登录、CLI Device Flow、S3 或微服务。

## 7. 用户确认

confirmed_by: user
confirmed_at: 2026-09-01
confirmation_scope: 四个 Feature 的拆分和依赖关系、首期默认值可配置、正式需求文档写入
