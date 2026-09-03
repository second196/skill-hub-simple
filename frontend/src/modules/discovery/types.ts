export interface SkillDiscoveryQuery {
  keyword?: string
  namespaceKey?: string
  lifecycleState?: string
  tag?: string
  page: number
  pageSize: number
}

export interface SkillDiscoveryItem {
  assetId: number
  namespaceKey: string
  assetKey: string
  name: string
  description: string
  status: string
  versionLabel?: string
  versionDigest?: string
  lifecycleState?: string
  metadataStatus: string
}

export interface VersionDifference {
  path: string
  changeType: 'ADDED' | 'REMOVED' | 'MODIFIED' | string
  fromDigest?: string
  toDigest?: string
}

export interface VersionComparison {
  fromDigest: string
  toDigest: string
  fromVersion: string
  toVersion: string
  files: VersionDifference[]
}

export interface SkillNamespace {
  id: number
  namespaceKey: string
  displayName: string
  ownerScopeId: number
  status: string
}
