import { CliError } from '../shared/errors.js'
import { EXIT_CODE } from '../shared/constants.js'
import { normalizeServiceUrl } from '../stores/config-store.js'

export interface CurrentIdentity {
  authenticated: boolean
  username: string
}

export interface PackageValidationResult {
  valid: boolean
  artifactDigest: string
  versionDigest: string
}

export interface PackageImportResult {
  requestId: string
  status: string
  assetId?: number
  artifactDigest?: string
  versionDigest?: string
  lifecycleState?: string
  duplicate: boolean
  consolePath?: string
  failureStage?: string
  failureCode?: string
  failureReason?: string
}

export interface ReviewSubmissionResult {
  id: number
  versionDigest: string
  status: string
}

export interface RuntimeIntegrationRegistrationRequest {
  scopeId: number
  runtimeKey: string
  runtimeVersion: string
  targetKey: string
  adapterVersion: string
  configurationDigest: string
  installationState: string
  healthStatus: string
  failureStage?: string
  errorCode?: string
  errorReason?: string
}

export interface RuntimeIntegrationEventRequest {
  eventId: string
  eventSequence: number
  eventType: string
  stage: string
  result: string
  installationState: string
  healthStatus: string
  occurredAt: string
  failureStage?: string
  errorCode?: string
  errorReason?: string
}

export interface RuntimeIntegrationServiceResult {
  integrationId: string
  lastEventSequence: number
}

export class SkillHubClient {
  readonly serviceUrl: string

  constructor(serviceUrl: string, private readonly token: string, private readonly fetchImpl: typeof fetch = fetch) {
    this.serviceUrl = normalizeServiceUrl(serviceUrl)
  }

  async currentIdentity(): Promise<CurrentIdentity> {
    let response: Response
    try {
      response = await this.fetchImpl(`${this.serviceUrl}/api/v1/session/current`, {
        headers: { Authorization: `Bearer ${this.token}` }
      })
    } catch (_error: unknown) {
      throw new CliError('无法连接 SkillHub 服务', 'SERVICE_UNREACHABLE', EXIT_CODE.network, { serviceUrl: this.serviceUrl })
    }
    if (response.status === 401 || response.status === 403) {
      throw new CliError('访问凭证无效或已失效', 'AUTHENTICATION_FAILED', EXIT_CODE.authentication)
    }
    if (!response.ok) {
      throw new CliError(`SkillHub 返回状态码 ${response.status}`, 'SERVICE_ERROR', EXIT_CODE.generic)
    }
    const value: unknown = await response.json()
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      throw new CliError('身份响应格式错误', 'INVALID_IDENTITY_RESPONSE', EXIT_CODE.generic)
    }
    const record = value as Record<string, unknown>
    if (record.authenticated !== true || typeof record.username !== 'string' || record.username.length === 0) {
      throw new CliError('访问凭证未关联有效账户', 'AUTHENTICATION_FAILED', EXIT_CODE.authentication)
    }
    return { authenticated: true, username: record.username }
  }

  async validateSkillPackage(
    archive: Uint8Array,
    artifactDigest: string,
    versionDigest: string,
    scopeId: number,
    requestId: string
  ): Promise<PackageValidationResult> {
    const form = packageForm(archive, artifactDigest, versionDigest, scopeId)
    const response = await this.request('/api/v1/assets/imports/package/validate', {
      method: 'POST',
      headers: { 'X-Request-Id': requestId },
      body: form
    }, requestId)
    return parseValidation(await response.json())
  }

  async importSkillPackage(
    archive: Uint8Array,
    artifactDigest: string,
    versionDigest: string,
    scopeId: number,
    requestId: string
  ): Promise<PackageImportResult> {
    const form = packageForm(archive, artifactDigest, versionDigest, scopeId)
    const response = await this.request('/api/v1/assets/imports/package', {
      method: 'POST',
      headers: { 'X-Request-Id': requestId },
      body: form
    }, requestId)
    return parseImport(await response.json())
  }

  async submitReview(versionDigest: string, comment: string | undefined, requestId: string): Promise<ReviewSubmissionResult> {
    const response = await this.request('/api/v1/reviews', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ versionDigest, comment })
    }, requestId)
    return parseReview(await response.json())
  }

  async registerRuntimeIntegration(
    registration: RuntimeIntegrationRegistrationRequest,
    requestId: string
  ): Promise<RuntimeIntegrationServiceResult> {
    const response = await this.request('/api/v1/runtime-integrations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(registration)
    }, requestId)
    return parseRuntimeIntegration(await response.json())
  }

  async reportRuntimeIntegrationEvent(
    integrationId: string,
    event: RuntimeIntegrationEventRequest,
    requestId: string
  ): Promise<RuntimeIntegrationServiceResult> {
    const response = await this.request(`/api/v1/runtime-integrations/${encodeURIComponent(integrationId)}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(event)
    }, requestId)
    return parseRuntimeIntegration(await response.json())
  }

  private async request(path: string, init: RequestInit, requestId: string): Promise<Response> {
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${this.token}`)
    headers.set('X-Request-Id', requestId)
    for (let attempt = 0; attempt < 3; attempt++) {
      let response: Response
      try {
        response = await this.fetchImpl(`${this.serviceUrl}${path}`, { ...init, headers })
      } catch (_error: unknown) {
        if (attempt < 2) {
          await delay(250 * (attempt + 1))
          continue
        }
        throw new CliError('无法连接 SkillHub 服务', 'SERVICE_UNREACHABLE', EXIT_CODE.network,
          { serviceUrl: this.serviceUrl, requestId })
      }
      if ((response.status === 429 || response.status >= 500) && attempt < 2) {
        await delay(250 * (attempt + 1))
        continue
      }
      if (!response.ok) throw await responseError(response, requestId)
      return response
    }
    throw new CliError('SkillHub 请求失败', 'SERVICE_ERROR', EXIT_CODE.generic, { requestId })
  }
}

