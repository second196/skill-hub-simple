import type { PlatformSkill } from './types.js'
import { normalizeSkillKey } from './timeline.js'
import { normalizeVersionLabel, parentSlugsFor } from './catalog.js'

export interface PlatformIndex {
  slugs: Set<string>
  names: Set<string>
  slugByKey: Map<string, string>
  versionsBySlug: Map<string, Set<string>>
}

export async function fetchPlatformSkills(serviceUrl: string): Promise<PlatformSkill[]> {
  const base = serviceUrl.replace(/\/+$/, '')
  const response = await fetch(`${base}/api/skills?includeOffline=true`, {
    signal: AbortSignal.timeout(15_000)
  })
  if (!response.ok) {
    throw new Error(`读取平台Skill失败（${response.status}）`)
  }
  const body = await response.json() as unknown
  if (!Array.isArray(body)) throw new Error('平台Skill列表格式无效')
  return body.map((item) => {
    const record = item && typeof item === 'object' ? item as Record<string, unknown> : {}
    const rawLabels = record.versionLabels ?? record.version_labels
    const versionLabels: string[] = []
    if (Array.isArray(rawLabels)) {
      for (const label of rawLabels) {
        const normalized = normalizeVersionLabel(typeof label === 'string' ? label : undefined)
        if (normalized) versionLabels.push(normalized)
      }
    } else if (typeof record.version_label === 'string' || typeof record.versionLabel === 'string') {
      const normalized = normalizeVersionLabel(String(record.version_label ?? record.versionLabel))
      if (normalized) versionLabels.push(normalized)
    }
    return {
      slug: String(record.slug || ''),
      name: String(record.name || record.slug || ''),
      category: record.category == null ? undefined : String(record.category),
      description: record.description == null ? undefined : String(record.description),
      status: record.status == null ? undefined : String(record.status),
      versionLabels
    }
  }).filter((item) => item.slug)
}

export function platformIndex(skills: PlatformSkill[]): PlatformIndex {
  const slugs = new Set<string>()
  const names = new Set<string>()
  const slugByKey = new Map<string, string>()
  const versionsBySlug = new Map<string, Set<string>>()
  for (const skill of skills) {
    slugs.add(skill.slug)
    names.add(normalizeSkillKey(skill.slug))
    names.add(normalizeSkillKey(skill.name))
    slugByKey.set(normalizeSkillKey(skill.slug), skill.slug)
    slugByKey.set(normalizeSkillKey(skill.name), skill.slug)
    const versions = new Set<string>()
    for (const label of skill.versionLabels || []) {
      const normalized = normalizeVersionLabel(label)
      if (normalized) versions.add(normalized)
    }
    versionsBySlug.set(skill.slug, versions)
  }
  return { slugs, names, slugByKey, versionsBySlug }
}

/**
 * Map any observed skill identity onto a platform skill slug.
 * - Independent platform skills keep their own slug (child included).
 * - Composite children roll up to parent only when the child is not itself listed.
 * - Local-only skills resolve to undefined; callers keep the local identity for upload.
 */
export function resolvePlatformSlug(
  index: PlatformIndex,
  slug?: string,
  name?: string,
  fallback?: string,
  skillPath?: string
): string | undefined {
  const exact = matchPlatform(index, slug)
  if (exact) return exact
  const fromName = matchPlatform(index, name)
  if (fromName) return fromName
  const fromFallback = matchPlatform(index, fallback)
  if (fromFallback) return fromFallback
  if (slug) {
    for (const parent of parentSlugsFor(slug, skillPath)) {
      if (index.slugs.has(parent)) return parent
    }
  }
  if (name) {
    for (const parent of parentSlugsFor(normalizeSkillKey(name), skillPath)) {
      if (index.slugs.has(parent)) return parent
    }
  }
  return undefined
}

function matchPlatform(index: PlatformIndex, value?: string): string | undefined {
  if (!value) return undefined
  if (index.slugs.has(value)) return value
  return index.slugByKey.get(normalizeSkillKey(value))
}
