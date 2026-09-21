import type { SkillPackageMetadata } from '../shared/types.js';
export interface PlatformSkillVersion {
    versionLabel: string;
}
export interface PlatformSkillSnapshot {
    slug: string;
    name: string;
    versions: PlatformSkillVersion[];
}
export interface VersionBumpCheckInput {
    serviceUrl?: string;
    metadata: SkillPackageMetadata;
    /**
     * Optional override for tests / offline strategies.
     * Return null when the skill is not on the platform.
     * Throw SERVICE_UNREACHABLE-style errors to skip the check.
     */
    fetchPlatformSkill?: (serviceUrl: string, name: string) => Promise<PlatformSkillSnapshot | null>;
}
/**
 * VERSION_EXISTS / VERSION_BUMP_REQUIRED gate — run before upload POST.
 * Version identity is SemVer version_label only (no content digest).
 *
 * Decision table:
 * - Skill not on platform                         → allow (first upload)
 * - Same version_label already on platform        → VERSION_EXISTS (immutable)
 * - Local version <= latest formal platform       → VERSION_BUMP_REQUIRED
 * - Local version strictly greater than latest    → allow
 * - Platform unreachable / malformed response     → skip check (server re-validates)
 */
export declare function assertVersionBumpRequired(input: VersionBumpCheckInput): Promise<void>;
/**
 * Resolve a platform skill by name (case-insensitive) or derived slug,
 * then load version history from GET /api/skills/{slug}.
 */
export declare function fetchPlatformSkillFromService(serviceUrl: string, name: string): Promise<PlatformSkillSnapshot | null>;
/** Mirror backend SkillRepository.uniqueSlug base slug derivation. */
export declare function slugifySkillName(name: string): string;
/** Exported for tests: validate a label the same way the package service does. */
export declare function assertSemverLabel(version: string): string;
