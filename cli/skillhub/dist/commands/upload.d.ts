export interface UploadOptions {
    inputPaths: string[];
    serviceUrl: string;
    category?: string;
    name?: string;
    description?: string;
    json: boolean;
    /** Run local package completeness check before network upload. Default true. */
    check?: boolean;
}
/**
 * Upload path (ZIP / directory / SKILL.md share this pipeline):
 *   local completeness check (package must declare SemVer version)
 *   → prepareSkillPackage (parse version/category from package content only)
 *   → assertVersionBumpRequired (VERSION_EXISTS / VERSION_BUMP_REQUIRED)
 *   → HTTP POST /api/skills
 *   → optional list verification when --json
 *
 * Version is NEVER taken from CLI flags. If package metadata is incomplete:
 * copy skill to a temp dir → complete package.json/SKILL.md there →
 * overwrite the skill source with the complete tree → delete temp → upload again.
 */
export declare function uploadCommand(options: UploadOptions): Promise<string>;
export interface PrepareOptions {
    inputPath: string;
    json: boolean;
}
/**
 * Official prepare flow for incomplete skill sources:
 * temp copy → require complete package metadata → overwrite source → delete temp.
 * Does not invent version. Fails when version/category cannot be resolved from package content.
 */
export declare function prepareCommand(options: PrepareOptions): Promise<string>;
export interface CheckOptions {
    inputPath: string;
    json: boolean;
}
export declare function checkCommand(options: CheckOptions): Promise<string>;
