import { PackageValidationError } from '../shared/errors.js';
const WINDOWS_ABSOLUTE_PATH = /^[a-zA-Z]:/;
/** Directories never treated as skill business content. */
const EXCLUDED_DIRECTORIES = new Set(['.git', '.skillhub', 'node_modules']);
/** Exact file names never treated as skill business content. */
const EXCLUDED_FILES = new Set([
    '.env',
    '.npmrc',
    '.pypirc',
    '.netrc',
    '.ds_store',
    'credentials.json',
    'id_rsa',
    'id_ed25519'
]);
export function normalizePackagePath(value, maxLength) {
    if (value.length === 0 || value.includes('\0') || value.includes('\\')) {
        throw unsafePath(value);
    }
    if (value.startsWith('/') || WINDOWS_ABSOLUTE_PATH.test(value)) {
        throw unsafePath(value);
    }
    const normalized = value.normalize('NFC');
    const segments = normalized.split('/');
    if (segments.some((segment) => segment.length === 0 || segment === '.' || segment === '..')) {
        throw unsafePath(value);
    }
    if (Array.from(normalized).length > maxLength) {
        throw new PackageValidationError('Skill 包内路径超过长度限制', 'PACKAGE_PATH_TOO_LONG', { path: normalized });
    }
    return normalized;
}
/**
 * Package-level exclusion: credentials, VCS metadata, dependency dirs, junk.
 * Used when reading directories and when rejecting sensitive ZIP entries.
 */
export function shouldExcludePackagePath(value) {
    const segments = value.replace(/\\/g, '/').split('/');
    const fileName = segments[segments.length - 1]?.toLowerCase() ?? '';
    return segments.some((segment) => EXCLUDED_DIRECTORIES.has(segment.toLowerCase()))
        || EXCLUDED_FILES.has(fileName)
        || fileName.startsWith('.env.')
        || fileName.endsWith('.pem')
        || fileName.endsWith('.key')
        || fileName.endsWith('.tmp');
}
function unsafePath(path) {
    return new PackageValidationError('Skill 包包含不安全路径', 'UNSAFE_PACKAGE_PATH', { path });
}
