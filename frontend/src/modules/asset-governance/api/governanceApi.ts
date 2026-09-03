import type { AuditRecord, ReleaseDecision, ReleasePolicy } from '../types/governance'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  if (!response.ok) throw new Error(`请求失败（状态码 ${response.status}）`)
  return await response.json() as T
}

export function fetchDecision(id: number): Promise<ReleaseDecision> {
  return request<ReleaseDecision>(`/api/v1/releases/decisions/${id}`)
}

export function fetchPolicies(): Promise<ReleasePolicy[]> {
  return request<ReleasePolicy[]>('/api/v1/governance/policies')
}

export function fetchRetentionPolicies(): Promise<ReleasePolicy[]> {
  return request<ReleasePolicy[]>('/api/v1/governance/retention-policies')
}

export function fetchAudits(): Promise<AuditRecord[]> {
  return request<AuditRecord[]>('/api/v1/audits')
}
