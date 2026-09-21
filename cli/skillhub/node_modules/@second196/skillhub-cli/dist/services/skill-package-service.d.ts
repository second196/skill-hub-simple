import { VERSION_GATE_ERROR_CODES } from '../shared/constants.js';
import { type PackageLimitOverrides, type PreparedSkillPackage, type SkillMetadataOverrides } from '../shared/types.js';
import { computeVersionDigest } from './version-digest.js';
export declare function prepareSkillPackage(inputPath: string, overrides?: PackageLimitOverrides, metadataOverrides?: SkillMetadataOverrides): Promise<PreparedSkillPackage>;
export { computeVersionDigest, VERSION_GATE_ERROR_CODES };
