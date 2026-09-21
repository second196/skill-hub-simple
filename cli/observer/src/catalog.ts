import { readdir, readFile, stat } from 'node:fs/promises'
import { homedir, platform } from 'node:os'
import { dirname, join } from 'node:path'
import type { InstalledSkill, SkillUsage } from './types.js'

/** Full SemVer, optional prerelease/build. Leading `v` is stripped before matching. */
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/

export const UPD_SOP_CHILDREN = [
  'sop-requirement',
  'sop-design',
  'sop-implement',
  'sop-review',
  'sop-verification',
  'sop-release-check',
  'sop-documentation'
] as const

const UPD_CHILD_SET = new Set<string>(UPD_SOP_CHILDREN)
const UPD_SLUG = 'using-product-development'
const SUPERPOWERS_SLUG = 'superpowers'
const KNOWN_COMPOSITE_PARENTS = new Set([UPD_SLUG, SUPERPOWERS_SLUG])
const KNOWN_STANDALONE = new Set(['ui-ux-pro-max'])

export function slugify(value: string): string {
  const slug = value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return slug || 'skill'
}

export async function listInstalledSkills(extraRoots: string[] = []): Promise<InstalledSkill[]> {
  const roots = [...skillRoots(), ...extraRoots.map(normalizeRoot).filter(Boolean)]
  const found: InstalledSkill[] = []
  const seen = new Set<string>()
  for (const root of roots) {
    for (const path of await findSkillFiles(root, 6)) {
      const parsed = parseSkillFile(path, await readFile(path, 'utf8').catch(() => ''))
      if (!parsed || seen.has(parsed.path)) continue
      seen.add(parsed.path)
      found.push(parsed)
    }
  }
  return withCompositeParents(found)
}

export function parseSkillFile(path: string, content: string): InstalledSkill | undefined {
  const name = frontmatterName(content) || parentDirName(path)
  if (!name) return undefined
  const slug = slugify(name)
  const parentSlug = primaryParentSlug(slug, path)
  const source = isProjectSkillPath(path) ? 'project' : 'global'
  // Observation identity is SemVer label only — content digest is not used.
  const versionLabel = normalizeVersionLabel(frontmatterField(content, ['version']))
  const base: InstalledSkill = { slug, name, path, source }
  if (versionLabel) base.versionLabel = versionLabel
  return parentSlug ? { ...base, parentSlug } : base
}

