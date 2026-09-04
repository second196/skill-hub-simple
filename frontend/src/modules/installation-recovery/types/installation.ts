export interface InstallationInstance {
  id: number
  assetId: number
  currentVersionDigest?: string
  runtimeKey: string
  runtimeVersion: string
  targetType: string
  targetKey: string
  scopeId: number
  desiredState: string
  skillState: string
  trackerState: string
  overallState: string
  healthStatus: string
  lastErrorCode?: string
  lastErrorReason?: string
}

export interface InstallationOperation {
  id?: number
  operationId: string
  requestId: string
  operationType: string
  installationInstanceId: number
  previousVersionDigest?: string
  targetVersionDigest?: string
  operationStage: string
  operationState: string
  rollbackState: string
  failureStage?: string
  errorCode?: string
  errorReason?: string
}

export interface InstallationPage {
  items: InstallationInstance[]
  page: number
  pageSize: number
}

export interface RuntimeDefinition {
  runtimeKey: string
  runtimeVersion: string
  status: string
  capabilities: string
}

export interface RuntimeIntegration {
  integrationId: string
  scopeId: number
  runtimeKey: string
  runtimeVersion: string
  targetKey: string
  adapterVersion: string
  configurationDigest: string
  installationState: string
  healthStatus: string
  lastEventSequence: number
  failureStage?: string
  errorCode?: string
  errorReason?: string
  lastReportedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface RuntimeIntegrationQuery {
  scopeId: number
  runtimeKey?: string
  limit?: number
}

export interface InstallationQuery {
  scopeId: number
  assetId?: number
  runtimeKey?: string
  page?: number
  pageSize?: number
}

export interface InstallationRequest {
  assetId: number
  versionDigest: string
  scopeType: string
  scopeId: number
  runtimeKey: string
  runtimeVersion: string
  targetType: string
  targetKey: string
  trackerKey?: string
  trackerVersion?: string
  trackerConfigurationDigest?: string
}

export interface RevocationResult {
  versionDigest: string
  total: number
  queued: number
  operationIds: string[]
}
