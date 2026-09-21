import { mkdir, rm, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { unzipSync } from 'fflate';
import { download } from '../clients/api-client.js';
import { exposeSkillToAgent, installedAgentTargets, userSkillStoreRoot } from '../platform/agent-paths.js';
export async function installCommand(options) {
    const query = options.version ? `?version=${encodeURIComponent(options.version)}` : '';
    const archive = await download(options.serviceUrl, `/api/skills/${encodeURIComponent(options.slug)}/download${query}`);
    const files = unzipSync(archive);
    const userInstall = !options.target;
    const parent = options.target ? resolve(options.target) : userSkillStoreRoot();
    const root = resolve(parent, options.slug);
    if (userInstall)
        await rm(root, { recursive: true, force: true });
    await mkdir(root, { recursive: true });
    for (const [path, content] of Object.entries(files)) {
        const output = resolve(root, path);
        const safety = relative(root, output);
        if (safety.startsWith('..') || isAbsolute(safety))
            throw new Error('安装包路径不安全');
        await mkdir(dirname(output), { recursive: true });
        await writeFile(output, content);
    }
    const agentLinks = userInstall ? await Promise.all((await installedAgentTargets()).map(async (target) => ({
        ...target,
        mode: await exposeSkillToAgent(root, target, options.slug)
    }))) : [];
    return options.json
        ? JSON.stringify({ ok: true, slug: options.slug, target: root, userInstall, agentLinks, fileCount: Object.keys(files).length })
        : [
            `安装成功：${options.slug}`,
            `目录：${root}`,
            `文件：${Object.keys(files).length} 个`,
            ...(userInstall ? [`Agent 入口：${agentLinks.filter((item) => item.mode !== 'skipped').map((item) => item.path).join('、') || '未发现可用 Agent 目录'}`] : [])
        ].join('\n');
}
