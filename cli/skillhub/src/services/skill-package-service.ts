import { lstat, readFile, readdir } from 'node:fs/promises'
import { basename, extname, join, relative, resolve } from 'node:path'
import { parseDocument } from 'yaml'
import { z } from 'zod'
import { createArchive, readArchive, resolvePackageLimits } from '../platform/archive.js'
import { normalizePackagePath, shouldExcludePackagePath } from '../platform/paths.js'
import { PackageValidationError } from '../shared/errors.js'
import { VERSION_GATE_ERROR_CODES, versionRequiredExample } from '../shared/constants.js'
import {
  type PackageFile,
  type PackageLimitOverrides,
  type PreparedSkillPackage,
  type SkillMetadataOverrides,
  type SkillPackageManifestEntry,
  type SkillPackageMetadata
} from '../shared/types.js'
import { isInvalidVersionLabel, normalizeVersionLabel } from './semver.js'
import { assertSemverLabel } from './version-gate.js'

const metadataSchema = z.object({
  name: z.string().trim().min(1).max(100),
  description: z.string().trim().min(1).max(2000),
  category: z.string().trim().max(128).optional(),
  // version is validated separately so missing/invalid labels map to VERSION_SEMVER_REQUIRED
  version: z.unknown().optional()
}).passthrough()

/**
 * Prepare a skill package for upload.
 * Version/category are parsed from package content only — CLI never injects version.
 */
export async function prepareSkillPackage(
  inputPath: string,
  overrides: PackageLimitOverrides = {},
  metadataOverrides: SkillMetadataOverrides = {}
): Promise<PreparedSkillPackage> {
  const limits = resolvePackageLimits(overrides)
  let inputStat
  try {
    inputStat = await lstat(inputPath)
  } catch (_error: unknown) {
    throw new PackageValidationError('Skill 路径不存在或不可读', 'SKILL_PATH_NOT_READABLE')
  }
  if (inputStat.isSymbolicLink()) {
    throw new PackageValidationError('Skill 路径不允许是符号链接', 'SYMLINK_NOT_ALLOWED')
  }

  let sourceType: PreparedSkillPackage['sourceType']
  let archive: Uint8Array
  let files: PackageFile[]
  if (inputStat.isDirectory()) {
    sourceType = 'DIRECTORY'
    files = await readDirectoryFiles(inputPath, overrides)
    archive = createArchive(files, overrides)
  } else if (inputStat.isFile()) {
    if (inputPath.toLowerCase().endsWith('.md')) {
      sourceType = 'DIRECTORY'
      const content = new Uint8Array(await readFile(inputPath))
      files = [{ path: 'SKILL.md', content }]
      archive = createArchive(files, overrides)
    } else {
      sourceType = 'ZIP'
      if (inputStat.size > limits.maxArchiveBytes) {
        throw new PackageValidationError('Skill 压缩包超过大小限制', 'PACKAGE_ARCHIVE_TOO_LARGE')
      }
      archive = new Uint8Array(await readFile(inputPath))
      files = readArchive(archive, overrides)
      const sensitiveEntry = files.find((file) => shouldExcludePackagePath(file.path))
      if (sensitiveEntry !== undefined) {
        throw new PackageValidationError(
          'Skill ZIP 包含版本库或凭据文件',
          'SENSITIVE_PACKAGE_PATH',
          { path: sensitiveEntry.path }
        )
      }
    }
  } else {
    throw new PackageValidationError('Skill 路径必须是目录或 ZIP 文件', 'UNSUPPORTED_SKILL_PATH')
  }

  if (files.length === 0) {
    throw new PackageValidationError('Skill 包不能为空', 'EMPTY_SKILL_PACKAGE')
  }
  files = normalizePackageRoot(files)
  const rootSkill = files.find((file) => file.path === 'SKILL.md')
  let metadata: SkillPackageMetadata
  if (rootSkill !== undefined) {
    metadata = parseMetadata(rootSkill.content)
  } else {
    const nestedSkillCount = files.filter((file) => file.path.endsWith('/SKILL.md')).length
    if (nestedSkillCount === 0) {
      throw new PackageValidationError(
        'Skill 包缺少 SKILL.md，且没有可识别的子Skill（至少需要一个 */SKILL.md 或根 SKILL.md）',
        'SKILL_FILE_REQUIRED'
      )
    }
    files = ensureCompositeReadme(files, inputPath, metadataOverrides)
    metadata = resolveCompositeMetadata(files, inputPath, metadataOverrides)
  }
  archive = createArchive(files, overrides)
  const manifest = createManifest(files)
  return {
    sourceType,
    archive,
    metadata,
    manifest
  }
}

