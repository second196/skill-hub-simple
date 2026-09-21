/**
 * Minimal SemVer 2.0.0 helpers for the skill package version gate.
 * Accepts an optional leading `v`/`V` (stripped before parse/compare).
 * Rejects empty strings and the non-version literal `latest`.
 */
export interface SemverParts {
    major: number;
    minor: number;
    patch: number;
    /** Dot-separated prerelease identifiers (empty array = formal release). */
    prerelease: string[];
    /** Build metadata (ignored for precedence). */
    build: string[];
    /** Normalized label without leading `v`. */
    raw: string;
}
/** Strip an optional leading `v`/`V` and surrounding whitespace. */
export declare function stripVersionPrefix(value: string): string;
/** True when value is empty, `latest`, or not valid SemVer (after stripping `v`). */
export declare function isInvalidVersionLabel(value: unknown): boolean;
/** Parse a version label into SemVer parts, or null when invalid. */
export declare function parseSemver(value: string): SemverParts | null;
/** Normalize a valid label (strip `v`); returns null when invalid. */
export declare function normalizeVersionLabel(value: string): string | null;
/** True when the label is a prerelease (has a `-` prerelease component). */
export declare function isPrereleaseVersion(value: string): boolean;
/**
 * SemVer precedence compare.
 * Returns <0 when a < b, 0 when equal, >0 when a > b.
 * Build metadata is ignored. Formal release > any prerelease of same core.
 */
export declare function compareSemver(a: string | SemverParts, b: string | SemverParts): number;
/** True when candidate is strictly greater than baseline. */
export declare function isGreaterThan(candidate: string, baseline: string): boolean;
/**
 * Latest formal (non-prerelease) label among platform versions, or null.
 * Invalid labels are ignored.
 */
export declare function latestFormalVersion(labels: Iterable<string>): string | null;
