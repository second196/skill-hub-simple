import type { PlatformSkill } from './types.js'
import { normalizeSkillKey } from './timeline.js'
import { parentSlugsFor } from './catalog.js'

export interface PlatformIndex {
  slugs: Set<string>
  names: Set<string>
  slugByKey: Map<string, string>
}

export async function fetchPlatformSkills(serviceUrl: string): Promise<PlatformSkill[]> {
  const base = serviceUrl.replace(/\/+$/, '')
  const response = await fetch(`${base}/api/skills?includeOffline=true`)
  if (!response.ok) {
    throw new Error(`读取平台Skill失败（${response.status}）`)
  }
  const body = await response.json() as unknown
  if (!Array.isArray(body)) throw new Error('平台Skill列表格式无效')
  return body.map((item) => {
    const record = item && typeof item === 'object' ? item as Record<string, unknown> : {}
    return {
      slug: String(record.slug || ''),
      name: String(record.name || record.slug || ''),
      category: record.category == null ? undefined : String(record.category),
      description: record.description == null ? undefined : String(record.description),
      status: record.status == null ? undefined : String(record.status)
    }
  }).filter((item) => item.slug)
}

export function platformIndex(skills: PlatformSkill[]): PlatformIndex {
  const slugs = new Set<string>()
  const names = new Set<string>()
  const slugByKey = new Map<string, string>()
  for (const skill of skills) {
    slugs.add(skill.slug)
    names.add(normalizeSkillKey(skill.slug))
    names.add(normalizeSkillKey(skill.name))
    slugByKey.set(normalizeSkillKey(skill.slug), skill.slug)
    slugByKey.set(normalizeSkillKey(skill.name), skill.slug)
  }
  return { slugs, names, slugByKey }
}

/**
 * Map any observed skill identity onto a platform skill slug.
 * - Independent platform skills keep their own slug (child included).
 * - Composite children roll up to parent only when the child is not itself listed.
 * - Local-only skills resolve to undefined and are not uploaded as observation cards.
 */
export function resolvePlatformSlug(index: PlatformIndex, slug?: string, name?: string, fallback?: string): string | undefined {
  const exact = matchPlatform(index, slug)
  if (exact) return exact
  const fromName = matchPlatform(index, name)
  if (fromName) return fromName
  const fromFallback = matchPlatform(index, fallback)
  if (fromFallback) return fromFallback
  if (slug) {
    for (const parent of parentSlugsFor(slug)) {
      if (index.slugs.has(parent)) return parent
    }
  }
  if (name) {
    for (const parent of parentSlugsFor(normalizeSkillKey(name))) {
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
