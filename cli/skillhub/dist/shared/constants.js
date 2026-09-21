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
