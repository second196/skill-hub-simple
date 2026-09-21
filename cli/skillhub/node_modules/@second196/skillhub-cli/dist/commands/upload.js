import { basename } from 'node:path';
import { prepareSkillPackage } from '../services/skill-package-service.js';
import { assertVersionBumpRequired } from '../services/version-gate.js';
import { apiRequest } from '../clients/api-client.js';
import { CliError } from '../shared/errors.js';
/**
 * Upload path (ZIP / directory / SKILL.md share this pipeline):
 *   prepareSkillPackage (validate metadata + version SemVer + compute versionDigest)
 *   → assertVersionBumpRequired (VERSION_BUMP_REQUIRED / VERSION_DIGEST_CONFLICT)
 *   → HTTP POST /api/skills
 */
export async function uploadCommand(options) {
    const results = [];
    for (const inputPath of options.inputPaths) {
        try {
            const prepared = await prepareSkillPackage(inputPath, {}, {
                name: options.name,
                description: options.description,
                version: options.version
            });
            await assertVersionBumpRequired({
                serviceUrl: options.serviceUrl,
                metadata: prepared.metadata,
                versionDigest: prepared.versionDigest
            });
            const form = new FormData();
            form.append('file', new Blob([prepared.archive]), `${basename(inputPath)}.zip`);
            form.append('category', options.category);
            // Forward client-computed version gate fields for server-side re-validation.
            form.append('version', prepared.metadata.version);
            form.append('versionDigest', prepared.versionDigest);
            const result = await apiRequest(options.serviceUrl, '/api/skills', {
                method: 'POST',
                body: form
            });
            results.push({
                ok: true,
                inputPath,
                version: prepared.metadata.version,
                versionDigest: prepared.versionDigest,
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
    const succeeded = results.filter((item) => item.ok);
    const failed = results.filter((item) => !item.ok);
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
        lines.push(`成功：${String(item.inputPath)} → ${String(item.name)}（${String(item.slug)}）v${String(item.version)}`);
    }
    for (const item of failed) {
        lines.push(`失败：${String(item.inputPath)} → ${String(item.message)}`);
    }
    return lines.join('\n');
}
