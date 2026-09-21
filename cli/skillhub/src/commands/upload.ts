import { access, cp, lstat, mkdir, mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, join, resolve } from 'node:path'
import { prepareSkillPackage, inspectSkillPackage } from '../services/skill-package-service.js'
import { assertVersionBumpRequired } from '../services/version-gate.js'
import { apiRequest } from '../clients/api-client.js'
import { CliError } from '../shared/errors.js'

const WRITE_BACK_FLOW = [
  '禁止：仅在临时目录写入 package.json/元数据并用临时路径上传成功，却不覆盖回技能源目录',
  '官方流程：',
  '1. 创建临时目录并完整复制 Skill（仅当需要在只读源上补全时）',
  '2. 在临时目录补全 package.json / SKILL.md 的 version 与 category',
  '3. skillhub prepare <技能源目录> --complete-from <临时目录>  # 强制覆盖回源并删除临时目录',
  '4. skillhub verify-source <技能源目录> --json                 # 源目录磁盘必须完整',
  '5. skillhub upload <技能源目录> --json [--source-dir <技能源目录>]',
  '6. skillhub list --json'
]

export interface UploadOptions {
  inputPaths: string[]
  serviceUrl: string
  category?: string
  name?: string
  description?: string
  json: boolean
  /** Run local package completeness check before network upload. Default true. */
  check?: boolean
  /**
   * Skill source of record on disk (usually the same as input path).
   * After upload, this path MUST still pass verify-source.
   */
  sourceDir?: string
}

/**
 * Upload pipeline:
 *   check package completeness on the path being uploaded
 *   → prepareSkillPackage (version/category from package content only)
 *   → assertVersionBumpRequired
 *   → POST /api/skills
 *   → list verification + verify-source on input path (and optional --source-dir)
 *
 * Success requires: platform row matches AND source directory metadata remains complete.
 * Temp-only packages without write-back fail verify-source.
 */
export async function uploadCommand(options: UploadOptions): Promise<string> {
  const results: Array<Record<string, unknown>> = []
  for (const inputPath of options.inputPaths) {
    try {
      if (options.check !== false) {
        const inspection = await inspectSkillPackage(inputPath)
        if (!inspection.ok) {
          throw new CliError(
            inspection.messages[0] || 'Skill 源目录元数据不完整，拒绝上传',
            'SOURCE_METADATA_INCOMPLETE',
            5,
            {
              missing: inspection.missing,
              packageKind: inspection.packageKind,
              prepareFlow: WRITE_BACK_FLOW
            }
          )
        }
      }
      const prepared = await prepareSkillPackage(inputPath, {}, {
        name: options.name,
        description: options.description,
        category: options.category
      })
      await assertVersionBumpRequired({
        serviceUrl: options.serviceUrl,
        metadata: prepared.metadata
      })
      const form = new FormData()
      form.append('file', new Blob([prepared.archive]), `${basename(inputPath)}.zip`)
      if (prepared.metadata.category) form.append('category', prepared.metadata.category)
      else if (options.category) form.append('category', options.category)
      const result = await apiRequest<Record<string, unknown>>(options.serviceUrl, '/api/skills', {
        method: 'POST',
        body: form as unknown as BodyInit
      })
      const version = prepared.metadata.version
      const slug = String((result as { slug?: unknown }).slug ?? '')
      const category = String((result as { category?: unknown }).category ?? prepared.metadata.category ?? options.category ?? '')
      const platformVerify = await verifyUpload(options.serviceUrl, slug, version, category)
      const sourceVerify = await verifySourcePaths([inputPath, options.sourceDir].filter(Boolean) as string[])
      const publishOk = platformVerify.ok && sourceVerify.ok
      results.push({
        ok: publishOk,
        inputPath,
        sourceDir: options.sourceDir || inputPath,
        version,
        slug,
        category,
        platformVerify,
        sourceVerify,
        publishOk,
        ...result
      })
    } catch (error: unknown) {
      results.push({
        ok: false,
        inputPath,
        message: error instanceof Error ? error.message : '上传失败',
        code: error instanceof CliError ? error.code : 'UPLOAD_FAILED',
        details: error instanceof CliError ? error.details : undefined
      })
    }
  }

  const succeeded = results.filter((item) => item.ok === true)
  const failed = results.filter((item) => item.ok !== true)
  if (failed.length > 0) process.exitCode = 1
  if (options.json) {
    return JSON.stringify({
      ok: failed.length === 0,
      total: results.length,
      succeeded: succeeded.length,
      failed: failed.length,
      results
    })
  }
  const lines = [`上传完成：成功 ${succeeded.length} 个，失败 ${failed.length} 个`]
  for (const item of succeeded) {
    lines.push(`成功：${String(item.inputPath)} → ${String(item.name)}（${String(item.slug)}）v${String(item.version)} [${String(item.category)}]`)
  }
  for (const item of failed) {
    lines.push(`失败：${String(item.inputPath)} → ${String(item.message || '')}`)
    const sourceVerify = item.sourceVerify as { ok?: boolean; message?: string; paths?: Array<{ path: string; ok: boolean; message?: string }> } | undefined
    const platformVerify = item.platformVerify as { ok?: boolean; message?: string } | undefined
    if (platformVerify && platformVerify.ok === false) {
      lines.push(`  平台核验失败：${String(platformVerify.message || '')}`)
    }
    if (sourceVerify && sourceVerify.ok === false) {
      lines.push(`  源目录核验失败：${String(sourceVerify.message || '')}`)
      for (const pathResult of sourceVerify.paths || []) {
        if (!pathResult.ok) lines.push(`    - ${pathResult.path}: ${String(pathResult.message || '不完整')}`)
      }
      lines.push('  说明：临时目录写入后未覆盖回技能源目录时，不能算发布成功。')
    }
  }
  return lines.join('\n')
}

