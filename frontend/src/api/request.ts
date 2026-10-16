/**
 * 观测/质量/优化/工作台 API 的共享请求封装。
 *
 * 与 App.vue 内联 request<T> 行为一致：
 * - 非 2xx 抛出结构化错误（后端 `{message, code, hint}` 信封）
 * - 204 返回 undefined
 * 不回改 App.vue，供 api/observation.ts 及后续独立页面复用。
 */

/** SkillHub API 错误，保留后端错误码与可操作提示。 */
export class ObservationApiError extends Error {
  code?: string
  hint?: string

  constructor(message: string, options: { code?: string; hint?: string } = {}) {
    super(message)
    this.name = 'ObservationApiError'
    this.code = options.code
    this.hint = options.hint
  }
}

/** 请求 JSON 接口；失败时抛 ObservationApiError。 */
export async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({} as Record<string, unknown>))
    throw new ObservationApiError(String(body.message || `请求失败（${response.status}）`), {
      code: typeof body.code === 'string' ? body.code : undefined,
      hint: typeof body.hint === 'string' ? body.hint : undefined
    })
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
