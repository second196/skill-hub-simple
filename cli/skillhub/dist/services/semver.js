/**
 * Minimal SemVer 2.0.0 helpers for the skill package version gate.
 * Accepts an optional leading `v`/`V` (stripped before parse/compare).
 * Rejects empty strings and the non-version literal `latest`.
 */
const SEMVER_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
/** Strip an optional leading `v`/`V` and surrounding whitespace. */
export function stripVersionPrefix(value) {
    return value.trim().replace(/^v/i, '');
}
/** True when value is empty, `latest`, or not valid SemVer (after stripping `v`). */
export function isInvalidVersionLabel(value) {
    if (value === undefined || value === null)
        return true;
    if (typeof value !== 'string')
        return true;
    const trimmed = value.trim();
    if (trimmed.length === 0)
        return true;
    if (trimmed.toLowerCase() === 'latest')
        return true;
    return parseSemver(trimmed) === null;
}
/** Parse a version label into SemVer parts, or null when invalid. */
export function parseSemver(value) {
    const normalized = stripVersionPrefix(value);
    if (normalized.length === 0 || normalized.toLowerCase() === 'latest')
        return null;
    const match = SEMVER_PATTERN.exec(normalized);
    if (match === null)
        return null;
    return {
        major: Number(match[1]),
        minor: Number(match[2]),
        patch: Number(match[3]),
        prerelease: match[4] ? match[4].split('.') : [],
        build: match[5] ? match[5].split('.') : [],
        raw: normalized
    };
}
/** Normalize a valid label (strip `v`); returns null when invalid. */
export function normalizeVersionLabel(value) {
    return parseSemver(value)?.raw ?? null;
}
/** True when the label is a prerelease (has a `-` prerelease component). */
export function isPrereleaseVersion(value) {
    const parsed = parseSemver(value);
    return parsed !== null && parsed.prerelease.length > 0;
}
/**
 * SemVer precedence compare.
 * Returns <0 when a < b, 0 when equal, >0 when a > b.
 * Build metadata is ignored. Formal release > any prerelease of same core.
 */
export function compareSemver(a, b) {
    const left = typeof a === 'string' ? parseSemver(a) : a;
    const right = typeof b === 'string' ? parseSemver(b) : b;
    if (left === null || right === null) {
        throw new Error('compareSemver requires valid SemVer labels');
    }
    if (left.major !== right.major)
        return left.major < right.major ? -1 : 1;
    if (left.minor !== right.minor)
        return left.minor < right.minor ? -1 : 1;
    if (left.patch !== right.patch)
        return left.patch < right.patch ? -1 : 1;
    const leftPre = left.prerelease;
    const rightPre = right.prerelease;
    if (leftPre.length === 0 && rightPre.length === 0)
        return 0;
    if (leftPre.length === 0)
        return 1;
    if (rightPre.length === 0)
        return -1;
    const max = Math.max(leftPre.length, rightPre.length);
    for (let index = 0; index < max; index += 1) {
        const leftId = leftPre[index];
        const rightId = rightPre[index];
        if (leftId === undefined)
            return -1;
        if (rightId === undefined)
            return 1;
        const leftNum = /^\d+$/.test(leftId);
        const rightNum = /^\d+$/.test(rightId);
        if (leftNum && rightNum) {
            const leftValue = Number(leftId);
            const rightValue = Number(rightId);
            if (leftValue !== rightValue)
                return leftValue < rightValue ? -1 : 1;
            continue;
        }
        if (leftNum)
            return -1;
        if (rightNum)
            return 1;
        if (leftId !== rightId)
            return leftId < rightId ? -1 : 1;
    }
    return 0;
}
/** True when candidate is strictly greater than baseline. */
export function isGreaterThan(candidate, baseline) {
    return compareSemver(candidate, baseline) > 0;
}
/**
 * Latest formal (non-prerelease) label among platform versions, or null.
 * Invalid labels are ignored.
 */
export function latestFormalVersion(labels) {
    let latest = null;
    for (const label of labels) {
        const normalized = normalizeVersionLabel(label);
        if (normalized === null)
            continue;
        if (isPrereleaseVersion(normalized))
            continue;
        if (latest === null || compareSemver(normalized, latest) > 0) {
            latest = normalized;
        }
    }
    return latest;
}
