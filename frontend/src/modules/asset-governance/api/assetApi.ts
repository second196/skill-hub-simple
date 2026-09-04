import type {
  AssetCatalogItem,
  AssetCatalogQuery,
  AssetDetail,
  AssetImportRequest,
  ImportAttempt,
  PageResult,
  SkillVersion,
  SkillFile,
  SkillPackageImportRequest,
  SkillPackageValidation
} from '../types/asset'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function parseItem(value: unknown): AssetCatalogItem | null {
  if (!isRecord(value) || typeof value.assetId !== 'number' || typeof value.assetKey !== 'string'
    || typeof value.name !== 'string' || typeof value.description !== 'string'
    || typeof value.status !== 'string') {
    return null
  }
  return {
    assetId: value.assetId,
    assetKey: value.assetKey,
    name: value.name,
    description: value.description,
    status: value.status,
    versionLabel: typeof value.versionLabel === 'string' ? value.versionLabel : undefined,
    versionDigest: typeof value.versionDigest === 'string' ? value.versionDigest : undefined,
    lifecycleState: typeof value.lifecycleState === 'string' ? value.lifecycleState as AssetCatalogItem['lifecycleState'] : undefined,
    metadataStatus: value.metadataStatus === 'COMPLETE' || value.metadataStatus === 'INCOMPLETE' ? value.metadataStatus : 'UNKNOWN'
  }
}

export function parseDetail(value: unknown): AssetDetail | null {
  const item = parseItem(value)
  if (!item || !isRecord(value) || typeof value.ownerScopeId !== 'number') return null
  return { ...item, ownerScopeId: value.ownerScopeId }
}

export function parseVersion(value: unknown): SkillVersion | null {
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.assetId !== 'number'
    || typeof value.artifactId !== 'number' || typeof value.versionLabel !== 'string'
    || typeof value.versionDigest !== 'string' || typeof value.sourceType !== 'string'
    || typeof value.sourceLocator !== 'string' || typeof value.lifecycleState !== 'string') return null
  return {
    id: value.id,
    assetId: value.assetId,
    artifactId: value.artifactId,
    versionLabel: value.versionLabel,
    versionDigest: value.versionDigest,
    sourceType: value.sourceType,
    sourceLocator: value.sourceLocator,
    sourceSnapshotUri: typeof value.sourceSnapshotUri === 'string' ? value.sourceSnapshotUri : undefined,
    metadataStatus: value.metadataStatus === 'COMPLETE' || value.metadataStatus === 'INCOMPLETE' ? value.metadataStatus : 'UNKNOWN',
    lifecycleState: value.lifecycleState,
    createdBy: typeof value.createdBy === 'string' ? value.createdBy : undefined
  }
}

export function parseImportAttempt(value: unknown): ImportAttempt | null {
  if (!isRecord(value) || typeof value.requestId !== 'string' || typeof value.status !== 'string') return null
  return {
    requestId: value.requestId,
    status: value.status,
    assetId: typeof value.assetId === 'number' ? value.assetId : undefined,
    versionDigest: typeof value.versionDigest === 'string' ? value.versionDigest : undefined,
    failureStage: typeof value.failureStage === 'string' ? value.failureStage : undefined,
    failureCode: typeof value.failureCode === 'string' ? value.failureCode : undefined,
    failureReason: typeof value.failureReason === 'string' ? value.failureReason : undefined
  }
}

export async function fetchAssetCatalog(query: AssetCatalogQuery): Promise<PageResult<AssetCatalogItem>> {
  const params = new URLSearchParams({ page: String(query.page), pageSize: String(query.pageSize) })
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.lifecycleState) params.set('lifecycleState', query.lifecycleState)
  const response = await fetch(`/api/v1/assets?${params.toString()}`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权访问资产目录' : '资产目录加载失败')
  const payload: unknown = await response.json()
  if (!isRecord(payload) || !Array.isArray(payload.items)) throw new Error('资产目录响应格式错误')
  return {
    items: payload.items.map(parseItem).filter((item): item is AssetCatalogItem => item !== null),
    page: typeof payload.page === 'number' ? payload.page : query.page,
    pageSize: typeof payload.pageSize === 'number' ? payload.pageSize : query.pageSize
  }
}

export async function importAsset(payload: AssetImportRequest): Promise<ImportAttempt> {
  const response = await fetch('/api/v1/assets/imports', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify(payload)
  })
  if (!response.ok) throw new Error(response.status === 403 ? '无权导入技能' : '技能导入请求失败')
  const result = parseImportAttempt(await response.json())
  if (!result) throw new Error('导入结果响应格式错误')
  return result
}

export async function importSkillPackage(payload: SkillPackageImportRequest, file: File): Promise<ImportAttempt> {
  const form = new FormData()
  Object.entries(payload).forEach(([key, value]) => form.append(key, String(value)))
  form.append('file', file)
  const response = await fetch('/api/v1/assets/imports/package', {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-XSRF-TOKEN': csrfCookie() },
    body: form
  })
  if (!response.ok) throw new Error(response.status === 403 ? '无权导入技能包' : '技能包导入请求失败')
  const result = parseImportAttempt(await response.json())
  if (!result) throw new Error('导入结果响应格式错误')
  return result
}

