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
 * Backend should re-validate with the same codes before accepting upload.
 */
export const VERSION_GATE_ERROR_CODES = {
    /** SKILL.md frontmatter version is missing / empty / "latest" / not SemVer */
    VERSION_SEMVER_REQUIRED: 'VERSION_SEMVER_REQUIRED',
    /** Local content digest differs from platform and version_label was not bumped */
    VERSION_BUMP_REQUIRED: 'VERSION_BUMP_REQUIRED',
    /** Same version_label exists on platform with a different content digest */
    VERSION_DIGEST_CONFLICT: 'VERSION_DIGEST_CONFLICT'
};
export const VERSION_BUMP_REQUIRED_MESSAGE = 'Skill 内容已修改但版本号未升，请先修改 SKILL.md 中的 version 再上传';
export const VERSION_DIGEST_CONFLICT_MESSAGE = 'Skill 版本号在平台上已存在但内容摘要不一致，请升版后再上传';
/** Actionable example when version is missing/invalid. Composite packages must not invent root SKILL.md. */
export function versionRequiredExample(overrides = {}) {
    const packageKind = overrides.packageKind || 'composite';
    const name = overrides.name || (packageKind === 'composite' ? 'my-composite-skill' : 'my-skill');
    const description = overrides.description || (packageKind === 'composite' ? '复合Skill包说明' : 'Skill说明');
    const hint = packageKind === 'composite'
        ? '复合包：不要创建根 SKILL.md。version 必须本地提供——包根 package.json 的 version，或 CLI --skill-version（全局 --version 是 CLI 自身版本开关，不是技能版本）。平台/CLI 不会自动创建版本号，也不会默认 0.0.0。'
        : 'version 必须为语义化版本（如 1.0.0）；写在 SKILL.md frontmatter，或用 package.json / CLI --skill-version。平台/CLI 不会自动创建版本号。';
    const example = {
        packageJson: {
            name,
            description,
            version: '1.0.0'
        },
        cli: 'skillhub upload <path> --skill-version 1.0.0'
    };
    if (packageKind === 'skill-md') {
        example.skillMd =
            '---\n' +
                `name: ${name}\n` +
                `description: ${description}\n` +
                'version: 1.0.0\n' +
                '---\n';
    }
    return { hint, example };
}
