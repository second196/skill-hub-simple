import type { PlatformSkill } from './types.js'
import { normalizeSkillKey } from './timeline.js'

export interface PlatformIndex {
  slugs: Set<string>
  names: Set<string>
  slugByKey: Map<string, string>
}

export async function fetchPlatformSkills(serviceUrl: string): Promise<PlatformSkill[]> {
  const base = serviceUrl.replace(/\/+$/, '')
  const response = await fetch(`${base}/api/skills?includeOffline=true`)
  if (!response.ok) {
    throw new Error(`读取平台技能失败（${response.status}）`)
  }
  const body = await response.json() as unknown
  if (!Array.isArray(body)) throw new Error('平台技能列表格式无效')
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

export function resolvePlatformSlug(index: PlatformIndex, slug?: string, name?: string, fallback?: string): string | undefined {
  if (slug && index.slugs.has(slug)) return slug
  const fromSlug = slug ? index.slugByKey.get(normalizeSkillKey(slug)) : undefined
  if (fromSlug) return fromSlug
  const fromName = name ? index.slugByKey.get(normalizeSkillKey(name)) : undefined
  if (fromName) return fromName
  if (fallback && index.slugs.has(fallback)) return fallback
  return undefined
}
