export interface AdminAccount { id: number; username: string; enabled: boolean }
export interface Namespace { id: number; namespaceKey: string; displayName: string; ownerScopeId: number; status: string }
export interface NamespaceMember { principalId: number; username: string; roleKey: string }
export interface VersionTag { id: number; assetId: number; tagName: string; versionDigest: string; createdBy: string }

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  if (!response.ok) throw new Error(response.status === 403 ? '您没有系统管理权限' : `请求失败（状态码 ${response.status}）`)
  return response.status === 204 ? undefined as T : await response.json() as T
}

export const fetchAdminAccounts = () => request<AdminAccount[]>('/api/v1/admin/accounts')
export const fetchAdminNamespaces = () => request<Namespace[]>('/api/v1/admin/namespaces')
export const fetchAdminLabels = () => request<VersionTag[]>('/api/v1/admin/labels')
export const fetchAdminMembers = (namespaceKey: string) => request<NamespaceMember[]>(`/api/v1/admin/namespaces/${encodeURIComponent(namespaceKey)}/members`)
export const addNamespaceMember = (namespaceKey: string, username: string, roleKey: string) => request<void>(`/api/v1/admin/namespaces/${encodeURIComponent(namespaceKey)}/members`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() }, body: JSON.stringify({ username, roleKey }) })
export const removeNamespaceMember = (memberId: number) => request<void>(`/api/v1/admin/members/${memberId}`, { method: 'DELETE', headers: { 'X-XSRF-TOKEN': csrfCookie() } })
export const updateAccountStatus = (username: string, enabled: boolean) => request<void>(`/api/v1/admin/accounts/${encodeURIComponent(username)}/status?enabled=${enabled}`, { method: 'POST', headers: { 'X-XSRF-TOKEN': csrfCookie() } })
export const removeAdminLabel = (id: number) => request<void>(`/api/v1/admin/labels/${id}`, { method: 'DELETE', headers: { 'X-XSRF-TOKEN': csrfCookie() } })

function csrfCookie(): string {
  if (typeof document === 'undefined') return ''
  const value = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='))
  return value ? decodeURIComponent(value.substring('XSRF-TOKEN='.length)) : ''
}
