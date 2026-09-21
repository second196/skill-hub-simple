import { apiRequest } from '../clients/api-client.js'
import { PackageValidationError } from '../shared/errors.js'
import {
  VERSION_BUMP_REQUIRED_MESSAGE,
  VERSION_EXISTS_MESSAGE,
  VERSION_GATE_ERROR_CODES,
  versionRequiredExample
} from '../shared/constants.js'
import type { SkillPackageMetadata } from '../shared/types.js'
import {
  compareSemver,
  latestFormalVersion,
  normalizeVersionLabel,
  parseSemver
} from './semver.js'

export interface PlatformSkillVersion {
  versionLabel: string
}

export interface PlatformSkillSnapshot {
  slug: string
  name: string
  versions: PlatformSkillVersion[]
}

export interface VersionBumpCheckInput {
  serviceUrl?: string
  metadata: SkillPackageMetadata
  /**
   * Optional override for tests / offline strategies.
   * Return null when the skill is not on the platform.
   * Throw SERVICE_UNREACHABLE-style errors to skip the check.
   */
  fetchPlatformSkill?: (serviceUrl: string, name: string) => Promise<PlatformSkillSnapshot | null>
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
export async function assertVersionBumpRequired(input: VersionBumpCheckInput): Promise<void> {
  if (!input.serviceUrl || input.serviceUrl.trim().length === 0) {
    return
  }
  const localVersion = normalizeVersionLabel(input.metadata.version)
  if (localVersion === null) {
    throw new PackageValidationError(
      'Skill 包内缺少有效的语义化版本号（version）',
      VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED,
      { version: input.metadata.version, ...versionRequiredExample({ ...input.metadata, packageKind: 'skill-md' }) }
    )
  }

  let snapshot: PlatformSkillSnapshot | null
  try {
    const fetcher = input.fetchPlatformSkill ?? fetchPlatformSkillFromService
    snapshot = await fetcher(input.serviceUrl, input.metadata.name)
  } catch (_error: unknown) {
    return
  }
  if (snapshot === null || snapshot.versions.length === 0) {
    return
  }

  const labels = snapshot.versions
    .map((item) => normalizeVersionLabel(item.versionLabel) ?? item.versionLabel)
    .filter((item) => item.length > 0)

  const maxFormal = latestFormalVersion(labels)
  const suggested = suggestNextVersion(maxFormal)

  if (labels.some((label) => label === localVersion)) {
    throw new PackageValidationError(
      VERSION_EXISTS_MESSAGE,
      VERSION_GATE_ERROR_CODES.VERSION_EXISTS,
      {
        version: localVersion,
        maxFormal,
        suggestedNextVersion: suggested,
        slug: snapshot.slug,
        hint: '请修改包内 version（单 Skill：SKILL.md frontmatter；复合包：package.json）为大于平台当前版本的 SemVer 后重新上传。CLI 不注入版本号。'
      }
    )
  }

  if (maxFormal !== null && compareSemver(localVersion, maxFormal) <= 0) {
    throw new PackageValidationError(
      VERSION_BUMP_REQUIRED_MESSAGE,
      VERSION_GATE_ERROR_CODES.VERSION_BUMP_REQUIRED,
      {
        version: localVersion,
        maxFormal,
        suggestedNextVersion: suggested,
        slug: snapshot.slug,
        hint: '请提升包内 version 后再上传（单 Skill：SKILL.md frontmatter；复合包：package.json）。'
      }
    )
  }
}

function suggestNextVersion(maxFormal: string | null): string {
  if (maxFormal === null) return '0.0.1'
  const parsed = parseSemver(maxFormal)
  if (parsed === null || parsed.prerelease.length > 0) return '0.0.1'
  return `${parsed.major}.${parsed.minor}.${parsed.patch + 1}`
}

/**
 * Resolve a platform skill by name (case-insensitive) or derived slug,
 * then load version history from GET /api/skills/{slug}.
 */
export async function fetchPlatformSkillFromService(
  serviceUrl: string,
  name: string
): Promise<PlatformSkillSnapshot | null> {
  const list = await apiRequest<Array<Record<string, unknown>>>(
    serviceUrl,
    '/api/skills?includeOffline=true'
  )
  if (!Array.isArray(list) || list.length === 0) return null

  const needle = name.trim().toLowerCase()
  const derivedSlug = slugifySkillName(name)
  const match = list.find((item) => {
    const rowName = String(item.name ?? '').trim().toLowerCase()
    const rowSlug = String(item.slug ?? '').trim().toLowerCase()
    return rowName === needle || (derivedSlug.length > 0 && rowSlug === derivedSlug)
  })
  if (match === undefined) return null

  const slug = String(match.slug ?? '')
  if (slug.length === 0) return null

  let versions: PlatformSkillVersion[] = []
  try {
    const detail = await apiRequest<Record<string, unknown>>(
      serviceUrl,
      `/api/skills/${encodeURIComponent(slug)}`
    )
    versions = extractVersions(detail)
  } catch (_error: unknown) {
    versions = []
  }

  if (versions.length === 0) {
    const listLabel = match.version_label
    if (typeof listLabel === 'string' && listLabel.length > 0) {
      versions = [{ versionLabel: listLabel }]
    }
  }

  return {
    slug,
    name: String(match.name ?? name),
    versions
  }
}

function extractVersions(detail: Record<string, unknown>): PlatformSkillVersion[] {
  const raw = detail.versions
  if (!Array.isArray(raw)) return []
  const versions: PlatformSkillVersion[] = []
  for (const entry of raw) {
    if (entry === null || typeof entry !== 'object') continue
    const record = entry as Record<string, unknown>
    const label = record.version_label ?? record.versionLabel
    if (typeof label !== 'string' || label.trim().length === 0) continue
    versions.push({ versionLabel: label.trim() })
  }
  return versions
}

/** Mirror backend SkillRepository.uniqueSlug base slug derivation. */
export function slugifySkillName(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

/** Exported for tests: validate a label the same way the package service does. */
export function assertSemverLabel(version: string): string {
  if (parseSemver(version) === null) {
    throw new PackageValidationError(
      'Skill 包内缺少有效的语义化版本号（version）',
      VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED,
      { version, ...versionRequiredExample({ packageKind: 'skill-md' }) }
    )
  }
  return normalizeVersionLabel(version) as string
}
