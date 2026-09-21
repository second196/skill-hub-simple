import { cp, lstat, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { prepareSkillPackage, inspectSkillPackage } from '../services/skill-package-service.js';
import { assertVersionBumpRequired } from '../services/version-gate.js';
import { apiRequest } from '../clients/api-client.js';
import { CliError } from '../shared/errors.js';
/**
 * Upload path (ZIP / directory / SKILL.md share this pipeline):
 *   local completeness check (package must declare SemVer version)
 *   → prepareSkillPackage (parse version/category from package content only)
 *   → assertVersionBumpRequired (VERSION_EXISTS / VERSION_BUMP_REQUIRED)
 *   → HTTP POST /api/skills
 *   → optional list verification when --json
 *
 * Version is NEVER taken from CLI flags. If package metadata is incomplete:
 * copy skill to a temp dir → complete package.json/SKILL.md there →
 * overwrite the skill source with the complete tree → delete temp → upload again.
 */
export async function uploadCommand(options) {
    const results = [];
    for (const inputPath of options.inputPaths) {
        try {
            if (options.check !== false) {
                const inspection = await inspectSkillPackage(inputPath);
                if (!inspection.ok) {
                    throw new CliError(inspection.messages[0] || 'Skill 包内元数据不完整', 'VERSION_SEMVER_REQUIRED', 5, {
                        missing: inspection.missing,
                        packageKind: inspection.packageKind,
                        prepareFlow: [
                            '创建临时目录并完整复制 Skill',
                            '在临时目录补全包内 version（及 category）',
                            '将临时目录完整内容覆盖回技能源目录',
                            '删除临时目录',
                            `skillhub upload ${inputPath} --json`
                        ]
                    });
                }
            }
            const prepared = await prepareSkillPackage(inputPath, {}, {
                name: options.name,
                description: options.description,
                category: options.category
            });
            await assertVersionBumpRequired({
                serviceUrl: options.serviceUrl,
                metadata: prepared.metadata
            });
            const form = new FormData();
            form.append('file', new Blob([prepared.archive]), `${basename(inputPath)}.zip`);
            // Category is optional: server priority = package > existing platform > this value > 其他
            if (prepared.metadata.category)
                form.append('category', prepared.metadata.category);
            else if (options.category)
                form.append('category', options.category);
            const result = await apiRequest(options.serviceUrl, '/api/skills', {
                method: 'POST',
                body: form
            });
            const version = prepared.metadata.version;
            const slug = String(result.slug ?? '');
            const category = String(result.category ?? prepared.metadata.category ?? options.category ?? '');
            const verify = await verifyUpload(options.serviceUrl, slug, version, category);
            results.push({
                ok: true,
                inputPath,
                version,
                slug,
                category,
                verify,
                ...result
            });
        }
        catch (error) {
            results.push({
                ok: false,
                inputPath,
                message: error instanceof Error ? error.message : '上传失败',
                code: error instanceof CliError ? error.code : 'UPLOAD_FAILED',
                details: error instanceof CliError ? error.details : undefined
            });
        }
    }
    const succeeded = results.filter((item) => item.ok === true);
    const failed = results.filter((item) => item.ok !== true);
    if (failed.length > 0)
        process.exitCode = 1;
    if (options.json) {
        return JSON.stringify({
            ok: failed.length === 0,
            total: results.length,
            succeeded: succeeded.length,
            failed: failed.length,
            results
        });
    }
    const lines = [`上传完成：成功 ${succeeded.length} 个，失败 ${failed.length} 个`];
    for (const item of succeeded) {
        const verify = item.verify;
        lines.push(`成功：${String(item.inputPath)} → ${String(item.name)}（${String(item.slug)}）v${String(item.version)} [${String(item.category)}]`);
        if (verify && verify.ok === false) {
            lines.push(`  警告：上传后核验失败 — ${String(verify.message || '')}`);
        }
    }
    for (const item of failed) {
        lines.push(`失败：${String(item.inputPath)} → ${String(item.message)}`);
    }
    return lines.join('\n');
}
/** Closed-loop verification: list --json must show slug + version_label + category. */
async function verifyUpload(serviceUrl, slug, version, category) {
    if (!slug || !version) {
        return { ok: false, message: '上传响应缺少 slug/version，无法核验' };
    }
    try {
        const items = await apiRequest(serviceUrl, '/api/skills?status=ACTIVE');
        const row = Array.isArray(items)
            ? items.find((item) => String(item.slug ?? '') === slug)
            : undefined;
        if (!row) {
            return { ok: false, message: `list 未找到 slug=${slug}` };
        }
        const rowVersion = String(row.version_label ?? '');
        const rowCategory = String(row.category ?? '');
        if (rowVersion !== version) {
            return { ok: false, message: `version_label 不一致：期望 ${version}，实际 ${rowVersion}`, row };
        }
        if (category && rowCategory !== category) {
            return { ok: false, message: `category 不一致：期望 ${category}，实际 ${rowCategory}`, row };
        }
        return { ok: true, row };
    }
    catch (error) {
        return { ok: false, message: error instanceof Error ? error.message : String(error) };
    }
}
/**
 * Official prepare flow for incomplete skill sources:
 * temp copy → require complete package metadata → overwrite source → delete temp.
 * Does not invent version. Fails when version/category cannot be resolved from package content.
 */
export async function prepareCommand(options) {
    const source = resolve(options.inputPath);
    const inspection = await inspectSkillPackage(source);
    if (!inspection.ok) {
        throw new CliError(inspection.messages[0] || 'Skill 包内元数据不完整，无法 prepare', 'VERSION_SEMVER_REQUIRED', 5, {
            missing: inspection.missing,
            prepareFlow: [
                '创建临时目录并完整复制 Skill',
                '在临时目录补全 package.json / SKILL.md 的 version（及 category）',
                'skillhub prepare <skill-dir>  # 校验通过后覆盖回源目录',
                'skillhub upload <skill-dir> --json'
            ]
        });
    }
    const stat = await lstat(source);
    if (!stat.isDirectory()) {
        throw new CliError('prepare 仅支持技能源目录', 'UNSUPPORTED_SKILL_PATH', 5);
    }
    const tempRoot = await mkdtemp(join(tmpdir(), 'skillhub-prepare-'));
    const tempSkill = join(tempRoot, 'skill');
    try {
        await mkdir(tempSkill, { recursive: true });
        await cp(source, tempSkill, { recursive: true, force: true });
        const tempInspection = await inspectSkillPackage(tempSkill);
        if (!tempInspection.ok || !tempInspection.metadata?.version) {
            throw new CliError('临时目录中的 Skill 包仍不完整', 'VERSION_SEMVER_REQUIRED', 5, { messages: tempInspection.messages, missing: tempInspection.missing });
        }
        // Overwrite source with the complete temp tree.
        await rm(source, { recursive: true, force: true });
        await mkdir(source, { recursive: true });
        await cp(tempSkill, source, { recursive: true, force: true });
        const finalInspection = await inspectSkillPackage(source);
        const payload = {
            ok: finalInspection.ok,
            source,
            packageKind: finalInspection.packageKind,
            metadata: finalInspection.metadata,
            messages: finalInspection.ok
                ? ['已用完整包覆盖技能源目录，可直接 upload']
                : finalInspection.messages
        };
        return options.json ? JSON.stringify(payload) : payload.messages.join('\n');
    }
    finally {
        await rm(tempRoot, { recursive: true, force: true });
    }
}
export async function checkCommand(options) {
    const inspection = await inspectSkillPackage(options.inputPath);
    const category = inspection.metadata?.category;
    if (options.json) {
        return JSON.stringify({
            ...inspection,
            categoryDeclaredInPackage: Boolean(category),
            categoryHint: category
                ? `包内 category=${category}`
                : '包内未声明 category：上传前请先读 skill 内容判定分类并写入包内；禁止未读内容默认「其他」'
        });
    }
    const lines = [
        `检查：${options.inputPath}`,
        `类型：${inspection.packageKind}`,
        `结果：${inspection.ok ? '通过' : '未通过'}`
    ];
    if (inspection.metadata) {
        lines.push(`name: ${inspection.metadata.name}`);
        lines.push(`version: ${inspection.metadata.version}`);
        lines.push(`category: ${category || '(未声明)'}`);
        if (!category) {
            lines.push('- 提示：包内未声明 category。请读 skill 内容判定分类后写入包内，再 upload。');
        }
    }
    for (const message of inspection.messages)
        lines.push(`- ${message}`);
    return lines.join('\n');
}
