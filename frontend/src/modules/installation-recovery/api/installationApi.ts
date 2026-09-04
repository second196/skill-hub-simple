import type {
  InstallationInstance,
  InstallationOperation,
  InstallationPage,
  InstallationQuery,
  InstallationRequest,
  RevocationResult,
  RuntimeDefinition,
  RuntimeIntegration,
  RuntimeIntegrationQuery
} from '../types/installation'

export class InstallationApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
    this.name = 'InstallationApiError'
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  if (!response.ok) {
    throw new InstallationApiError(
      response.status === 403 ? '您没有权限查看或操作当前安装范围' : `请求失败（状态码 ${response.status}）`,
      response.status
    )
  }
  return await response.json() as T
}

function parseRuntimeIntegration(value: unknown): RuntimeIntegration | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const item = value as Record<string, unknown>
  if (typeof item.integrationId !== 'string' || typeof item.scopeId !== 'number'
    || typeof item.runtimeKey !== 'string' || typeof item.runtimeVersion !== 'string'
    || typeof item.targetKey !== 'string' || typeof item.adapterVersion !== 'string'
    || typeof item.configurationDigest !== 'string' || typeof item.installationState !== 'string'
    || typeof item.healthStatus !== 'string' || typeof item.lastEventSequence !== 'number') return null
  return item as unknown as RuntimeIntegration
}

function parseInstance(value: unknown): InstallationInstance | null {
  if (!value || typeof value !== 'object') return null
  const item = value as Record<string, unknown>
  if (typeof item.id !== 'number' || typeof item.assetId !== 'number' || typeof item.runtimeKey !== 'string'
    || typeof item.runtimeVersion !== 'string' || typeof item.scopeId !== 'number'
    || typeof item.overallState !== 'string') return null
  return item as unknown as InstallationInstance
}

export async function fetchInstallations(query: InstallationQuery): Promise<InstallationPage> {
  const params = new URLSearchParams({ scopeId: String(query.scopeId), page: String(query.page ?? 1), pageSize: String(query.pageSize ?? 20) })
  if (query.assetId !== undefined) params.set('assetId', String(query.assetId))
  if (query.runtimeKey) params.set('runtimeKey', query.runtimeKey)
  const value = await request<unknown>(`/api/v1/installations?${params.toString()}`)
  if (!value || typeof value !== 'object') throw new Error('安装列表响应格式错误')
  const page = value as Record<string, unknown>
  const items = Array.isArray(page.items) ? page.items.map(parseInstance).filter((item): item is InstallationInstance => item !== null) : []
  return { items, page: typeof page.page === 'number' ? page.page : 1, pageSize: typeof page.pageSize === 'number' ? page.pageSize : 20 }
}

export function fetchInstallation(id: number): Promise<InstallationInstance> {
  return request<InstallationInstance>(`/api/v1/installations/${id}`)
}

export function fetchLatestOperation(id: number): Promise<InstallationOperation | null> {
  return request<InstallationOperation | null>(`/api/v1/installations/${id}/operations/latest`)
}

export function fetchRuntimeMatrix(scopeId: number): Promise<RuntimeDefinition[]> {
  return request<RuntimeDefinition[]>(`/api/v1/runtime-matrix?scopeId=${encodeURIComponent(scopeId)}`)
}

export async function fetchRuntimeIntegrations(query: RuntimeIntegrationQuery): Promise<RuntimeIntegration[]> {
  const params = new URLSearchParams({ scopeId: String(query.scopeId) })
  if (query.runtimeKey) params.set('runtimeKey', query.runtimeKey)
  params.set('limit', String(query.limit ?? 50))
  const value = await request<unknown>(`/api/v1/runtime-integrations?${params.toString()}`)
  if (!Array.isArray(value)) throw new Error('运行时接入列表响应格式错误')
  return value.map(parseRuntimeIntegration).filter((item): item is RuntimeIntegration => item !== null)
}

export function requestInstallation(payload: InstallationRequest, requestId: string): Promise<InstallationOperation> {
  return request<InstallationOperation>('/api/v1/installations/operations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId, 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify(payload)
  })
}

export function requestSwitch(instanceId: number, payload: InstallationRequest, requestId: string): Promise<InstallationOperation> {
  return request<InstallationOperation>(`/api/v1/installations/${instanceId}/switch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId, 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify(payload)
  })
}

export function requestRollback(operationId: string, reason: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>('/api/v1/installations/rollback', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify({ operationId, reason })
  })
}

export function requestRevocation(payload: Record<string, unknown>, requestId: string): Promise<RevocationResult> {
  return request<RevocationResult>('/api/v1/installations/revocations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId, 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify(payload)
  })
}

function csrfCookie(): string {
  const value = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='))
  return value ? decodeURIComponent(value.substring('XSRF-TOKEN='.length)) : ''
}
