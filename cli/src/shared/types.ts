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
  version: string
}

export interface SkillMetadataOverrides {
  name?: string
  description?: string
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
  versionDigest: string
  manifestDigest: string
  metadata: SkillPackageMetadata
  manifest: SkillPackageManifestEntry[]
}

export const DEFAULT_PACKAGE_LIMITS: PackageLimits = {
  maxArchiveBytes: 10 * 1024 * 1024,
  maxExpandedBytes: 100 * 1024 * 1024,
  maxSingleFileBytes: 10 * 1024 * 1024,
  maxFiles: 1000,
  maxPathLength: 255
}

export type PackageLimitOverrides = Partial<PackageLimits>