function versionSemverRequired(version: unknown, overrides: { name?: string; description?: string; packageKind?: 'composite' | 'skill-md' } = {}): PackageValidationError {
  return new PackageValidationError(
    'Skill 包内缺少有效的语义化版本号（version）',
    VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED,
    {
      version: version === undefined || version === null ? null : String(version),
      ...versionRequiredExample({
        name: overrides.name,
        description: overrides.description,
        packageKind: overrides.packageKind || 'skill-md'
      })
    }
  )
}

function normalizePackageRoot(files: PackageFile[]): PackageFile[] {
  if (files.some((file) => file.path === 'SKILL.md')) return files
  const skillFiles = files.filter((file) => file.path.endsWith('/SKILL.md'))
  if (skillFiles.length !== 1) return files
  const prefix = skillFiles[0].path.slice(0, -'SKILL.md'.length)
  if (!files.every((file) => file.path.startsWith(prefix))) return files
  return files.map((file) => ({ ...file, path: file.path.slice(prefix.length) }))
}

/** Keep existing root README.* bytes unchanged; only create when the package has none. */
function ensureCompositeReadme(
  files: PackageFile[],
  inputPath: string,
  metadataOverrides: SkillMetadataOverrides
): PackageFile[] {
  if (findRootReadme(files) !== undefined) return files
  const packageMetadata = readPackageMetadata(files)
  const nestedSkills = files
    .filter((file) => file.path.endsWith('/SKILL.md'))
    .map((file) => file.path.slice(0, -'SKILL.md'.length).replace(/\/$/, ''))
  const fallbackName = basename(inputPath, extname(inputPath)).trim() || 'composite-skill'
  const name = cleanMetadataValue(
    metadataOverrides.name || packageMetadata.name || readmeTitle(files) || fallbackName,
    fallbackName,
    100
  )
  const description = cleanMetadataValue(
    metadataOverrides.description
      || packageMetadata.description
      || readmeDescription(files)
      || `复合Skill包，包含 ${nestedSkills.length} 个子Skill。`,
    `复合Skill包，包含 ${nestedSkills.length} 个子Skill。`,
    2000
  )
  const lines = [
    `# ${name}`,
    '',
    description,
    '',
    '这是一个复合Skill包，包含若干可独立使用的子Skill。子Skill及其资源保留在原始目录结构中。'
  ]
  if (nestedSkills.length > 0) {
    lines.push('', '## 子 Skill', '')
    for (const skill of nestedSkills) {
      lines.push(`- \`${skill}\``)
    }
  }
  lines.push('')
  return [{ path: 'README.md', content: new TextEncoder().encode(lines.join('\n')) }, ...files]
}

function resolveCompositeMetadata(
  files: PackageFile[],
  inputPath: string,
  metadataOverrides: SkillMetadataOverrides
): SkillPackageMetadata {
  const nestedSkillCount = files.filter((file) => file.path.endsWith('/SKILL.md')).length
  const packageMetadata = readPackageMetadata(files)
  const fallbackName = basename(inputPath, extname(inputPath)).trim() || 'composite-skill'
  const name = cleanMetadataValue(
    metadataOverrides.name || packageMetadata.name || readmeTitle(files) || fallbackName,
    fallbackName,
    100
  )
  const description = cleanMetadataValue(
    metadataOverrides.description
      || packageMetadata.description
      || readmeDescription(files)
      || `复合Skill包，包含 ${nestedSkillCount} 个子Skill。`,
    `复合Skill包，包含 ${nestedSkillCount} 个子Skill。`,
    2000
  )
  // Version must come from package content (package.json / plugin.json). CLI never injects it.
  const rawVersion = packageMetadata.version
  if (rawVersion === undefined || rawVersion === null || String(rawVersion).trim() === '') {
    throw new PackageValidationError(
      '复合Skill包缺少 version（请写在包根 package.json / .codex-plugin/plugin.json）',
      VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED,
      {
        version: null,
        ...versionRequiredExample({ name, description, packageKind: 'composite' })
      }
    )
  }
  const version = normalizeVersionLabel(String(rawVersion))
  if (version === null) {
    throw versionSemverRequired(rawVersion, { name, description, packageKind: 'composite' })
  }
  const packageCategory = packageMetadata.category?.trim()
  const category = packageCategory && packageCategory.length > 0
    ? packageCategory.slice(0, 128)
    : (metadataOverrides.category?.trim() || undefined)
  return category ? { name, description, version, category } : { name, description, version }
}

