import type { AssetDetail, SkillVersion } from '../asset-governance/types/asset'
import type { SkillDiscoveryItem, SkillDiscoveryQuery, SkillNamespace, VersionComparison } from './types'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseItem(value: unknown): SkillDiscoveryItem | null {
  if (!isRecord(value) || typeof value.assetId !== 'number' || typeof value.namespaceKey !== 'string'
    || typeof value.assetKey !== 'string' || typeof value.name !== 'string' || typeof value.description !== 'string') return null
  return {
    assetId: value.assetId,
    namespaceKey: value.namespaceKey,
    assetKey: value.assetKey,
    name: value.name,
    description: value.description,
    status: typeof value.status === 'string' ? value.status : 'UNKNOWN',
    versionLabel: typeof value.versionLabel === 'string' ? value.versionLabel : undefined,
    versionDigest: typeof value.versionDigest === 'string' ? value.versionDigest : undefined,
    lifecycleState: typeof value.lifecycleState === 'string' ? value.lifecycleState : undefined,
    metadataStatus: typeof value.metadataStatus === 'string' ? value.metadataStatus : 'UNKNOWN'
  }
}

export async function fetchSkillDiscovery(query: SkillDiscoveryQuery): Promise<{ items: SkillDiscoveryItem[]; page: number; pageSize: number }> {
  const params = new URLSearchParams({ page: String(query.page), pageSize: String(query.pageSize) })
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.namespaceKey) params.set('namespaceKey', query.namespaceKey)
  if (query.lifecycleState) params.set('lifecycleState', query.lifecycleState)
  if (query.tag) params.set('tag', query.tag)
  const response = await fetch(`/api/v1/skills/search?${params.toString()}`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权搜索技能' : '技能搜索失败')
  const payload: unknown = await response.json()
  if (!isRecord(payload) || !Array.isArray(payload.items)) throw new Error('技能搜索响应格式错误')
  return {
    items: payload.items.map(parseItem).filter((item): item is SkillDiscoveryItem => item !== null),
    page: typeof payload.page === 'number' ? payload.page : query.page,
    pageSize: typeof payload.pageSize === 'number' ? payload.pageSize : query.pageSize
  }
}

export async function fetchSkillDetail(namespaceKey: string, slug: string): Promise<AssetDetail> {
  const response = await fetch(`/api/v1/skills/${encodeURIComponent(namespaceKey)}/${encodeURIComponent(slug)}`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看技能' : '技能详情加载失败')
  const payload: unknown = await response.json()
  if (!isRecord(payload) || typeof payload.assetId !== 'number' || typeof payload.assetKey !== 'string'
    || typeof payload.name !== 'string' || typeof payload.description !== 'string' || typeof payload.ownerScopeId !== 'number') {
    throw new Error('技能详情响应格式错误')
  }
  return {
    assetId: payload.assetId,
    assetKey: payload.assetKey,
    name: payload.name,
    description: payload.description,
    ownerScopeId: payload.ownerScopeId,
    status: typeof payload.status === 'string' ? payload.status : 'UNKNOWN',
    versionLabel: typeof payload.versionLabel === 'string' ? payload.versionLabel : undefined,
    versionDigest: typeof payload.versionDigest === 'string' ? payload.versionDigest : undefined,
    lifecycleState: typeof payload.lifecycleState === 'string' ? payload.lifecycleState as AssetDetail['lifecycleState'] : undefined,
    metadataStatus: typeof payload.metadataStatus === 'string' ? payload.metadataStatus as AssetDetail['metadataStatus'] : 'UNKNOWN'
  }
}

export async function compareSkillVersions(namespaceKey: string, slug: string, from: string, to: string): Promise<VersionComparison> {
  const params = new URLSearchParams({ from, to })
  const response = await fetch(`/api/v1/skills/${encodeURIComponent(namespaceKey)}/${encodeURIComponent(slug)}/versions/compare?${params.toString()}`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权比较技能版本' : '版本比较失败')
  const payload: unknown = await response.json()
  if (!isRecord(payload) || typeof payload.fromDigest !== 'string' || typeof payload.toDigest !== 'string' || !Array.isArray(payload.files)) throw new Error('版本比较响应格式错误')
  return {
    fromDigest: payload.fromDigest,
    toDigest: payload.toDigest,
    fromVersion: typeof payload.fromVersion === 'string' ? payload.fromVersion : '未知版本',
    toVersion: typeof payload.toVersion === 'string' ? payload.toVersion : '未知版本',
    files: payload.files.filter(isRecord).filter((file): file is Record<string, unknown> => typeof file.path === 'string').map((file) => ({ path: file.path as string, changeType: typeof file.changeType === 'string' ? file.changeType as VersionComparison['files'][number]['changeType'] : 'UNKNOWN', fromDigest: typeof file.fromDigest === 'string' ? file.fromDigest : undefined, toDigest: typeof file.toDigest === 'string' ? file.toDigest : undefined }))
  }
}

export async function fetchSkillNamespaces(): Promise<SkillNamespace[]> {
  const response = await fetch('/api/v1/skills/namespaces', { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看命名空间' : '命名空间加载失败')
  const payload: unknown = await response.json()
  if (!Array.isArray(payload)) throw new Error('命名空间响应格式错误')
  return payload.filter(isRecord).filter((value): value is Record<string, unknown> =>
    typeof value.id === 'number' && typeof value.namespaceKey === 'string'
  ).map((value) => ({
    id: value.id as number,
    namespaceKey: value.namespaceKey as string,
    displayName: typeof value.displayName === 'string' ? value.displayName : value.namespaceKey as string,
    ownerScopeId: typeof value.ownerScopeId === 'number' ? value.ownerScopeId : 1,
    status: typeof value.status === 'string' ? value.status : 'ACTIVE'
  }))
}

export async function fetchSkillNamespaceMembers(namespaceKey: string): Promise<Array<{ principalId: number; username: string; roleKey: string }>> {
  const response = await fetch(`/api/v1/skills/namespaces/${encodeURIComponent(namespaceKey)}/members`, { credentials: 'include' })
  if (!response.ok) throw new Error(response.status === 403 ? '无权查看命名空间成员' : '成员加载失败')
  const payload: unknown = await response.json()
  if (!Array.isArray(payload)) throw new Error('成员响应格式错误')
  return payload.filter(isRecord).filter((value): value is Record<string, unknown> =>
    typeof value.principalId === 'number' && typeof value.username === 'string' && typeof value.roleKey === 'string'
  ).map((value) => ({ principalId: value.principalId as number, username: value.username as string, roleKey: value.roleKey as string }))
}

export type { SkillVersion }