async function pathExists(path: string): Promise<boolean> {
  try {
    await access(path)
    return true
  } catch {
    return false
  }
}

export interface SourceVerifyPathResult {
  path: string
  ok: boolean
  packageKind?: 'skill-md' | 'composite'
  hasRootSkillMd?: boolean
  hasPackageJson?: boolean
  metadata?: Record<string, unknown>
  message?: string
  missing?: string[]
}

export interface SourceVerifyResult {
  ok: boolean
  message?: string
  paths: SourceVerifyPathResult[]
}

/** Verify skill source directories still contain complete package metadata on disk. */
export async function verifySourcePaths(sourcePaths: string[]): Promise<SourceVerifyResult> {
  const unique = [...new Set(sourcePaths.map((item) => resolve(item)))]
  const paths: SourceVerifyPathResult[] = []
  for (const sourcePath of unique) {
    paths.push(await verifyOneSource(sourcePath))
  }
  const failed = paths.filter((item) => !item.ok)
  if (failed.length === 0) {
    return { ok: true, paths }
  }
  return {
    ok: false,
    message: '技能源目录元数据不完整（存在仅在临时目录补全、未 write-back 的风险）',
    paths
  }
}

async function verifyOneSource(sourcePath: string): Promise<SourceVerifyPathResult> {
  const stat = await lstat(sourcePath).catch(() => null)
  if (!stat) {
    return { path: sourcePath, ok: false, message: '路径不存在或不可读' }
  }
  const hasRootSkillMd = stat.isDirectory()
    ? await pathExists(join(sourcePath, 'SKILL.md'))
    : sourcePath.toLowerCase().endsWith('.md')
  const hasPackageJson = stat.isDirectory()
    ? (await pathExists(join(sourcePath, 'package.json')) || await pathExists(join(sourcePath, '.codex-plugin/plugin.json')))
    : false
  const inspection = await inspectSkillPackage(sourcePath)
  if (!inspection.ok) {
    return {
      path: sourcePath,
      ok: false,
      packageKind: inspection.packageKind,
      hasRootSkillMd,
      hasPackageJson,
      missing: inspection.missing,
      message: inspection.messages[0] || '元数据不完整'
    }
  }
  if (inspection.packageKind === 'composite' && !hasPackageJson) {
    return {
      path: sourcePath,
      ok: false,
      packageKind: 'composite',
      hasRootSkillMd,
      hasPackageJson,
      message: '复合 Skill 源目录缺少 package.json / .codex-plugin/plugin.json（不允许只存在于临时目录）'
    }
  }
  return {
    path: sourcePath,
    ok: true,
    packageKind: inspection.packageKind,
    hasRootSkillMd,
    hasPackageJson,
    metadata: inspection.metadata as unknown as Record<string, unknown>
  }
}

