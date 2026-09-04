import { unzipSync, zipSync } from 'fflate'

export interface InspectedPackageFile {
  path: string
  displayPath: string
  size: number
  data: Uint8Array
  text: boolean
}

export interface SkillManifest {
  name: string
  description: string
  version: string
  slug: string
}

export interface InspectedSkillPackage {
  sourceName: string
  files: InspectedPackageFile[]
  manifest: SkillManifest
  warnings: string[]
}

const ignoredSegments = new Set(['.git', '.svn', '.hg', 'node_modules', '__pycache__', '__MACOSX'])
const ignoredNames = new Set(['.DS_Store', 'Thumbs.db', 'desktop.ini'])
const sensitivePattern = /(^|\/)(\.env(?:\.|$)|id_rsa|id_ed25519|credentials?|secrets?|.*\.(?:pem|key|p12|pfx))$/i
const textPattern = /\.(?:md|txt|json|ya?ml|toml|xml|html?|css|scss|less|js|jsx|ts|tsx|vue|java|kt|py|rb|go|rs|sh|ps1|sql|properties|ini|conf|csv)$/i

function normalized(path: string): string {
  return path.replace(/\\/g, '/').replace(/^\.\//, '').replace(/\/+/g, '/')
}

function ignored(path: string): boolean {
  const parts = normalized(path).split('/').filter(Boolean)
  const name = parts[parts.length - 1] ?? ''
  return parts.some((part) => ignoredSegments.has(part)) || ignoredNames.has(name) || name.startsWith('._') || /\.(?:pyc|swp)$/i.test(name)
}

function displayPaths(paths: string[]): Map<string, string> {
  const roots = new Set(paths.map((path) => normalized(path).split('/')[0]).filter(Boolean))
  const stripRoot = roots.size === 1 && paths.some((path) => normalized(path).includes('/'))
  const root = stripRoot ? [...roots][0] : ''
  return new Map(paths.map((path) => {
    const value = normalized(path)
    return [path, root && value.startsWith(`${root}/`) ? value.slice(root.length + 1) : value]
  }))
}

function scalar(value: string): string {
  const trimmed = value.trim()
  if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) return trimmed.slice(1, -1)
  return trimmed
}

export function parseSkillManifest(content: string, fallbackName: string): SkillManifest {
  const frontmatter = content.match(/^---\s*\r?\n([\s\S]*?)\r?\n---(?:\s*\r?\n|$)/)
  const values: Record<string, string> = {}
  if (frontmatter) {
    for (const line of frontmatter[1].split(/\r?\n/)) {
      const match = line.match(/^([A-Za-z][A-Za-z0-9_-]*):\s*(.*)$/)
      if (match) values[match[1].toLocaleLowerCase()] = scalar(match[2])
    }
  }
  const packageName = fallbackName.replace(/\.zip$/i, '').replace(/[^A-Za-z0-9._-]+/g, '-') || 'skill'
  const name = values.displayname || values.name || packageName
  const slugSource = values.slug || values.id || values.name || packageName
  const slug = slugSource.toLocaleLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || packageName.toLocaleLowerCase()
  const bodySummary = content.replace(/^---[\s\S]*?---\s*/, '').replace(/^#+\s*/gm, '').trim().split(/\r?\n/).find(Boolean) ?? ''
  return {
    name,
    description: values.description || bodySummary.slice(0, 500),
    version: values.version || '1.0.0',
    slug
  }
}

function inspectEntries(sourceName: string, values: Array<{ path: string; data: Uint8Array }>): InspectedSkillPackage {
  const accepted = values.filter((entry) => !ignored(entry.path) && entry.data.length > 0)
  const pathMap = displayPaths(accepted.map((entry) => entry.path))
  const files = accepted.map((entry) => ({
    path: normalized(entry.path),
    displayPath: pathMap.get(entry.path) ?? normalized(entry.path),
    size: entry.data.length,
    data: entry.data,
    text: textPattern.test(entry.path) || /(^|\/)SKILL\.md$/i.test(entry.path)
  })).sort((left, right) => left.displayPath.localeCompare(right.displayPath))
  const manifestFile = files.find((file) => /(^|\/)SKILL\.md$/i.test(file.path))
  if (!manifestFile) throw new Error('技能包根目录必须包含 SKILL.md')
  const content = new TextDecoder('utf-8', { fatal: true }).decode(manifestFile.data)
  const warnings: string[] = []
  if (!/^---\s*$/m.test(content)) warnings.push('SKILL.md 未检测到 YAML 元数据头，发布信息需要人工确认。')
  const sensitive = files.filter((file) => sensitivePattern.test(file.displayPath))
  if (sensitive.length > 0) warnings.push(`检测到 ${sensitive.length} 个可能包含凭据的文件，请移除后再发布。`)
  if (files.length > 200) warnings.push(`技能包包含 ${files.length} 个文件，请确认没有包含构建产物或依赖目录。`)
  return { sourceName, files, manifest: parseSkillManifest(content, sourceName), warnings }
}

export async function inspectZip(file: File): Promise<InspectedSkillPackage> {
  const archive = unzipSync(new Uint8Array(await file.arrayBuffer()))
  return inspectEntries(file.name, Object.entries(archive).map(([path, data]) => ({ path, data })))
}

export async function inspectFolder(fileList: FileList): Promise<InspectedSkillPackage> {
  const files = Array.from(fileList)
  const sourceName = ((files[0] as File & { webkitRelativePath?: string })?.webkitRelativePath || files[0]?.name || 'skill').split('/')[0]
  const entries = await Promise.all(files.map(async (file) => ({
    path: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
    data: new Uint8Array(await file.arrayBuffer())
  })))
  return inspectEntries(sourceName, entries)
}

export function createPackageFile(source: InspectedSkillPackage, excludedPaths: Set<string>): File {
  const entries: Record<string, Uint8Array> = {}
  source.files.forEach((file) => {
    if (!excludedPaths.has(file.path)) entries[file.path] = file.data
  })
  return new File([zipSync(entries, { level: 6 })], `${source.sourceName.replace(/\.zip$/i, '')}.zip`, { type: 'application/zip' })
}
