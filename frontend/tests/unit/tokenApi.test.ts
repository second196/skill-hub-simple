import { afterEach, describe, expect, it, vi } from 'vitest'
import { createToken, fetchTokens, revokeToken, updateTokenExpiration } from '../../src/modules/token/api/tokenApi'

describe('访问凭证 API 契约', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('只保留合法的令牌列表项和作用域', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { id: 1, name: '发布工具', tokenPrefix: 'sk_abc', scopes: ['skill:publish', 'telemetry:write', 'invalid'], createdAt: '2026-09-02T00:00:00Z', status: 'ACTIVE' },
      { id: 'bad' }
    ]), { status: 200 })))

    const result = await fetchTokens()

    expect(result).toHaveLength(1)
    expect(result[0].scopes).toEqual(['skill:publish', 'telemetry:write'])
    expect(result[0].status).toBe('ACTIVE')
  })

  it('创建令牌只发送管理所需字段并解析一次性原文', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: 2, name: '自动化读取', token: 'sk_secret', tokenPrefix: 'sk_secret',
      scopes: ['skill:read'], createdAt: '2026-09-02T00:00:00Z'
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await createToken({ name: '自动化读取', scopes: ['skill:read'] })

    expect(result.token).toBe('sk_secret')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      name: '自动化读取', scopes: ['skill:read']
    })
  })

  it('支持更新期限和撤销，并保持请求方法与路径明确', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 3, name: '期限令牌', tokenPrefix: 'sk_dead', scopes: ['token:manage'],
        createdAt: '2026-09-02T00:00:00Z', status: 'ACTIVE'
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await updateTokenExpiration(3, null)
    await revokeToken(3)

    expect(fetchMock.mock.calls.map((call) => [call[0], call[1].method])).toEqual([
      ['/api/v1/tokens/3/expiration', 'PUT'],
      ['/api/v1/tokens/3', 'DELETE']
    ])
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({ expiresAt: null })
  })
})
