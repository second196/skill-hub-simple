export const DEFAULT_SERVICE_URL = 'http://127.0.0.1:8080';
export const EXIT_CODE = {
    success: 0,
    generic: 1,
    usage: 2,
    authentication: 3,
    network: 4,
    validation: 5
};
/**
 * Skill package version-gate error codes (CLI + server contract).
 * Version identity is SemVer version_label only — no content digest.
 */
export const VERSION_GATE_ERROR_CODES = {
    /** Package content is missing a valid SemVer version (SKILL.md frontmatter or package.json). */
    VERSION_SEMVER_REQUIRED: 'VERSION_SEMVER_REQUIRED',
    /** Package SemVer is not strictly greater than the latest formal platform version. */
    VERSION_BUMP_REQUIRED: 'VERSION_BUMP_REQUIRED',
    /** Same skill + same version_label already exists on the platform (immutable). */
    VERSION_EXISTS: 'VERSION_EXISTS'
};
export const VERSION_BUMP_REQUIRED_MESSAGE = 'Skill 包内 version 未超过平台最新正式版，请提升包内 version 后再上传';
export const VERSION_EXISTS_MESSAGE = '该 version 在平台上已存在且不可覆盖，请提升包内 version 后再上传';
/** Actionable example when version is missing/invalid. Version must live inside the package. */
export function versionRequiredExample(overrides = {}) {
    const packageKind = overrides.packageKind || 'composite';
    const name = overrides.name || (packageKind === 'composite' ? 'my-composite-skill' : 'my-skill');
    const description = overrides.description || (packageKind === 'composite' ? '复合Skill包说明' : 'Skill说明');
    const hint = packageKind === 'composite'
        ? '复合包：不要创建根 SKILL.md。version 必须写在包根 package.json 或 .codex-plugin/plugin.json。CLI 不注入版本号；平台/CLI 不会自动创建版本号，也不会默认 0.0.0。若源目录不完整：先复制到临时目录补全，再完整覆盖回源目录后上传。'
        : 'version 必须写在根 SKILL.md frontmatter（SemVer，如 1.0.0）。CLI 不注入版本号。可选在同一 frontmatter 声明 category。';
    const example = {
        cli: 'skillhub upload <skill-dir> --json'
    };
    if (packageKind === 'composite') {
        example.packageJson = {
            name,
            description,
            version: '1.0.0',
            category: '研发'
        };
    }
    else {
        example.skillMd =
            '---\n' +
                `name: ${name}\n` +
                `description: ${description}\n` +
                'version: 1.0.0\n' +
                'category: 研发\n' +
                '---\n';
    }
    return { hint, example };
}
