export declare const DEFAULT_SERVICE_URL = "http://127.0.0.1:8080";
export declare const EXIT_CODE: {
    readonly success: 0;
    readonly generic: 1;
    readonly usage: 2;
    readonly authentication: 3;
    readonly network: 4;
    readonly validation: 5;
};
/**
 * Skill package version-gate error codes (CLI + server contract).
 * Backend should re-validate with the same codes before accepting upload.
 */
export declare const VERSION_GATE_ERROR_CODES: {
    /** SKILL.md frontmatter version is missing / empty / "latest" / not SemVer */
    readonly VERSION_SEMVER_REQUIRED: "VERSION_SEMVER_REQUIRED";
    /** Local content digest differs from platform and version_label was not bumped */
    readonly VERSION_BUMP_REQUIRED: "VERSION_BUMP_REQUIRED";
    /** Same version_label exists on platform with a different content digest */
    readonly VERSION_DIGEST_CONFLICT: "VERSION_DIGEST_CONFLICT";
};
export type VersionGateErrorCode = (typeof VERSION_GATE_ERROR_CODES)[keyof typeof VERSION_GATE_ERROR_CODES];
export declare const VERSION_BUMP_REQUIRED_MESSAGE = "Skill \u5185\u5BB9\u5DF2\u4FEE\u6539\u4F46\u7248\u672C\u53F7\u672A\u5347\uFF0C\u8BF7\u5148\u4FEE\u6539 SKILL.md \u4E2D\u7684 version \u518D\u4E0A\u4F20";
export declare const VERSION_DIGEST_CONFLICT_MESSAGE = "Skill \u7248\u672C\u53F7\u5728\u5E73\u53F0\u4E0A\u5DF2\u5B58\u5728\u4F46\u5185\u5BB9\u6458\u8981\u4E0D\u4E00\u81F4\uFF0C\u8BF7\u5347\u7248\u540E\u518D\u4E0A\u4F20";
/** Actionable example when version is missing/invalid. Composite packages must not invent root SKILL.md. */
export declare function versionRequiredExample(overrides?: {
    name?: string;
    description?: string;
    /** composite → package.json; skill-md → frontmatter example only */
    packageKind?: 'composite' | 'skill-md';
}): {
    hint: string;
    example: {
        packageJson: Record<string, string>;
        skillMd?: string | undefined;
        cli: string;
    };
};
