/**
 * AI 模型运行时配置 API 层（挂 /api/ai-config，差距清单 G5 / P3-T24）。
 *
 * 对齐后端 com.km.skillhub.aiconfig.AiModelConfigController 与 AiModelConfig：
 * - GET  /api/ai-config            当前生效配置（api_key 掩码为 "******"）
 * - PUT  /api/ai-config            部分更新：null 字段不变；apiKey 空串或
 *                                   "******" 表示不修改；reasoningEffort 空串表示清空
 * - GET  /api/ai-config/models     模型列表代理，可选 ?endpoint= 覆盖已存地址先探活
 *
 * 响应 Jackson default-property-inclusion: non_null，字段均为可选。
 * 错误信封 {message, code, hint} 由 request.ts 转 ObservationApiError。
 */
import { request } from './request'

/** AI 模型配置（sys_ai_config 单行，返回合并 yml 默认值后的生效配置）。 */
export interface AiModelConfig {
  id?: number
  /** 是否启用 AI 配置 */
  enabled?: boolean
  /** OpenAI 兼容接口地址（如 https://api.openai.com/v1） */
  endpoint?: string
  /** 读取时掩码为 "******"；PUT 传空串或掩码均表示不修改 */
  apiKey?: string
  /** 主模型 id */
  model?: string
  /** 向量化模型 id */
  embeddingModel?: string
  /** 请求超时（毫秒） */
  timeoutMs?: number
  /** 单次生成最大 token 数 */
  maxTokens?: number
  /** 模型上下文窗口（token 数） */
  contextWindow?: number
  /** 思考程度：minimal/low/medium/high；缺省或空串表示不传参数 */
  reasoningEffort?: string
  /** 最后更新者（服务端写入） */
  updatedBy?: string
  updatedAt?: string
  createdAt?: string
}

/** PUT 部分更新载荷：仅携带需要修改的字段。 */
export type AiModelConfigPatch = Partial<AiModelConfig>

/** GET /api/ai-config —— 当前生效配置（api_key 掩码）。 */
export function getAiModelConfig(): Promise<AiModelConfig> {
  return request<AiModelConfig>('/api/ai-config')
}

/** PUT /api/ai-config —— 部分更新，返回更新后的掩码配置。 */
export function updateAiModelConfig(patch: AiModelConfigPatch): Promise<AiModelConfig> {
  return request<AiModelConfig>('/api/ai-config', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch)
  })
}

/**
 * GET /api/ai-config/models —— 代理拉取对话模型 id 列表（非对话模型已被后端过滤）。
 * options.endpoint 非空时以 query 覆盖已存地址，供未保存新地址前先探活。
 */
export function listAiModels(options: { endpoint?: string } = {}): Promise<string[]> {
  const params = new URLSearchParams()
  if (options.endpoint) params.set('endpoint', options.endpoint)
  const suffix = params.toString()
  return request<string[]>(`/api/ai-config/models${suffix ? `?${suffix}` : ''}`)
}
