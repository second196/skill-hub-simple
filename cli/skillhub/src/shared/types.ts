export interface PackageLimits {
  maxArchiveBytes: number
  maxExpandedBytes: number
  maxSingleFileBytes: number
  maxFiles: number
  maxPathLength: number
}

export interface PackageFile {
  path: string
  content: Uint8Array
}

export interface SkillPackageMetadata {
  name: string
  description: string
  /** Normalized SemVer label (no leading `v`). Required — never defaulted to 0.0.0. */
  version: string
}

export interface SkillMetadataOverrides {
  name?: string
  description?: string
  /** Explicit SemVer for composite packages / override. Validated; no silent default. */
  version?: string
}

export interface SkillPackageManifestEntry {
  path: string
  size: number
  digest: string
}

export interface PreparedSkillPackage {
  sourceType: 'DIRECTORY' | 'ZIP'
  archive: Uint8Array
  artifactDigest: string
  /**
   * Content fingerprint of normalized package files (NOT sha256 of version label).
   * See services/version-digest.ts for the exact algorithm backend must match.
   */
  versionDigest: string
  manifestDigest: string
  metadata: SkillPackageMetadata
  manifest: SkillPackageManifestEntry[]
}

export const DEFAULT_PACKAGE_LIMITS: PackageLimits = {
  maxArchiveBytes: 1024 * 1024 * 1024,
  maxExpandedBytes: 1024 * 1024 * 1024,
  maxSingleFileBytes: 1024 * 1024 * 1024,
  maxFiles: 1000,
  maxPathLength: 255
}

export type PackageLimitOverrides = Partial<PackageLimits>
