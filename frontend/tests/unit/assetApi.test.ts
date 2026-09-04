import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  fetchAssetCatalog,
  fetchAssetDetail,
  fetchAssetVersions,
  fetchVersionFiles,
  importSkillPackage,
  importAsset,
  parseDetail,
  parseImportAttempt,
  parseItem,
  parseVersion,
  transitionVersion,
  validateSkillPackage
} from '../../src/modules/asset-governance/api/assetApi'
import type { AssetImportRequest } from '../../src/modules/asset-governance/types/asset'

describe('asset catalog contract', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('keeps missing metadata explicit instead of guessing a value', () => {
    const item = parseItem({ assetId: 1, assetKey: 'demo', name: 'Demo', description: 'Skill', status: 'ACTIVE' })
    expect(item?.metadataStatus).toBe('UNKNOWN')
    expect(item?.versionDigest).toBeUndefined()
  })

  it('filters malformed rows and preserves page information', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      page: 2, pageSize: 20, items: [{ assetId: 1, assetKey: 'demo', name: 'Demo', description: 'Skill', status: 'ACTIVE' }, {}]
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    const result = await fetchAssetCatalog({ page: 2, pageSize: 20 })
    expect(result.page).toBe(2)
    expect(result.items).toHaveLength(1)
  })

  it('parses import, detail and version responses without guessing missing fields', () => {
    expect(parseImportAttempt({ requestId: 'r-1', status: 'FAILED', failureReason: '文件不可读' })?.failureReason).toBe('文件不可读')
    expect(parseDetail({ assetId: 7, assetKey: 'demo', name: 'Demo', description: '描述', status: 'ACTIVE', ownerScopeId: 1 })?.ownerScopeId).toBe(1)
    expect(parseVersion({ id: 2, assetId: 7, artifactId: 3, versionLabel: '1.0.0', versionDigest: 'abc', sourceType: 'FILE', sourceLocator: 'SKILL.md', lifecycleState: 'CANDIDATE' })?.metadataStatus).toBe('UNKNOWN')
  })

  it('submits import and lifecycle requests with the existing session contract', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      requestId: 'r-1', status: 'SUCCEEDED', assetId: 7, versionDigest: 'abc'
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const payload: AssetImportRequest = { requestId: 'r-1', assetKey: 'demo', name: 'Demo', description: '描述', ownerScopeId: 1, versionLabel: '1.0.0', sourceType: 'FILE', sourceLocator: 'SKILL.md', content: '# Demo' }
    await importAsset(payload)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/assets/imports')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual(payload)
  })

  it('submits a Skill package as multipart data without a JSON content body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ requestId: 'r-zip', status: 'SUCCEEDED' }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['zip-bytes'], 'demo.zip', { type: 'application/zip' })
    await importSkillPackage({ requestId: 'r-zip', assetKey: 'demo', name: 'Demo', description: '描述', ownerScopeId: 1, versionLabel: '1.0.0', sourceLocator: 'demo.zip' }, file)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/assets/imports/package')
    expect(fetchMock.mock.calls[0][1].body).toBeInstanceOf(FormData)
    expect((fetchMock.mock.calls[0][1].body as FormData).get('file')).toBe(file)
  })

  it('validates a Skill package through the server before publication', async () => {
    const responseBody = {
      valid: true,
      name: '文档评审',
      description: '检查文档结构和规范',
      versionLabel: '2.1.0',
      artifactDigest: 'a'.repeat(64),
      versionDigest: 'b'.repeat(64),
      expandedSizeBytes: 128,
      manifest: [{ path: 'SKILL.md', sizeBytes: 128, contentDigest: 'c'.repeat(64) }]
    }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(responseBody), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['zip-bytes'], 'demo.zip', { type: 'application/zip' })

    await expect(validateSkillPackage(file)).resolves.toEqual(responseBody)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/assets/imports/package/validate')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST', credentials: 'include' })
    expect((fetchMock.mock.calls[0][1].body as FormData).get('file')).toBe(file)
  })

  it('surfaces the server package validation reason without simulating success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'SENSITIVE_FILE_NOT_ALLOWED',
      message: 'Skill 包不能包含敏感文件：.env'
    }), { status: 400, headers: { 'Content-Type': 'application/json' } })))
    const file = new File(['zip-bytes'], 'unsafe.zip', { type: 'application/zip' })

    await expect(validateSkillPackage(file)).rejects.toThrow('Skill 包不能包含敏感文件：.env')
  })

  it('rejects a malformed validation manifest instead of hiding invalid rows', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      valid: true,
      name: '文档评审',
      description: '检查文档结构和规范',
      versionLabel: '2.1.0',
      artifactDigest: 'a'.repeat(64),
      versionDigest: 'b'.repeat(64),
      expandedSizeBytes: 128,
      manifest: [{ path: 'SKILL.md', sizeBytes: '128', contentDigest: 'c'.repeat(64) }]
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    const file = new File(['zip-bytes'], 'malformed.zip', { type: 'application/zip' })

    await expect(validateSkillPackage(file)).rejects.toThrow('技能包校验响应格式错误')
  })

  it('parses a version file manifest and keeps the file digest optional', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { path: 'SKILL.md', required: true, readStatus: 'READABLE', contentDigest: 'abc' },
      { path: 'README.md', required: false, readStatus: 'READABLE' }
    ]), { status: 200 })))
    const files = await fetchVersionFiles('a'.repeat(64))
    expect(files).toEqual([
      { path: 'SKILL.md', required: true, readStatus: 'READABLE', contentDigest: 'abc' },
      { path: 'README.md', required: false, readStatus: 'READABLE', contentDigest: undefined }
    ])
  })

  it('loads an authorized asset detail and version list', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ assetId: 7, assetKey: 'demo', name: 'Demo', description: '描述', status: 'ACTIVE', ownerScopeId: 1 }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 2, assetId: 7, artifactId: 3, versionLabel: '1.0.0', versionDigest: 'abc', sourceType: 'FILE', sourceLocator: 'SKILL.md', lifecycleState: 'CANDIDATE' }]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    expect((await fetchAssetDetail(7)).assetKey).toBe('demo')
    expect((await fetchAssetVersions(7))).toHaveLength(1)
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual(['/api/v1/assets/7', '/api/v1/assets/7/versions'])
  })

  it('posts a version transition and parses the returned version', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 2, assetId: 7, artifactId: 3, versionLabel: '1.0.0', versionDigest: 'abc', sourceType: 'FILE', sourceLocator: 'SKILL.md', lifecycleState: 'PUBLISHED' }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const version = await transitionVersion('abc', 'PUBLISHED', '审核通过')
    expect(version.lifecycleState).toBe('PUBLISHED')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({ targetState: 'PUBLISHED', reason: '审核通过' })
  })
})