function findRootReadme(files: PackageFile[]): string | undefined {
  return files.find((file) => file.path.toLowerCase() === 'readme.md')?.path
}

function readPackageMetadata(files: PackageFile[]): { name?: string; description?: string; version?: string; category?: string } {
  for (const path of ['package.json', '.codex-plugin/plugin.json']) {
    const file = files.find((item) => item.path === path)
    if (file === undefined) continue
    try {
      const value = JSON.parse(new TextDecoder().decode(file.content)) as Record<string, unknown>
      return {
        name: typeof value.name === 'string' ? value.name : undefined,
        description: typeof value.description === 'string' ? value.description : undefined,
        version: typeof value.version === 'string' ? value.version : undefined,
        category: typeof value.category === 'string' ? value.category : undefined
      }
    } catch {
      continue
    }
  }
  return {}
}

function readmeTitle(files: PackageFile[]): string | undefined {
  const readme = readTextFile(files, 'README.md')
  return readme?.match(/^#\s+(.+?)\s*$/m)?.[1]?.trim()
}

function readmeDescription(files: PackageFile[]): string | undefined {
  const readme = readTextFile(files, 'README.md')
  if (!readme) return undefined
  const withoutTitle = readme.replace(/^#\s+.+?\s*$/m, '')
  const paragraph = withoutTitle
    .split(/\r?\n\s*\r?\n/)
    .map((item) => item.replace(/^\s*[-*>`#].*$/gm, '').replace(/\s+/g, ' ').trim())
    .find((item) => item.length > 0)
  return paragraph
}

function readTextFile(files: PackageFile[], path: string): string | undefined {
  const file = files.find((item) => item.path.toLowerCase() === path.toLowerCase())
  if (!file) return undefined
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(file.content)
  } catch {
    return undefined
  }
}

function cleanMetadataValue(value: string, fallback: string, maxLength: number): string {
  const cleaned = value.trim().replace(/\s+/g, ' ').slice(0, maxLength)
  return cleaned || fallback
}

async function readDirectoryFiles(
  rootPath: string,
  overrides: PackageLimitOverrides
): Promise<PackageFile[]> {
  const limits = resolvePackageLimits(overrides)
  const root = resolve(rootPath)
  const files: PackageFile[] = []
  let expandedBytes = 0

  async function visit(directory: string): Promise<void> {
    let entries
    try {
      entries = await readdir(directory, { withFileTypes: true })
    } catch (_error: unknown) {
      throw new PackageValidationError('Skill 目录不可读', 'SKILL_PATH_NOT_READABLE')
    }
    entries.sort((left, right) => Buffer.compare(Buffer.from(left.name, 'utf8'), Buffer.from(right.name, 'utf8')))
    for (const entry of entries) {
      const absolutePath = join(directory, entry.name)
      const relativePath = normalizePackagePath(relative(root, absolutePath).replace(/\\/g, '/'), limits.maxPathLength)
      if (shouldExcludePackagePath(relativePath)) continue
      const entryStat = await lstat(absolutePath)
      if (entryStat.isSymbolicLink()) {
        throw new PackageValidationError('Skill 包不允许符号链接', 'SYMLINK_NOT_ALLOWED', { path: relativePath })
      }
      if (entryStat.isDirectory()) {
        await visit(absolutePath)
        continue
      }
      if (!entryStat.isFile()) continue
      if (files.length + 1 > limits.maxFiles) {
        throw new PackageValidationError('Skill 包文件数量超过限制', 'PACKAGE_FILE_COUNT_EXCEEDED')
      }
      if (entryStat.size > limits.maxSingleFileBytes) {
        throw new PackageValidationError('Skill 包内单个文件超过大小限制', 'PACKAGE_FILE_TOO_LARGE', { path: relativePath })
      }
      expandedBytes += entryStat.size
      if (expandedBytes > limits.maxExpandedBytes) {
        throw new PackageValidationError('Skill 包解压后超过大小限制', 'PACKAGE_EXPANDED_TOO_LARGE')
      }
      try {
        const content = new Uint8Array(await readFile(absolutePath))
        files.push({ path: relativePath, content })
      } catch (_error: unknown) {
        throw new PackageValidationError('Skill 包包含不可读文件', 'SKILL_FILE_NOT_READABLE', { path: relativePath })
      }
    }
  }

  await visit(root)
  return files
}

function parseMetadata(content: Uint8Array): SkillPackageMetadata {
  let markdown: string
  try {
    markdown = new TextDecoder('utf-8', { fatal: true }).decode(content)
  } catch (_error: unknown) {
    throw new PackageValidationError('SKILL.md 必须使用 UTF-8', 'INVALID_SKILL_ENCODING')
  }
  const lines = markdown.replace(/^\uFEFF/, '').split(/\r?\n/)
  if (lines[0] !== '---') {
    throw new PackageValidationError('SKILL.md 缺少 YAML frontmatter', 'INVALID_SKILL_FRONTMATTER')
  }
  const closingIndex = lines.findIndex((line, index) => index > 0 && (line === '---' || line === '...'))
  if (closingIndex < 0) {
    throw new PackageValidationError('SKILL.md 的 YAML frontmatter 未闭合', 'INVALID_SKILL_FRONTMATTER')
  }

  const document = parseDocument(lines.slice(1, closingIndex).join('\n'), {
    schema: 'core',
    strict: true,
    uniqueKeys: true
  })
  if (document.errors.length > 0) {
    throw new PackageValidationError('SKILL.md 的 YAML frontmatter 无法解析', 'INVALID_SKILL_FRONTMATTER')
  }
  let value: unknown
  try {
    value = document.toJS({ maxAliasCount: 0 })
  } catch (_error: unknown) {
    throw new PackageValidationError('SKILL.md 的 YAML frontmatter 不安全', 'INVALID_SKILL_FRONTMATTER')
  }
  const result = metadataSchema.safeParse(value)
  if (!result.success) {
    throw new PackageValidationError('SKILL.md 的名称、描述无效', 'INVALID_SKILL_METADATA')
  }

  const rawVersion = result.data.version
  if (isInvalidVersionLabel(rawVersion)) {
    throw versionSemverRequired(rawVersion, {
      name: result.data.name,
      description: result.data.description,
      packageKind: 'skill-md'
    })
  }
  const version = assertSemverLabel(String(rawVersion))
  const category = result.data.category?.trim()
  const metadata: SkillPackageMetadata = {
    name: result.data.name,
    description: result.data.description,
    version
  }
  if (category && category.length > 0) metadata.category = category.slice(0, 128)
  return metadata
}

function createManifest(files: PackageFile[]): SkillPackageManifestEntry[] {
  return [...files]
    .sort((left, right) => Buffer.compare(Buffer.from(left.path, 'utf8'), Buffer.from(right.path, 'utf8')))
    .map((file) => ({ path: file.path, size: file.content.byteLength }))
}

/** Local preflight used by skillhub check / upload. Does not invent missing fields. */
export async function inspectSkillPackage(inputPath: string): Promise<{
  ok: boolean
  packageKind: 'skill-md' | 'composite'
  metadata?: SkillPackageMetadata
  missing: string[]
  messages: string[]
}> {
  const packageKind = await packageKindOf(inputPath)
  try {
    const prepared = await prepareSkillPackage(inputPath)
    const missing: string[] = []
    if (!prepared.metadata.version) missing.push('version')
    return {
      ok: missing.length === 0,
      packageKind,
      metadata: prepared.metadata,
      missing,
      messages: missing.length === 0 ? ['包内元数据完整，可以上传'] : missing.map((field) => `缺少 ${field}`)
    }
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error)
    return {
      ok: false,
      packageKind,
      missing: /version/i.test(message) ? ['version'] : [],
      messages: [message]
    }
  }
}

async function packageKindOf(inputPath: string): Promise<'skill-md' | 'composite'> {
  try {
    const stat = await lstat(inputPath)
    if (stat.isFile()) {
      const files = inputPath.toLowerCase().endsWith('.zip')
        ? readArchive(new Uint8Array(await readFile(inputPath)))
        : [{ path: 'SKILL.md', content: new Uint8Array(await readFile(inputPath)) }]
      return files.some((file) => file.path === 'SKILL.md') ? 'skill-md' : 'composite'
    }
    const files = await readDirectoryFiles(inputPath, {})
    return files.some((file) => file.path === 'SKILL.md') ? 'skill-md' : 'composite'
  } catch {
    return 'composite'
  }
}

export { VERSION_GATE_ERROR_CODES }