export function normalizeVersionLabel(raw: string | undefined): string | undefined {
  if (!raw) return undefined
  const cleaned = raw.trim().replace(/^['"]|['"]$/g, '').replace(/^[vV]/, '')
  return SEMVER.test(cleaned) ? cleaned : undefined
}

/** Resolve SemVer version label for a usage against the installed catalog. */
export function resolveSkillVersion(
  skills: InstalledSkill[],
  usage: { slug?: string; path?: string } | undefined
): { versionLabel?: string } {
  if (!usage) return {}
  const skill = (usage.path ? skills.find((item) => item.path === usage.path) : undefined)
    || (usage.slug ? skills.find((item) => item.slug === usage.slug) : undefined)
  return { versionLabel: skill?.versionLabel }
}

export function withCompositeParents(skills: InstalledSkill[]): InstalledSkill[] {
  const bySlug = new Map<string, InstalledSkill>()
  for (const skill of skills) {
    if (!bySlug.has(skill.slug)) bySlug.set(skill.slug, skill)
  }
  for (const skill of skills) {
    for (const parentSlug of parentSlugsFor(skill.slug, skill.path)) {
      if (bySlug.has(parentSlug)) continue
      const parent: InstalledSkill = {
        slug: parentSlug,
        name: parentSlug,
        path: parentPathFor(skill.path, parentSlug),
        source: isProjectSkillPath(skill.path) ? 'project' : 'global'
      }
      bySlug.set(parentSlug, parent)
    }
  }
  return [...bySlug.values()]
}

export function parentSlugsFor(slug: string, path?: string): string[] {
  const key = slugify(slug)
  const parents: string[] = []
  if (UPD_CHILD_SET.has(key) || key.startsWith('sop-')) {
    if (key !== UPD_SLUG) parents.push(UPD_SLUG)
  }
  if (key === UPD_SLUG) {
    // leaf only
  }
  if (path && isSuperpowersChildPath(path) && key !== SUPERPOWERS_SLUG) {
    parents.push(SUPERPOWERS_SLUG)
  }
  if (key === 'using-superpowers' && !parents.includes(SUPERPOWERS_SLUG)) {
    parents.push(SUPERPOWERS_SLUG)
  }
  return parents.filter((parent, index) => parents.indexOf(parent) === index && parent !== key)
}

export function primaryParentSlug(slug: string, path?: string): string | undefined {
  return parentSlugsFor(slug, path)[0]
}

export function matchSkillUsage(skills: InstalledSkill[], value: unknown): SkillUsage | undefined {
  const skill = matchSkill(skills, value)
  if (skill) {
    return {
      slug: skill.slug,
      name: skill.name,
      path: skill.path,
      parents: parentSlugsFor(skill.slug, skill.path),
      match: 'path'
    }
  }
  return matchSkillInText(serializeValue(value))
}

export function matchSkillInText(text: string): SkillUsage | undefined {
  if (!text) return undefined
  const fileUsage = matchSkillFilePath(text)
  if (fileUsage) return fileUsage
  const dirUsage = matchSkillDirectoryPath(text)
  if (dirUsage) return dirUsage
  return matchNamedSkillToken(text)
}

export function matchSlashSkillCommands(text: string): SkillUsage[] {
  if (!text) return []
  const found = new Map<string, SkillUsage>()
  const pattern = /(?:^|[\s`'"(（\[>])([/$])([a-z][a-z0-9-]*)/gi
  for (const match of text.matchAll(pattern)) {
    const slug = slugify(match[2] || '')
    if (!slug || slug === 'skill') continue
    if (!UPD_CHILD_SET.has(slug) && slug !== UPD_SLUG && slug !== SUPERPOWERS_SLUG && !KNOWN_STANDALONE.has(slug) && !KNOWN_COMPOSITE_PARENTS.has(slug)) {
      // Only hard-attribute known composite/UPD-related slash commands from free text.
      continue
    }
    const parents = parentSlugsFor(slug)
    const usage: SkillUsage = {
      slug,
      name: slug,
      parents,
      match: 'text'
    }
    found.set(slug, usage)
    for (const parent of usage.parents) {
      if (!found.has(parent)) {
        found.set(parent, { slug: parent, name: parent, parents: [], match: 'text' })
      }
    }
  }
  // `/sop-requirement` also implies UPD even if parent loop already added it
  if ([...found.keys()].some((slug) => UPD_CHILD_SET.has(slug)) && !found.has(UPD_SLUG)) {
    found.set(UPD_SLUG, { slug: UPD_SLUG, name: UPD_SLUG, parents: [], match: 'text' })
  }
  return [...found.values()]
}

export function matchSkill(skills: InstalledSkill[], value: unknown): InstalledSkill | undefined {
  const ordered = [...skills].sort((a, b) => b.path.length - a.path.length)
  return ordered.find((skill) => contains(value, skill.path) || contains(value, skill.name) || contains(value, skill.slug))
}

export async function discoverProjectSkillRoots(seedPaths: string[]): Promise<string[]> {
  const roots = new Set<string>()
  for (const seed of seedPaths) {
    if (!seed) continue
    let dir = dirname(seed.replace(/[\\/]+$/, ''))
    for (let i = 0; i < 8; i += 1) {
      const candidate = join(dir, '.agents', 'skills')
      roots.add(candidate)
      const parent = dirname(dir)
      if (parent === dir) break
      dir = parent
    }
  }
  const envRoots = (process.env.SKILLHUB_PROJECT_SKILL_ROOTS || '')
    .split(/[;]/)
    .map((item) => item.trim())
    .filter(Boolean)
  for (const root of envRoots) roots.add(root)
  return [...roots]
}

function matchSkillFilePath(text: string): SkillUsage | undefined {
  const patterns = [
    /(?:^|[\s'"=([{])((?:~|[A-Za-z]:[\\/]|\.\/|\.\.\/|\/)?[^\s'"()]*?\.agents[\\/]skills[\\/]([^\\/"'\s]+)(?:[\\/]skills[\\/]([^\\/"'\s]+))?[\\/][^\s'"()]+)/gi,
    /(?:^|[\s'"=([{])((?:~|[A-Za-z]:[\\/]|\.\/|\.\.\/|\/)?[^\s'"()]*?\.(?:claude|codex|agents)[\\/]skills[\\/]([^\\/"'\s]+)(?:[\\/]skills[\\/]([^\\/"'\s]+))?[\\/][^\s'"()]+)/gi
  ]
  for (const pattern of patterns) {
    pattern.lastIndex = 0
    const match = pattern.exec(text)
    if (!match) continue
    const fullPath = match[1] || ''
    const first = slugify(match[2] || '')
    const nested = match[3] ? slugify(match[3]) : ''
    if (!first) continue
    if (nested && first === SUPERPOWERS_SLUG) {
      return {
        slug: nested,
        name: nested,
        parents: [SUPERPOWERS_SLUG],
        match: 'file'
      }
    }
    return {
      slug: first,
      name: first,
      parents: parentSlugsFor(first, fullPath),
      match: 'file'
    }
  }
  return undefined
}

function matchSkillDirectoryPath(text: string): SkillUsage | undefined {
  const pattern = /(?:^|[\s'"=([{])((?:~|[A-Za-z]:[\\/]|\.\/|\.\.\/|\/)?[^\s'"()]*?\.agents[\\/]skills[\\/]([^\\/"'\s]+)(?:[\\/]skills[\\/]([^\\/"'\s]+))?(?=[\\/\s'"()]))/gi
  pattern.lastIndex = 0
  const match = pattern.exec(text)
  if (!match) return undefined
  const first = slugify(match[2] || '')
  const nested = match[3] ? slugify(match[3]) : ''
  if (!first) return undefined
  if (nested && first === SUPERPOWERS_SLUG) {
    return { slug: nested, name: nested, parents: [SUPERPOWERS_SLUG], match: 'path' }
  }
  return { slug: first, name: first, parents: parentSlugsFor(first), match: 'path' }
}

function matchNamedSkillToken(text: string): SkillUsage | undefined {
  const lowered = text.toLowerCase()
  const candidates = [
    UPD_SLUG,
    SUPERPOWERS_SLUG,
    'using-superpowers',
    ...UPD_SOP_CHILDREN,
    ...KNOWN_STANDALONE
  ]
  for (const slug of candidates) {
    if (lowered.includes(slug)) {
      return { slug, name: slug, parents: parentSlugsFor(slug), match: 'path' }
    }
  }
  return undefined
}

function frontmatterName(content: string): string | undefined {
  return frontmatterField(content, ['name'])
}

function frontmatterField(content: string, keys: string[]): string | undefined {
  const match = content.replace(/^\uFEFF/, '').match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!match) return undefined
  const block = match[1]
  for (const key of keys) {
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const value = block.match(new RegExp(`^${escaped}:\\s*(.+)$`, 'm'))?.[1]?.trim()
    if (value) return value.replace(/^['"]|['"]$/g, '')
  }
  return undefined
}

function parentDirName(path: string): string {
  const parts = path.replace(/\\/g, '/').split('/')
  return parts.length >= 2 ? parts[parts.length - 2] : ''
}

function skillRoots(): string[] {
  const home = homedir()
  const roots = [
    join(home, '.claude', 'skills'),
    join(home, '.codex', 'skills'),
    join(home, '.agents', 'skills')
  ]
  if (platform() === 'darwin') roots.push(join(home, 'Library', 'Application Support', 'SkillHub', 'skills'))
  else if (platform() === 'win32') roots.push(join(process.env.LOCALAPPDATA || join(home, 'AppData', 'Local'), 'SkillHub', 'skills'))
  else roots.push(join(process.env.XDG_DATA_HOME || join(home, '.local', 'share'), 'skillhub', 'skills'))
  return roots
}

async function findSkillFiles(dir: string, depth: number): Promise<string[]> {
  if (depth < 0) return []
  const found: string[] = []
  let entries: string[]
  try {
    entries = await readdir(dir)
  } catch {
    return found
  }
  for (const entry of entries) {
    const full = join(dir, entry)
    if (entry.toLowerCase() === 'skill.md') {
      found.push(full)
      continue
    }
    try {
      if ((await stat(full)).isDirectory()) found.push(...await findSkillFiles(full, depth - 1))
    } catch {
      // skip
    }
  }
  return found
}

function contains(value: unknown, needle: string): boolean {
  if (!needle) return false
  if (typeof value === 'string') return value.includes(needle) || value.includes(JSON.stringify(needle).slice(1, -1))
  if (Array.isArray(value)) return value.some((item) => contains(item, needle))
  if (value && typeof value === 'object') return Object.values(value as Record<string, unknown>).some((item) => contains(item, needle))
  return false
}

function serializeValue(value: unknown): string {
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value ?? '')
  } catch {
    return String(value ?? '')
  }
}

function isSuperpowersChildPath(path: string): boolean {
  const normalized = path.replace(/\\/g, '/').toLowerCase()
  if (normalized.includes('/superpowers/skills/')) return true
  if (/\/\.(?:claude|codex|agents)\/skills\/superpowers\/[^/]+\//.test(normalized)) return true
  if (/\/skillhub\/skills\/superpowers\/[^/]+\//.test(normalized)) return true
  return false
}

function isProjectSkillPath(path: string): boolean {
  return path.replace(/\\/g, '/').toLowerCase().includes('/.agents/skills/')
}

function parentPathFor(childPath: string, parentSlug: string): string {
  const normalized = childPath.replace(/\\/g, '/')
  const markers = [
    `/${SUPERPOWERS_SLUG}/skills/`,
    `/.agents/skills/${parentSlug}/skills/`,
    `/.claude/skills/${parentSlug}/skills/`,
    `/.codex/skills/${parentSlug}/skills/`,
    `/.agents/skills/${parentSlug}/`,
    `/.claude/skills/${parentSlug}/`,
    `/.codex/skills/${parentSlug}/`
  ]
  for (const marker of markers) {
    const index = normalized.toLowerCase().lastIndexOf(marker.toLowerCase())
    if (index >= 0) return normalized.slice(0, index + marker.length - 1)
  }
  const lastSlash = normalized.lastIndexOf('/')
  return lastSlash > 0 ? normalized.slice(0, lastSlash) : normalized
}

function normalizeRoot(root: string): string {
  return root.trim().replace(/[\\/]+$/, '')
}
