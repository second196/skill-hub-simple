export type LifecycleState = 'DRAFT' | 'CANDIDATE' | 'PUBLISHED' | 'OFFLINE' | 'EMERGENCY_REVOKED' | 'DEPRECATED'

export type ImportStatus = 'STARTED' | 'SUCCEEDED' | 'FAILED'

export interface SkillVersion {
  id: number
  assetId: number
  artifactId: number
  versionLabel: string
  versionDigest: string
  sourceType: string
  sourceLocator: string
  sourceSnapshotUri?: string
  metadataStatus: 'COMPLETE' | 'INCOMPLETE' | 'UNKNOWN'
  lifecycleState: LifecycleState | string
  createdBy?: string
}

export interface AssetCatalogItem {
  assetId: number
  assetKey: string
  name: string
  description: string
  status: string
  versionLabel?: string
  versionDigest?: string
  lifecycleState?: LifecycleState
  metadataStatus: 'COMPLETE' | 'INCOMPLETE' | 'UNKNOWN'
}

export interface AssetDetail extends AssetCatalogItem {
  ownerScopeId: number
}

export interface AssetImportRequest {
  requestId: string
  assetKey: string
  name: string
  description: string
  ownerScopeId: number
  versionLabel: string
  sourceType: string
  sourceLocator: string
  content: string
}

export interface SkillPackageImportRequest {
  requestId: string
  assetKey: string
  name: string
  description: string
  ownerScopeId: number
  versionLabel: string
  sourceLocator: string
}

export interface SkillPackageValidation {
  valid: boolean
  name: string
  description: string
  versionLabel: string
  artifactDigest: string
  versionDigest: string
  expandedSizeBytes: number
  manifest: Array<{ path: string; sizeBytes: number; contentDigest: string }>
}

export interface SkillFile {
  path: string
  required: boolean
  readStatus: string
  contentDigest?: string
}

export interface ImportAttempt {
  requestId: string
  status: ImportStatus | string
  assetId?: number
  versionDigest?: string
  failureStage?: string
  failureCode?: string
  failureReason?: string
}

export interface AssetCatalogQuery {
  keyword?: string
  lifecycleState?: LifecycleState
  page: number
  pageSize: number
}

export interface PageResult<T> {
  items: T[]
  page: number
  pageSize: number
}
