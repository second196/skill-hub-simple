import type { InstalledSkill, SkillUsage } from './types.js';
export declare const UPD_SOP_CHILDREN: readonly ["sop-requirement", "sop-design", "sop-implement", "sop-review", "sop-verification", "sop-release-check", "sop-documentation"];
export declare function slugify(value: string): string;
export declare function listInstalledSkills(extraRoots?: string[]): Promise<InstalledSkill[]>;
export declare function parseSkillFile(path: string, content: string): InstalledSkill | undefined;
export declare function normalizeVersionLabel(raw: string | undefined): string | undefined;
/** Resolve SemVer version label for a usage against the installed catalog. */
export declare function resolveSkillVersion(skills: InstalledSkill[], usage: {
    slug?: string;
    path?: string;
} | undefined): {
    versionLabel?: string;
};
export declare function withCompositeParents(skills: InstalledSkill[]): InstalledSkill[];
export declare function parentSlugsFor(slug: string, path?: string): string[];
export declare function primaryParentSlug(slug: string, path?: string): string | undefined;
export declare function matchSkillUsage(skills: InstalledSkill[], value: unknown): SkillUsage | undefined;
export declare function matchSkillInText(text: string): SkillUsage | undefined;
export declare function matchSlashSkillCommands(text: string): SkillUsage[];
export declare function matchSkill(skills: InstalledSkill[], value: unknown): InstalledSkill | undefined;
export declare function discoverProjectSkillRoots(seedPaths: string[]): Promise<string[]>;
