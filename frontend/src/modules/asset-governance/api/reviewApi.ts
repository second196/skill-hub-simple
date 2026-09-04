export interface ReviewTask {
  id: number
  versionDigest: string
  assetId: number
  scopeId: number
  assetName: string
  versionLabel: string
  applicantId: string
  reviewerId?: string
  status: string
  reviewComment?: string
  submittedAt?: string
  reviewedAt?: string
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  if (!response.ok) throw new Error(`请求失败（状态码 ${response.status}）`)
  return response.status === 204 ? undefined as T : await response.json() as T
}

export function fetchReviews(status = 'PENDING'): Promise<ReviewTask[]> {
  return request<ReviewTask[]>(`/api/v1/reviews?status=${encodeURIComponent(status)}`)
}

export async function fetchReview(id: number): Promise<ReviewTask> {
  const lists = await Promise.all(['PENDING', 'APPROVED', 'REJECTED'].map(fetchReviews))
  const task = lists.flat().find((item) => item.id === id)
  if (!task) throw new Error('审核任务不存在或当前账户无权查看')
  return task
}

export function submitReview(versionDigest: string, comment: string): Promise<ReviewTask> {
  return request<ReviewTask>('/api/v1/reviews', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify({ versionDigest, comment })
  })
}

export function finishReview(id: number, action: 'approve' | 'reject' | 'withdraw', comment: string): Promise<ReviewTask> {
  return request<ReviewTask>(`/api/v1/reviews/${id}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
    body: JSON.stringify({ comment })
  })
}

function csrfCookie(): string {
  if (typeof document === 'undefined') return ''
  const value = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='))
  return value ? decodeURIComponent(value.substring('XSRF-TOKEN='.length)) : ''
}