/** Closed-loop verification: list --json must show slug + version_label + category. */
async function verifyUpload(
  serviceUrl: string,
  slug: string,
  version: string,
  category: string
): Promise<{ ok: boolean; message?: string; row?: Record<string, unknown> }> {
  if (!slug || !version) {
    return { ok: false, message: '上传响应缺少 slug/version，无法核验' }
  }
  try {
    const items = await apiRequest<Array<Record<string, unknown>>>(serviceUrl, '/api/skills?status=ACTIVE')
    const row = Array.isArray(items)
      ? items.find((item) => String(item.slug ?? '') === slug)
      : undefined
    if (!row) {
      return { ok: false, message: `list 未找到 slug=${slug}` }
    }
    const rowVersion = String(row.version_label ?? '')
    const rowCategory = String(row.category ?? '')
    if (rowVersion !== version) {
      return { ok: false, message: `version_label 不一致：期望 ${version}，实际 ${rowVersion}`, row }
    }
    if (category && rowCategory !== category) {
      return { ok: false, message: `category 不一致：期望 ${category}，实际 ${rowCategory}`, row }
    }
    return { ok: true, row }
  } catch (error: unknown) {
    return { ok: false, message: error instanceof Error ? error.message : String(error) }
  }
}

export interface PrepareOptions {
  /** Skill source of record that must remain complete after prepare. */
  inputPath: string
  /** Optional complete temp package; when set, overwrite source from this tree then delete it. */
  completeFrom?: string
  json: boolean
}

/**
 * prepare
 * - Without --complete-from: source itself must already be complete; normalize via temp copy and write back.
 * - With --complete-from <temp>: temp must be complete → overwrite source → delete temp → verify source.
 * Never invents version/category.
 */
export async function prepareCommand(options: PrepareOptions): Promise<string> {
  const source = resolve(options.inputPath)
  const stat = await lstat(source).catch(() => null)
  if (!stat) {
    throw new CliError('技能源目录不存在', 'SKILL_PATH_NOT_READABLE', 5, { source })
  }
  if (!stat.isDirectory()) {
    throw new CliError('prepare 仅支持技能源目录', 'UNSUPPORTED_SKILL_PATH', 5)
  }

  if (options.completeFrom) {
    const completeFrom = resolve(options.completeFrom)
    const fromInspection = await inspectSkillPackage(completeFrom)
    if (!fromInspection.ok) {
      throw new CliError(
        fromInspection.messages[0] || '临时目录中的 Skill 包不完整，不能覆盖回源',
        'SOURCE_METADATA_INCOMPLETE',
        5,
        {
          completeFrom,
          missing: fromInspection.missing,
          prepareFlow: WRITE_BACK_FLOW
        }
      )
    }
    await rm(source, { recursive: true, force: true })
    await mkdir(source, { recursive: true })
    await cp(completeFrom, source, { recursive: true, force: true })
    await rm(completeFrom, { recursive: true, force: true })
    const finalInspection = await inspectSkillPackage(source)
    const sourceVerify = await verifySourcePaths([source])
    const payload = {
      ok: finalInspection.ok && sourceVerify.ok,
      mode: 'complete-from',
      source,
      completeFromDeleted: true,
      packageKind: finalInspection.packageKind,
      metadata: finalInspection.metadata,
      sourceVerify,
      messages: finalInspection.ok && sourceVerify.ok
        ? ['已用完整临时包覆盖技能源目录，并删除临时目录；请对源目录 upload']
        : [...finalInspection.messages, ...(sourceVerify.message ? [sourceVerify.message] : [])]
    }
    if (!payload.ok) process.exitCode = 1
    return options.json ? JSON.stringify(payload) : payload.messages.join('\n')
  }

  const inspection = await inspectSkillPackage(source)
  if (!inspection.ok) {
    throw new CliError(
      inspection.messages[0] || 'Skill 源目录元数据不完整，无法 prepare',
      'SOURCE_METADATA_INCOMPLETE',
      5,
      {
        missing: inspection.missing,
        prepareFlow: WRITE_BACK_FLOW
      }
    )
  }
  const tempRoot = await mkdtemp(join(tmpdir(), 'skillhub-prepare-'))
  const tempSkill = join(tempRoot, 'skill')
  try {
    await mkdir(tempSkill, { recursive: true })
    await cp(source, tempSkill, { recursive: true, force: true })
    const tempInspection = await inspectSkillPackage(tempSkill)
    if (!tempInspection.ok || !tempInspection.metadata?.version) {
      throw new CliError(
        '临时目录中的 Skill 包仍不完整',
        'SOURCE_METADATA_INCOMPLETE',
        5,
        { messages: tempInspection.messages, missing: tempInspection.missing, prepareFlow: WRITE_BACK_FLOW }
      )
    }
    await rm(source, { recursive: true, force: true })
    await mkdir(source, { recursive: true })
    await cp(tempSkill, source, { recursive: true, force: true })
    const finalInspection = await inspectSkillPackage(source)
    const sourceVerify = await verifySourcePaths([source])
    const payload = {
      ok: finalInspection.ok && sourceVerify.ok,
      mode: 'normalize-source',
      source,
      packageKind: finalInspection.packageKind,
      metadata: finalInspection.metadata,
      sourceVerify,
      messages: finalInspection.ok && sourceVerify.ok
        ? ['源目录元数据完整，已规范化写回，可直接 upload 源目录']
        : [...finalInspection.messages, ...(sourceVerify.message ? [sourceVerify.message] : [])]
    }
    return options.json ? JSON.stringify(payload) : payload.messages.join('\n')
  } finally {
    await rm(tempRoot, { recursive: true, force: true })
  }
}

