import { type PackageLimitOverrides, type PreparedSkillPackage, type SkillMetadataOverrides } from '../shared/types.js';
export declare function prepareSkillPackage(inputPath: string, overrides?: PackageLimitOverrides, metadataOverrides?: SkillMetadataOverrides): Promise<PreparedSkillPackage>;
