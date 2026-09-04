export type TokenScope = 'skill:read' | 'skill:publish' | 'telemetry:write' | 'token:manage'

export type TokenStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED'

export interface ApiTokenSummary {
  id: number
  name: string
  tokenPrefix: string
  scopes: TokenScope[]
  createdAt: string
  expiresAt?: string
  lastUsedAt?: string
  revokedAt?: string
  status: TokenStatus
}

export interface ApiTokenCreateRequest {
  name: string
  scopes: TokenScope[]
  expiresAt?: string
}

export interface ApiTokenCreateResponse {
  id: number
  name: string
  token: string
  tokenPrefix: string
  scopes: TokenScope[]
  createdAt: string
  expiresAt?: string
}
