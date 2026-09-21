import { VERSION_GATE_ERROR_CODES } from '../shared/constants.js';
import { type PackageLimitOverrides, type PreparedSkillPackage, type SkillMetadataOverrides, type SkillPackageMetadata } from '../shared/types.js';
/**
 * Prepare a skill package for upload.
 * Version/category are parsed from package content only — CLI never injects version.
 */
export declare function prepareSkillPackage(inputPath: string, overrides?: PackageLimitOverrides, metadataOverrides?: SkillMetadataOverrides): Promise<PreparedSkillPackage>;
/** Local preflight used by skillhub check / upload. Does not invent missing fields. */
export declare function inspectSkillPackage(inputPath: string): Promise<{
    ok: boolean;
    packageKind: 'skill-md' | 'composite';
    metadata?: SkillPackageMetadata;
    missing: string[];
    messages: string[];
}>;
export { VERSION_GATE_ERROR_CODES };
