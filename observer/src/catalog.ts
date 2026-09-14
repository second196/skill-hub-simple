import { readdir, readFile, stat } from 'node:fs/promises'
import { homedir, platform } from 'node:os'
import { join } from 'node:path'
import type { InstalledSkill } from './types.js'

export function slugify(value: string): string {
  const slug = value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return slug || 'skill'
}

export async function listInstalledSkills(): Promise<InstalledSkill[]> {
  const roots = skillRoots()
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
  return found
}

export function parseSkillFile(path: string, content: string): InstalledSkill | undefined {
  const name = frontmatterName(content) || parentDirName(path)
  if (!name) return undefined
  return { slug: slugify(name), name, path }
}

function frontmatterName(content: string): string | undefined {
  const match = content.replace(/^\uFEFF/, '').match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!match) return undefined
  const name = match[1].match(/^name:\s*(.+)$/m)?.[1]?.trim()
  return name ? name.replace(/^['"]|['"]$/g, '') : undefined
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

export function matchSkill(skills: InstalledSkill[], value: unknown): InstalledSkill | undefined {
  return skills.find((skill) => contains(value, skill.path) || contains(value, skill.name) || contains(value, skill.slug))
}

function contains(value: unknown, needle: string): boolean {
  if (!needle) return false
  if (typeof value === 'string') return value.includes(needle) || value.includes(JSON.stringify(needle).slice(1, -1))
  if (Array.isArray(value)) return value.some((item) => contains(item, needle))
  if (value && typeof value === 'object') return Object.values(value as Record<string, unknown>).some((item) => contains(item, needle))
  return false
}
