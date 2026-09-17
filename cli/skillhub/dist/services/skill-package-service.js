import { createHash } from 'node:crypto';
import { lstat, readFile, readdir } from 'node:fs/promises';
import { basename, extname, join, relative, resolve } from 'node:path';
import { parseDocument } from 'yaml';
import { z } from 'zod';
import { createArchive, readArchive, resolvePackageLimits } from '../platform/archive.js';
import { normalizePackagePath, shouldExcludePackagePath } from '../platform/paths.js';
import { PackageValidationError } from '../shared/errors.js';
const SEMANTIC_VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;
const metadataSchema = z.object({
    name: z.string().trim().min(1).max(100),
    description: z.string().trim().min(1).max(2000),
    version: z.string().trim().regex(SEMANTIC_VERSION).optional().default('0.0.0')
}).passthrough();
export async function prepareSkillPackage(inputPath, overrides = {}, metadataOverrides = {}) {
    const limits = resolvePackageLimits(overrides);
    let inputStat;
    try {
        inputStat = await lstat(inputPath);
    }
    catch (_error) {
        throw new PackageValidationError('Skill 路径不存在或不可读', 'SKILL_PATH_NOT_READABLE');
    }
    if (inputStat.isSymbolicLink()) {
        throw new PackageValidationError('Skill 路径不允许是符号链接', 'SYMLINK_NOT_ALLOWED');
    }
    let sourceType;
    let archive;
    let files;
    if (inputStat.isDirectory()) {
        sourceType = 'DIRECTORY';
        files = await readDirectoryFiles(inputPath, overrides);
        archive = createArchive(files, overrides);
    }
    else if (inputStat.isFile()) {
        if (inputPath.toLowerCase().endsWith('.md')) {
            sourceType = 'DIRECTORY';
            const content = new Uint8Array(await readFile(inputPath));
            files = [{ path: 'SKILL.md', content }];
            archive = createArchive(files, overrides);
        }
        else {
            sourceType = 'ZIP';
            if (inputStat.size > limits.maxArchiveBytes) {
                throw new PackageValidationError('Skill 压缩包超过大小限制', 'PACKAGE_ARCHIVE_TOO_LARGE');
            }
            archive = new Uint8Array(await readFile(inputPath));
            files = readArchive(archive, overrides);
            const sensitiveEntry = files.find((file) => shouldExcludePackagePath(file.path));
            if (sensitiveEntry !== undefined) {
                throw new PackageValidationError('Skill ZIP 包含版本库或凭据文件', 'SENSITIVE_PACKAGE_PATH', { path: sensitiveEntry.path });
            }
        }
    }
    else {
        throw new PackageValidationError('Skill 路径必须是目录或 ZIP 文件', 'UNSUPPORTED_SKILL_PATH');
    }
    if (files.length === 0) {
        throw new PackageValidationError('Skill 包不能为空', 'EMPTY_SKILL_PACKAGE');
    }
    files = normalizePackageRoot(files);
    if (!files.some((file) => file.path === 'SKILL.md')) {
        files = addGeneratedRootSkill(files, inputPath, metadataOverrides);
    }
    archive = createArchive(files, overrides);
    const skillFile = files.find((file) => file.path === 'SKILL.md');
    if (skillFile === undefined) {
        throw new PackageValidationError('Skill 包根目录缺少 SKILL.md', 'SKILL_FILE_REQUIRED');
    }
    const metadata = parseMetadata(skillFile.content);
    const manifest = createManifest(files);
    const manifestJson = JSON.stringify(manifest);
    const normalizedVersion = JSON.stringify({ metadata, manifest });
    return {
        sourceType,
        archive,
        artifactDigest: sha256(archive),
        versionDigest: sha256(normalizedVersion),
        manifestDigest: sha256(manifestJson),
        metadata,
        manifest
    };
}
function normalizePackageRoot(files) {
    if (files.some((file) => file.path === 'SKILL.md'))
        return files;
    const skillFiles = files.filter((file) => file.path.endsWith('/SKILL.md'));
    if (skillFiles.length !== 1)
        return files;
    const prefix = skillFiles[0].path.slice(0, -'SKILL.md'.length);
    if (!files.every((file) => file.path.startsWith(prefix)))
        return files;
    return files.map((file) => ({ ...file, path: file.path.slice(prefix.length) }));
}
function addGeneratedRootSkill(files, inputPath, metadataOverrides) {
    const nestedSkillCount = files.filter((file) => file.path.endsWith('/SKILL.md')).length;
    if (nestedSkillCount === 0) {
        throw new PackageValidationError('Skill 包根目录缺少 SKILL.md', 'SKILL_FILE_REQUIRED');
    }
    const packageMetadata = readPackageMetadata(files);
    const fallbackName = basename(inputPath, extname(inputPath)).trim() || 'composite-skill';
    const name = cleanMetadataValue(metadataOverrides.name || packageMetadata.name || readmeTitle(files) || fallbackName, fallbackName, 100);
    const description = cleanMetadataValue(metadataOverrides.description
        || packageMetadata.description
        || readmeDescription(files)
        || `复合Skill包，包含 ${nestedSkillCount} 个子Skill。`, `复合Skill包，包含 ${nestedSkillCount} 个子Skill。`, 2000);
    const body = [
        '---',
        `name: ${yamlQuote(name)}`,
        `description: ${yamlQuote(description)}`,
        'version: 0.0.0',
        '---',
        '',
        `# ${name}`,
        '',
        '这是一个复合Skill包，包含若干可独立使用的子Skill。子Skill及其资源保留在原始目录结构中。',
        ''
    ].join('\n');
    return [{ path: 'SKILL.md', content: new TextEncoder().encode(body) }, ...files];
}
function readPackageMetadata(files) {
    for (const path of ['package.json', '.codex-plugin/plugin.json']) {
        const file = files.find((item) => item.path === path);
        if (file === undefined)
            continue;
        try {
            const value = JSON.parse(new TextDecoder().decode(file.content));
            return {
                name: typeof value.name === 'string' ? value.name : undefined,
                description: typeof value.description === 'string' ? value.description : undefined
            };
        }
        catch {
            continue;
        }
    }
    return {};
}
function readmeTitle(files) {
    const readme = readTextFile(files, 'README.md');
    return readme?.match(/^#\s+(.+?)\s*$/m)?.[1]?.trim();
}
function readmeDescription(files) {
    const readme = readTextFile(files, 'README.md');
    if (!readme)
        return undefined;
    const withoutTitle = readme.replace(/^#\s+.+?\s*$/m, '');
    const paragraph = withoutTitle
        .split(/\r?\n\s*\r?\n/)
        .map((item) => item.replace(/^\s*[-*>`#].*$/gm, '').replace(/\s+/g, ' ').trim())
        .find((item) => item.length > 0);
    return paragraph;
}
function readTextFile(files, path) {
    const file = files.find((item) => item.path.toLowerCase() === path.toLowerCase());
    if (!file)
        return undefined;
    try {
        return new TextDecoder('utf-8', { fatal: true }).decode(file.content);
    }
    catch {
        return undefined;
    }
}
function cleanMetadataValue(value, fallback, maxLength) {
    const cleaned = value.trim().replace(/\s+/g, ' ').slice(0, maxLength);
    return cleaned || fallback;
}
function yamlQuote(value) {
    return `'${value.replace(/'/g, "''")}'`;
}
async function readDirectoryFiles(rootPath, overrides) {
    const limits = resolvePackageLimits(overrides);
    const root = resolve(rootPath);
    const files = [];
    let expandedBytes = 0;
    async function visit(directory) {
        let entries;
        try {
            entries = await readdir(directory, { withFileTypes: true });
        }
        catch (_error) {
            throw new PackageValidationError('Skill 目录不可读', 'SKILL_PATH_NOT_READABLE');
        }
        entries.sort((left, right) => Buffer.compare(Buffer.from(left.name, 'utf8'), Buffer.from(right.name, 'utf8')));
        for (const entry of entries) {
            const absolutePath = join(directory, entry.name);
            const relativePath = normalizePackagePath(relative(root, absolutePath).replace(/\\/g, '/'), limits.maxPathLength);
            if (shouldExcludePackagePath(relativePath))
                continue;
            const entryStat = await lstat(absolutePath);
            if (entryStat.isSymbolicLink()) {
                throw new PackageValidationError('Skill 包不允许符号链接', 'SYMLINK_NOT_ALLOWED', { path: relativePath });
            }
            if (entryStat.isDirectory()) {
                await visit(absolutePath);
                continue;
            }
            if (!entryStat.isFile())
                continue;
            if (files.length + 1 > limits.maxFiles) {
                throw new PackageValidationError('Skill 包文件数量超过限制', 'PACKAGE_FILE_COUNT_EXCEEDED');
            }
            if (entryStat.size > limits.maxSingleFileBytes) {
                throw new PackageValidationError('Skill 包内单个文件超过大小限制', 'PACKAGE_FILE_TOO_LARGE', { path: relativePath });
            }
            expandedBytes += entryStat.size;
            if (expandedBytes > limits.maxExpandedBytes) {
                throw new PackageValidationError('Skill 包解压后超过大小限制', 'PACKAGE_EXPANDED_TOO_LARGE');
            }
            try {
                const content = new Uint8Array(await readFile(absolutePath));
                files.push({ path: relativePath, content });
            }
            catch (_error) {
                throw new PackageValidationError('Skill 包包含不可读文件', 'SKILL_FILE_NOT_READABLE', { path: relativePath });
            }
        }
    }
    await visit(root);
    return files;
}
function parseMetadata(content) {
    let markdown;
    try {
        markdown = new TextDecoder('utf-8', { fatal: true }).decode(content);
    }
    catch (_error) {
        throw new PackageValidationError('SKILL.md 必须使用 UTF-8', 'INVALID_SKILL_ENCODING');
    }
    const lines = markdown.replace(/^\uFEFF/, '').split(/\r?\n/);
    if (lines[0] !== '---') {
        throw new PackageValidationError('SKILL.md 缺少 YAML frontmatter', 'INVALID_SKILL_FRONTMATTER');
    }
    const closingIndex = lines.findIndex((line, index) => index > 0 && (line === '---' || line === '...'));
    if (closingIndex < 0) {
        throw new PackageValidationError('SKILL.md 的 YAML frontmatter 未闭合', 'INVALID_SKILL_FRONTMATTER');
    }
    const document = parseDocument(lines.slice(1, closingIndex).join('\n'), {
        schema: 'core',
        strict: true,
        uniqueKeys: true
    });
    if (document.errors.length > 0) {
        throw new PackageValidationError('SKILL.md 的 YAML frontmatter 无法解析', 'INVALID_SKILL_FRONTMATTER');
    }
    let value;
    try {
        value = document.toJS({ maxAliasCount: 0 });
    }
    catch (_error) {
        throw new PackageValidationError('SKILL.md 的 YAML frontmatter 不安全', 'INVALID_SKILL_FRONTMATTER');
    }
    const result = metadataSchema.safeParse(value);
    if (!result.success) {
        throw new PackageValidationError('SKILL.md 的名称、描述或版本无效', 'INVALID_SKILL_METADATA');
    }
    return {
        name: result.data.name,
        description: result.data.description,
        version: result.data.version
    };
}
function createManifest(files) {
    return [...files]
        .sort((left, right) => Buffer.compare(Buffer.from(left.path, 'utf8'), Buffer.from(right.path, 'utf8')))
        .map((file) => ({ path: file.path, size: file.content.byteLength, digest: sha256(file.content) }));
}
function sha256(value) {
    return createHash('sha256').update(value).digest('hex');
}
