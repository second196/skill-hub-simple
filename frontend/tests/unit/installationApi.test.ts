import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchInstallations } from '../../src/modules/installation-recovery/api/installationApi'

describe('安装 API 契约', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('过滤格式错误的安装实例并保留分页信息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      page: 2,
      pageSize: 20,
      items: [{ id: 1, assetId: 2, runtimeKey: 'codex-cli', runtimeVersion: 'initial', scopeId: 1, overallState: 'SUCCEEDED' }, {}]
    }), { status: 200 })))
    const result = await fetchInstallations({ scopeId: 1, page: 2 })
    expect(result.page).toBe(2)
    expect(result.items).toHaveLength(1)
  })
})