function packageForm(
  archive: Uint8Array,
  artifactDigest: string,
  versionDigest: string,
  scopeId: number
): FormData {
  const form = new FormData()
  form.append('ownerScopeId', String(scopeId))
  form.append('artifactDigest', artifactDigest)
  form.append('versionDigest', versionDigest)
  form.append('sourceLocator', 'skillhub-cli')
  form.append('file', new Blob([archive], { type: 'application/zip' }), 'skill.zip')
  return form
}

function parseValidation(value: unknown): PackageValidationResult {
  const record = requireRecord(value, '服务端校验响应格式错误')
  if (record.valid !== true || typeof record.artifactDigest !== 'string' || typeof record.versionDigest !== 'string') {
    throw new CliError('服务端校验响应格式错误', 'INVALID_VALIDATION_RESPONSE', EXIT_CODE.generic)
  }
  return { valid: true, artifactDigest: record.artifactDigest, versionDigest: record.versionDigest }
}

function parseImport(value: unknown): PackageImportResult {
  const record = requireRecord(value, '上传响应格式错误')
  if (typeof record.requestId !== 'string' || typeof record.status !== 'string') {
    throw new CliError('上传响应格式错误', 'INVALID_IMPORT_RESPONSE', EXIT_CODE.generic)
  }
  return {
    requestId: record.requestId,
    status: record.status,
    assetId: typeof record.assetId === 'number' ? record.assetId : undefined,
    artifactDigest: typeof record.artifactDigest === 'string' ? record.artifactDigest : undefined,
    versionDigest: typeof record.versionDigest === 'string' ? record.versionDigest : undefined,
    lifecycleState: typeof record.lifecycleState === 'string' ? record.lifecycleState : undefined,
    duplicate: record.duplicate === true,
    consolePath: typeof record.consolePath === 'string' ? record.consolePath : undefined,
    failureStage: typeof record.failureStage === 'string' ? record.failureStage : undefined,
    failureCode: typeof record.failureCode === 'string' ? record.failureCode : undefined,
    failureReason: typeof record.failureReason === 'string' ? record.failureReason : undefined
  }
}

function parseReview(value: unknown): ReviewSubmissionResult {
  const record = requireRecord(value, '审核响应格式错误')
  if (typeof record.id !== 'number' || typeof record.versionDigest !== 'string' || typeof record.status !== 'string') {
    throw new CliError('审核响应格式错误', 'INVALID_REVIEW_RESPONSE', EXIT_CODE.generic)
  }
  return { id: record.id, versionDigest: record.versionDigest, status: record.status }
}

function parseRuntimeIntegration(value: unknown): RuntimeIntegrationServiceResult {
  const record = requireRecord(value, '运行时接入响应格式错误')
  if (typeof record.integrationId !== 'string' || record.integrationId.length === 0
      || typeof record.lastEventSequence !== 'number') {
    throw new CliError('运行时接入响应格式错误', 'INVALID_RUNTIME_INTEGRATION_RESPONSE', EXIT_CODE.generic)
  }
  return { integrationId: record.integrationId, lastEventSequence: record.lastEventSequence }
}

async function responseError(response: Response, requestId: string): Promise<CliError> {
  let payload: Record<string, unknown> = {}
  try {
    payload = requireRecord(await response.json(), '')
  } catch (_error: unknown) {
    payload = {}
  }
  const code = typeof payload.code === 'string' ? payload.code : `HTTP_${response.status}`
  const message = typeof payload.message === 'string' ? payload.message : `SkillHub 返回状态码 ${response.status}`
  const exitCode = response.status === 401 || response.status === 403
    ? EXIT_CODE.authentication
    : response.status === 400 || response.status === 409 ? EXIT_CODE.validation : EXIT_CODE.generic
  return new CliError(message, code, exitCode, { requestId, status: response.status })
}

function requireRecord(value: unknown, message: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new CliError(message, 'INVALID_SERVICE_RESPONSE', EXIT_CODE.generic)
  }
  return value as Record<string, unknown>
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}
