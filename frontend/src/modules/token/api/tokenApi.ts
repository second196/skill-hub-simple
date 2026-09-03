import type {
  ApiTokenCreateRequest,
  ApiTokenCreateResponse,
  ApiTokenSummary,
  TokenScope,
  TokenStatus
} from '../types/token'

const scopes: TokenScope[] = ['skill:read', 'skill:publish', 'token:manage']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseScopes(value: unknown): TokenScope[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is TokenScope => typeof item === 'string' && scopes.includes(item as TokenScope))
}

function parseSummary(value: unknown): ApiTokenSummary | null {
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.name !== 'string'
    || typeof value.tokenPrefix !== 'string' || typeof value.createdAt !== 'string'
    || (value.status !== 'ACTIVE' && value.status !== 'EXPIRED' && value.status !== 'REVOKED')) return null
  return {
    id: value.id,
    name: value.name,
    tokenPrefix: value.tokenPrefix,
    scopes: parseScopes(value.scopes),
    createdAt: value.createdAt,
    expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : undefined,
    lastUsedAt: typeof value.lastUsedAt === 'string' ? value.lastUsedAt : undefined,
    revokedAt: typeof value.revokedAt === 'string' ? value.revokedAt : undefined,
    status: value.status as TokenStatus
  }
}

function parseCreated(value: unknown): ApiTokenCreateResponse | null {
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.name !== 'string'
    || typeof value.token !== 'string' || typeof value.tokenPrefix !== 'string'
    || typeof value.createdAt !== 'string') return null
  return {
    id: value.id,
    name: value.name,
    token: value.token,
    tokenPrefix: value.tokenPrefix,
    scopes: parseScopes(value.scopes),
    createdAt: value.createdAt,
    expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : undefined
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  if (!response.ok) {
    let message = response.status === 403 ? '您没有权限执行此访问凭证操作' : `请求失败（状态码 ${response.status}）`
    try {
      const payload: unknown = await response.json()
      if (isRecord(payload) && typeof payload.message === 'string') message = payload.message
    } catch (_) {
      // 非 JSON 错误响应使用状态码提示。
    }
    throw new Error(message)
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}

export async function fetchTokens(): Promise<ApiTokenSummary[]> {
  const payload: unknown = await request<unknown>('/api/v1/tokens')
  if (!Array.isArray(payload)) throw new Error('访问凭证列表响应格式错误')
  return payload.map(parseSummary).filter((item): item is ApiTokenSummary => item !== null)
}

export async function createToken(payload: ApiTokenCreateRequest): Promise<ApiTokenCreateResponse> {
  const result = await request<unknown>('/api/v1/tokens', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify(payload)
  })
  const parsed = parseCreated(result)
  if (!parsed) throw new Error('访问凭证创建响应格式错误')
  return parsed
}

export async function updateTokenExpiration(id: number, expiresAt: string | null): Promise<ApiTokenSummary> {
  const result = await request<unknown>(`/api/v1/tokens/${id}/expiration`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify({ expiresAt })
  })
  const parsed = parseSummary(result)
  if (!parsed) throw new Error('访问凭证过期时间响应格式错误')
  return parsed
}

export async function revokeToken(id: number): Promise<void> {
  await request<void>(`/api/v1/tokens/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': csrfCookie() }
  })
}

function csrfCookie(): string {
  if (typeof document === 'undefined') return ''
  const value = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='))
  return value ? decodeURIComponent(value.substring('XSRF-TOKEN='.length)) : ''
}
