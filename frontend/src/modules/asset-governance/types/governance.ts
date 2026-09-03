export interface ReleaseDecision {
  id: number
  versionDigest: string
  scopeType: string
  scopeId: number
  policyVersion: string
  releaseMode: string
  decisionState: string
  blockingReasons?: string
}

export interface ReleasePolicy {
  id?: number
  scopeType: string
  scopeId: number
  policyVersion: string
  effectiveAt: string
  minimumValidCases: number
  grayRatio: number
  observationWindowSeconds: number
  minimumValidCalls: number
  rollbackConditions: string
  autoReleaseConditions: string
}

export interface AuditRecord {
  id: number
  actorId: string
  actorType: string
  action: string
  objectType: string
  objectId: string
  reason?: string
  policyVersion?: string
  occurredAt: string
}

export interface ReleaseBinding {
  id: number
  assetId: number
  versionDigest: string
  scopeType: string
  scopeId: number
  bindingState: string
  current: boolean
  policyVersion: string
}
