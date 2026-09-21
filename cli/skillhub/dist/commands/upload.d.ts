export interface UploadOptions {
    inputPaths: string[];
    serviceUrl: string;
    category?: string;
    name?: string;
    description?: string;
    json: boolean;
    /** Run local package completeness check before network upload. Default true. */
    check?: boolean;
    /**
     * Skill source of record on disk (usually the same as input path).
     * After upload, this path MUST still pass verify-source.
     */
    sourceDir?: string;
}
/**
 * Upload pipeline:
 *   check package completeness on the path being uploaded
 *   → prepareSkillPackage (version/category from package content only)
 *   → assertVersionBumpRequired
 *   → POST /api/skills
 *   → list verification + verify-source on input path (and optional --source-dir)
 *
 * Success requires: platform row matches AND source directory metadata remains complete.
 * Temp-only packages without write-back fail verify-source.
 */
export declare function uploadCommand(options: UploadOptions): Promise<string>;
export interface SourceVerifyPathResult {
    path: string;
    ok: boolean;
    packageKind?: 'skill-md' | 'composite';
    hasRootSkillMd?: boolean;
    hasPackageJson?: boolean;
    metadata?: Record<string, unknown>;
    message?: string;
    missing?: string[];
}
export interface SourceVerifyResult {
    ok: boolean;
    message?: string;
    paths: SourceVerifyPathResult[];
}
/** Verify skill source directories still contain complete package metadata on disk. */
export declare function verifySourcePaths(sourcePaths: string[]): Promise<SourceVerifyResult>;
export interface PrepareOptions {
    /** Skill source of record that must remain complete after prepare. */
    inputPath: string;
    /** Optional complete temp package; when set, overwrite source from this tree then delete it. */
    completeFrom?: string;
    json: boolean;
}
/**
 * prepare
 * - Without --complete-from: source itself must already be complete; normalize via temp copy and write back.
 * - With --complete-from <temp>: temp must be complete → overwrite source → delete temp → verify source.
 * Never invents version/category.
 */
export declare function prepareCommand(options: PrepareOptions): Promise<string>;
export interface CheckOptions {
    inputPath: string;
    json: boolean;
}
export declare function checkCommand(options: CheckOptions): Promise<string>;
export interface VerifySourceOptions {
    sourcePath: string;
    json: boolean;
}
export declare function verifySourceCommand(options: VerifySourceOptions): Promise<string>;