export interface CheckOptions {
  inputPath: string
  json: boolean
}

export async function checkCommand(options: CheckOptions): Promise<string> {
  const inspection = await inspectSkillPackage(options.inputPath)
  const category = inspection.metadata?.category
  const sourceVerify = await verifySourcePaths([options.inputPath])
  const path = resolve(options.inputPath)
  const hasPackageJson = await pathExists(join(path, 'package.json'))
    || await pathExists(join(path, '.codex-plugin/plugin.json'))
  const hasRootSkillMd = await pathExists(join(path, 'SKILL.md'))
  if (options.json) {
    return JSON.stringify({
      ...inspection,
      path,
      hasRootSkillMd,
      hasPackageJson,
      sourceVerify,
      categoryDeclaredInPackage: Boolean(category),
      categoryHint: category
        ? `包内 category=${category}`
        : '包内未声明 category：上传前请先读 skill 内容判定分类并写入包内；禁止未读内容默认「其他」',
      writeBackHint: sourceVerify.ok
        ? '源目录元数据完整'
        : '源目录不完整：必须 write-back 后再 upload，禁止仅临时目录成功'
    })
  }
  const lines = [
    `检查：${options.inputPath}`,
    `类型：${inspection.packageKind}`,
    `结果：${sourceVerify.ok ? '通过' : '未通过'}`,
    `根 SKILL.md：${hasRootSkillMd ? '有' : '无'}`,
    `package.json/plugin.json：${hasPackageJson ? '有' : '无'}`
  ]
  if (inspection.metadata) {
    lines.push(`name: ${inspection.metadata.name}`)
    lines.push(`version: ${inspection.metadata.version}`)
    lines.push(`category: ${category || '(未声明)'}`)
    if (!category) {
      lines.push('- 提示：包内未声明 category。请读 skill 内容判定分类后写入包内，再 upload。')
    }
  }
  for (const message of inspection.messages) lines.push(`- ${message}`)
  if (!sourceVerify.ok) {
    lines.push('- 硬性要求：临时目录补全后必须 `prepare --complete-from` 覆盖回源目录；否则不能 upload。')
  }
  return lines.join('\n')
}

export interface VerifySourceOptions {
  sourcePath: string
  json: boolean
}

export async function verifySourceCommand(options: VerifySourceOptions): Promise<string> {
  const result = await verifySourcePaths([options.sourcePath])
  const detail = result.paths[0]
  if (options.json) {
    return JSON.stringify({ ...result, detail })
  }
  const lines = [
    `源目录核验：${options.sourcePath}`,
    `结果：${result.ok ? '通过' : '未通过'}`
  ]
  if (detail) {
    lines.push(`类型：${detail.packageKind || '-'}`)
    lines.push(`根 SKILL.md：${detail.hasRootSkillMd ? '有' : '无'}`)
    lines.push(`package.json：${detail.hasPackageJson ? '有' : '无'}`)
    const metadata = detail.metadata as { name?: string; version?: string; category?: string } | undefined
    if (metadata) {
      lines.push(`name: ${metadata.name || ''}`)
      lines.push(`version: ${metadata.version || ''}`)
      lines.push(`category: ${metadata.category || '(未声明)'}`)
    }
    if (detail.message) lines.push(`说明：${detail.message}`)
  }
  if (!result.ok) {
    lines.push('禁止仅在临时目录写入元数据后上传成功；请 write-back 到技能源目录。')
  }
  return lines.join('\n')
}
