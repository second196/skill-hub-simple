import type { SkillPackageMetadata } from '../shared/types.js';
export interface PlatformSkillVersion {
    versionLabel: string;
    versionDigest: string;
}
export interface PlatformSkillSnapshot {
    slug: string;
    name: string;
    versions: PlatformSkillVersion[];
}
export interface VersionBumpCheckInput {
    serviceUrl?: string;
    metadata: SkillPackageMetadata;
    /** Local content fingerprint from PreparedSkillPackage.versionDigest */
    versionDigest: string;
    /**
     * Optional override for tests / offline strategies.
     * Return null when the skill is not on the platform.
     * Throw SERVICE_UNREACHABLE-style errors to skip the check.
     */
    fetchPlatformSkill?: (serviceUrl: string, name: string) => Promise<PlatformSkillSnapshot | null>;
}
/**
 * VERSION_BUMP_REQUIRED / VERSION_DIGEST_CONFLICT gate — run before upload POST.
 *
 * Decision table:
 * - Skill not on platform                → allow (first upload)
 * - Local digest matches any platform version → allow (idempotent)
 * - Same version_label + different digest → VERSION_DIGEST_CONFLICT
 * - Content differs from all platform digests AND local version is not
 *   strictly greater than the latest formal platform version
 *   → VERSION_BUMP_REQUIRED
 * - Local version is new and greater than latest formal → allow
 * - Platform unreachable / malformed response → skip check (server re-validates)
 */
export declare function assertVersionBumpRequired(input: VersionBumpCheckInput): Promise<void>;
/**
 * Resolve a platform skill by name (case-insensitive) or derived slug,
 * then load version history from GET /api/skills/{slug}.
 * Falls back to the list-row version when detail is unavailable.
 */
export declare function fetchPlatformSkillFromService(serviceUrl: string, name: string): Promise<PlatformSkillSnapshot | null>;
/** Mirror backend SkillRepository.uniqueSlug base slug derivation. */
export declare function slugifySkillName(name: string): string;
/** Exported for tests: validate a label the same way the package service does. */
export declare function assertSemverLabel(version: string): string;
