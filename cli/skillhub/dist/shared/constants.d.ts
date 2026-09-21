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
 * Version identity is SemVer version_label only — no content digest.
 */
export declare const VERSION_GATE_ERROR_CODES: {
    /** Package content is missing a valid SemVer version (SKILL.md frontmatter or package.json). */
    readonly VERSION_SEMVER_REQUIRED: "VERSION_SEMVER_REQUIRED";
    /** Package SemVer is not strictly greater than the latest formal platform version. */
    readonly VERSION_BUMP_REQUIRED: "VERSION_BUMP_REQUIRED";
    /** Same skill + same version_label already exists on the platform (immutable). */
    readonly VERSION_EXISTS: "VERSION_EXISTS";
};
export type VersionGateErrorCode = (typeof VERSION_GATE_ERROR_CODES)[keyof typeof VERSION_GATE_ERROR_CODES];
export declare const VERSION_BUMP_REQUIRED_MESSAGE = "Skill \u5305\u5185 version \u672A\u8D85\u8FC7\u5E73\u53F0\u6700\u65B0\u6B63\u5F0F\u7248\uFF0C\u8BF7\u63D0\u5347\u5305\u5185 version \u540E\u518D\u4E0A\u4F20";
export declare const VERSION_EXISTS_MESSAGE = "\u8BE5 version \u5728\u5E73\u53F0\u4E0A\u5DF2\u5B58\u5728\u4E14\u4E0D\u53EF\u8986\u76D6\uFF0C\u8BF7\u63D0\u5347\u5305\u5185 version \u540E\u518D\u4E0A\u4F20";
/** Actionable example when version is missing/invalid. Version must live inside the package. */
export declare function versionRequiredExample(overrides?: {
    name?: string;
    description?: string;
    /** composite → package.json; skill-md → frontmatter example only */
    packageKind?: 'composite' | 'skill-md';
}): {
    hint: string;
    example: {
        packageJson?: Record<string, string> | undefined;
        skillMd?: string | undefined;
        cli: string;
    };
};