export async function validateSkillPackage(file: File): Promise<SkillPackageValidation> {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch('/api/v1/assets/imports/package/validate', {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-XSRF-TOKEN': csrfCookie() },
    body: form
  })
  if (!response.ok) {
    let message = response.status === 403 ? '无权校验技能包' : '技能包校验失败'
    try {
      const payload: unknown = await response.json()
      if (isRecord(payload) && typeof payload.message === 'string') message = payload.message
    } catch (_) {
      // 非 JSON 错误响应使用稳定中文提示。
    }
    throw new Error(message)
  }
  const payload: unknown = await response.json()
  if (!isRecord(payload) || payload.valid !== true || typeof payload.name !== 'string'
    || typeof payload.description !== 'string' || typeof payload.versionLabel !== 'string'
    || typeof payload.artifactDigest !== 'string' || typeof payload.versionDigest !== 'string'
    || typeof payload.expandedSizeBytes !== 'number' || !Array.isArray(payload.manifest)) {
    throw new Error('技能包校验响应格式错误')
  }
  const manifest: SkillPackageValidation['manifest'] = []
  for (const entry of payload.manifest) {
    if (!isRecord(entry) || typeof entry.path !== 'string'
      || typeof entry.sizeBytes !== 'number' || typeof entry.contentDigest !== 'string') {
      throw new Error('技能包校验响应格式错误')
    }
    manifest.push({ path: entry.path, sizeBytes: entry.sizeBytes, contentDigest: entry.contentDigest })
  }
  return {
    valid: true,
    name: payload.name,
    description: payload.description,
    versionLabel: payload.versionLabel,
    artifactDigest: payload.artifactDigest,
    versionDigest: payload.versionDigest,
    expandedSizeBytes: payload.expandedSizeBytes,
    manifest
  }
}

export async function fetchImportAttempt(requestId: string): Promise<ImportAttempt> {
  const response = await fetch(`/api/v1/assets/imports/${encodeURIComponent(requestId)}`, { credentials: 'include' })
  if (!response.ok) throw new Error('导入结果查询失败')
  const result = parseImportAttempt(await response.json())
  if (!result) throw new Error('导入结果响应格式错误')
  return result
}

export async function fetchAssetDetail(assetId: number): Promise<AssetDetail> {
  const response = await fetch(`/api/v1/assets/${assetId}`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看该资产' : '资产详情加载失败')
  const result = parseDetail(await response.json())
  if (!result) throw new Error('资产详情响应格式错误')
  return result
}

export async function fetchAssetVersions(assetId: number): Promise<SkillVersion[]> {
  const response = await fetch(`/api/v1/assets/${assetId}/versions`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看资产版本' : '资产版本加载失败')
  const payload: unknown = await response.json()
  if (!Array.isArray(payload)) throw new Error('资产版本响应格式错误')
  return payload.map(parseVersion).filter((item): item is SkillVersion => item !== null)
}

export async function fetchVersion(versionDigest: string): Promise<SkillVersion> {
  const response = await fetch(`/api/v1/assets/versions/${encodeURIComponent(versionDigest)}`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看该版本' : '版本详情加载失败')
  const result = parseVersion(await response.json())
  if (!result) throw new Error('版本详情响应格式错误')
  return result
}

export async function transitionVersion(versionDigest: string, targetState: string, reason: string): Promise<SkillVersion> {
  const response = await fetch(`/api/v1/assets/versions/${encodeURIComponent(versionDigest)}/transition`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify({ targetState, reason })
  })
  if (!response.ok) throw new Error(response.status === 403 ? '无权变更版本状态' : '版本状态变更失败')
  const result = parseVersion(await response.json())
  if (!result) throw new Error('版本状态响应格式错误')
  return result
}

export async function fetchVersionFiles(versionDigest: string): Promise<SkillFile[]> {
  const response = await fetch(`/api/v1/assets/versions/${encodeURIComponent(versionDigest)}/files`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看版本文件' : '版本文件加载失败')
  const payload: unknown = await response.json()
  if (!Array.isArray(payload)) throw new Error('版本文件响应格式错误')
  return payload.filter(isRecord).filter((item): item is Record<string, unknown> => typeof item.path === 'string').map((item) => ({
    path: item.path as string,
    required: item.required === true,
    readStatus: typeof item.readStatus === 'string' ? item.readStatus : 'UNKNOWN',
    contentDigest: typeof item.contentDigest === 'string' ? item.contentDigest : undefined
  }))
}

export async function fetchVersionFile(versionDigest: string, path: string): Promise<string> {
  const response = await fetch(`/api/v1/assets/versions/${encodeURIComponent(versionDigest)}/file?path=${encodeURIComponent(path)}`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看文件' : '文件读取失败')
  return response.text()
}

export function versionPackageUrl(versionDigest: string): string {
  return `/api/v1/assets/versions/${encodeURIComponent(versionDigest)}/download`
}

function csrfCookie(): string {
  if (typeof document === 'undefined') return ''
  const value = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='))
  return value ? decodeURIComponent(value.substring('XSRF-TOKEN='.length)) : ''
}
