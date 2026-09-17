export interface PackageLimits {
    maxArchiveBytes: number;
    maxExpandedBytes: number;
    maxSingleFileBytes: number;
    maxFiles: number;
    maxPathLength: number;
}
export interface PackageFile {
    path: string;
    content: Uint8Array;
}
export interface SkillPackageMetadata {
    name: string;
    description: string;
    version: string;
}
export interface SkillMetadataOverrides {
    name?: string;
    description?: string;
}
export interface SkillPackageManifestEntry {
    path: string;
    size: number;
    digest: string;
}
export interface PreparedSkillPackage {
    sourceType: 'DIRECTORY' | 'ZIP';
    archive: Uint8Array;
    artifactDigest: string;
    versionDigest: string;
    manifestDigest: string;
    metadata: SkillPackageMetadata;
    manifest: SkillPackageManifestEntry[];
}
export declare const DEFAULT_PACKAGE_LIMITS: PackageLimits;
export type PackageLimitOverrides = Partial<PackageLimits>;
