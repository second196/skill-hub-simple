import { normalizeSkillKey } from './timeline.js';
import { parentSlugsFor } from './catalog.js';
export async function fetchPlatformSkills(serviceUrl) {
    const base = serviceUrl.replace(/\/+$/, '');
    const response = await fetch(`${base}/api/skills?includeOffline=true`, {
        signal: AbortSignal.timeout(15_000)
    });
    if (!response.ok) {
        throw new Error(`读取平台Skill失败（${response.status}）`);
    }
    const body = await response.json();
    if (!Array.isArray(body))
        throw new Error('平台Skill列表格式无效');
    return body.map((item) => {
        const record = item && typeof item === 'object' ? item : {};
        return {
            slug: String(record.slug || ''),
            name: String(record.name || record.slug || ''),
            category: record.category == null ? undefined : String(record.category),
            description: record.description == null ? undefined : String(record.description),
            status: record.status == null ? undefined : String(record.status)
        };
    }).filter((item) => item.slug);
}
export function platformIndex(skills) {
    const slugs = new Set();
    const names = new Set();
    const slugByKey = new Map();
    for (const skill of skills) {
        slugs.add(skill.slug);
        names.add(normalizeSkillKey(skill.slug));
        names.add(normalizeSkillKey(skill.name));
        slugByKey.set(normalizeSkillKey(skill.slug), skill.slug);
        slugByKey.set(normalizeSkillKey(skill.name), skill.slug);
    }
    return { slugs, names, slugByKey };
}
/**
 * Map any observed skill identity onto a platform skill slug.
 * - Independent platform skills keep their own slug (child included).
 * - Composite children roll up to parent only when the child is not itself listed.
 * - Local-only skills resolve to undefined and are not uploaded as observation cards.
 */
export function resolvePlatformSlug(index, slug, name, fallback) {
    const exact = matchPlatform(index, slug);
    if (exact)
        return exact;
    const fromName = matchPlatform(index, name);
    if (fromName)
        return fromName;
    const fromFallback = matchPlatform(index, fallback);
    if (fromFallback)
        return fromFallback;
    if (slug) {
        for (const parent of parentSlugsFor(slug)) {
            if (index.slugs.has(parent))
                return parent;
        }
    }
    if (name) {
        for (const parent of parentSlugsFor(normalizeSkillKey(name))) {
            if (index.slugs.has(parent))
                return parent;
        }
    }
    return undefined;
}
function matchPlatform(index, value) {
    if (!value)
        return undefined;
    if (index.slugs.has(value))
        return value;
    return index.slugByKey.get(normalizeSkillKey(value));
}
