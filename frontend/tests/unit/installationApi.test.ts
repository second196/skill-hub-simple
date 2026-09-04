import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchInstallations, fetchRuntimeIntegrations } from '../../src/modules/installation-recovery/api/installationApi'

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

  it('读取服务端运行时接入数组并过滤格式错误的记录', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([
      {
        integrationId: 'integration-1', scopeId: 1, runtimeKey: 'codex-cli', runtimeVersion: '0.151.0',
        targetKey: 'a'.repeat(64), adapterVersion: '0.1.0', configurationDigest: 'b'.repeat(64),
        installationState: 'ACTION_REQUIRED', healthStatus: 'DEGRADED', lastEventSequence: 2
      },
      { integrationId: 'broken' }
    ]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchRuntimeIntegrations({ scopeId: 1, runtimeKey: 'codex-cli', limit: 50 })

    expect(result).toHaveLength(1)
    expect(result[0]?.runtimeVersion).toBe('0.151.0')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/runtime-integrations?scopeId=1&runtimeKey=codex-cli&limit=50',
      expect.objectContaining({ credentials: 'include' })
    )
  })
})
