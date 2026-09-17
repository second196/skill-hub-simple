import type { PlatformSkill } from './types.js';
export interface PlatformIndex {
    slugs: Set<string>;
    names: Set<string>;
    slugByKey: Map<string, string>;
}
export declare function fetchPlatformSkills(serviceUrl: string): Promise<PlatformSkill[]>;
export declare function platformIndex(skills: PlatformSkill[]): PlatformIndex;
/**
 * Map any observed skill identity onto a platform skill slug.
 * - Independent platform skills keep their own slug (child included).
 * - Composite children roll up to parent only when the child is not itself listed.
 * - Local-only skills resolve to undefined and are not uploaded as observation cards.
 */
export declare function resolvePlatformSlug(index: PlatformIndex, slug?: string, name?: string, fallback?: string): string | undefined;
