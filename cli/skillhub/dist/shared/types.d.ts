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
    /** Normalized SemVer label (no leading `v`). Required — never defaulted to 0.0.0. */
    version: string;
    /** Optional category declared inside the package. */
    category?: string;
}
export interface SkillMetadataOverrides {
    name?: string;
    description?: string;
    /** Category override used only when the package does not declare one. */
    category?: string;
}
export interface SkillPackageManifestEntry {
    path: string;
    size: number;
}
export interface PreparedSkillPackage {
    sourceType: 'DIRECTORY' | 'ZIP';
    archive: Uint8Array;
    metadata: SkillPackageMetadata;
    manifest: SkillPackageManifestEntry[];
}
export declare const DEFAULT_PACKAGE_LIMITS: PackageLimits;
export type PackageLimitOverrides = Partial<PackageLimits>;
