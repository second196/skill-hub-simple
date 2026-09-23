#!/usr/bin/env node
import { cac } from 'cac';
import { installHooks } from './install.js';
import { uninstallObserver } from './uninstall.js';
import { runHook } from './hook.js';
import { writeReport } from './report.js';
import { uploadObservations } from './upload.js';
import { drainQueue } from './drain.js';
import { buildServiceUrl, describeConfig, loadConfig, normalizeServiceUrl, parseServiceUrl, saveConfig } from './config.js';
import { configPath } from './paths.js';
import { ensureStore } from './store.js';
const cli = cac('skillhub-observer');
cli.command('install', '安装 Claude Code / Codex 采集 hooks，并注册开机补传任务')
    .option('--service-url <url>', 'SkillHub 服务地址', { default: 'http://127.0.0.1:8080' })
    .option('--host <host>', '后端 IP/主机名（与 --port 一起用）')
    .option('--port <port>', '后端端口')
    .action(async (options) => run(async () => {
    const host = options.host ? String(options.host) : undefined;
    const port = options.port !== undefined && options.port !== '' ? Number(options.port) : undefined;
    const serviceUrl = resolveInstallUrl(options.serviceUrl, host, port);
    return installHooks({ serviceUrl });
}, false));
cli.command('uninstall', '一键卸载 Observer 本机痕迹（hooks、计划任务、数据；可选卸载 npm 包）')
    .option('--keep-data', '保留本机数据目录（config/spool/logs/sessions）')
    .option('--keep-codex-features', '保留 Codex [features].hooks 开关')
    .option('--purge-packages', '同时执行 npm uninstall -g 两个 SkillHub CLI 包')
    .action(async (options) => run(async () => uninstallObserver({
    keepData: Boolean(options.keepData),
    keepCodexFeatures: Boolean(options.keepCodexFeatures),
    packages: Boolean(options.purgePackages)
}), false));
cli.command('config', '查看或修改本机 Observer 配置（IP/端口/服务地址）')
    .action(async () => run(async () => {
    await ensureStore();
    return describeConfig(await loadConfig());
}, false));
cli.command('config-set', '更新后端地址，无需重装 hooks')
    .option('--service-url <url>', '完整服务地址，例如 http://192.168.1.10:8080')
    .option('--host <host>', '后端 IP 或主机名')
    .option('--port <port>', '后端端口')
    .option('--protocol <protocol>', 'http 或 https')
    .option('--drain-interval-sec <sec>', '定时 drain 间隔秒数')
    .action(async (options) => run(async () => {
    await ensureStore();
    const patch = {};
    const serviceUrl = normalizeServiceUrl(options.serviceUrl);
    if (serviceUrl) {
        patch.serviceUrl = serviceUrl;
        const parsed = parseServiceUrl(serviceUrl);
        if (parsed) {
            patch.host = parsed.host;
            patch.port = parsed.port;
            patch.protocol = parsed.protocol;
        }
    }
    if (options.host !== undefined)
        patch.host = String(options.host);
    if (options.port !== undefined && options.port !== '')
        patch.port = Number(options.port);
    if (options.protocol !== undefined)
        patch.protocol = String(options.protocol);
    if (options.drainIntervalSec !== undefined && options.drainIntervalSec !== '') {
        patch.drainIntervalSec = Number(options.drainIntervalSec);
    }
    if (patch.serviceUrl === undefined && (patch.host !== undefined || patch.port !== undefined || patch.protocol !== undefined)) {
        const current = await loadConfig();
        patch.serviceUrl = buildServiceUrl({
            protocol: patch.protocol || current.protocol,
            host: patch.host || current.host,
            port: patch.port || current.port
        });
    }
    if (!Object.keys(patch).length) {
        throw new Error('请至少提供 --service-url 或 --host/--port');
    }
    const saved = await saveConfig(patch);
    return `已更新配置\n${describeConfig(saved)}\n下次 drain/upload 将自动使用新地址，无需重新 install。`;
}, false));
cli.command('config-path', '打印配置文件路径')
    .action(async () => run(async () => {
    await ensureStore();
    return configPath();
}, false));
cli.command('report', '扫描本地会话并生成完整 HTML 报告')
    .option('--open', '生成后打开报告')
    .option('-o, --output <file>', '报告输出路径')
    .option('--service-url <url>', '对照平台Skill目录，标记是否已收录')
    .action(async (options) => run(async () => {
    const output = await writeReport({
        output: options.output,
        open: Boolean(options.open),
        serviceUrl: options.serviceUrl
    });
    return `已生成报告：${output}`;
}, false));
cli.command('upload', '全量扫描源 jsonl 并入队上传，作为对账兜底')
    .option('--service-url <url>', 'SkillHub 服务地址')
    .action(async (options) => run(() => uploadObservations(options.serviceUrl ? String(options.serviceUrl) : undefined), false));
cli.command('drain', '处理本地待上传队列')
    .option('--service-url <url>', 'SkillHub 服务地址')
    .option('--reconcile', '补扫尚未 ACK 或内容有更新的源 jsonl')
    .action(async (options) => run(() => drainQueue({
    serviceUrl: options.serviceUrl ? String(options.serviceUrl) : undefined,
    reconcile: Boolean(options.reconcile)
}), false));
cli.command('hook', '内部 hook 入口，不要手动调用')
    .option('--phase <phase>', 'pre | post | stop | session-end | session-start')
    .option('--provider <provider>', 'claude-code | codex')
    .action(async (options) => {
    await runHook(String(options.phase || ''), String(options.provider || ''));
});
cli.help();
cli.version('0.5.0');
cli.parse();
function resolveInstallUrl(serviceUrl, host, port) {
    const raw = normalizeServiceUrl(String(serviceUrl || ''));
    if (host || port) {
        const parsed = parseServiceUrl(raw) || { protocol: 'http', host: '127.0.0.1', port: 8080 };
        return buildServiceUrl({
            protocol: parsed.protocol,
            host: host || parsed.host,
            port: port || parsed.port
        });
    }
    return raw;
}
async function run(action, _json) {
    try {
        process.stdout.write(`${await action()}\n`);
    }
    catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        process.stderr.write(`错误：${message}\n`);
        process.exitCode = 1;
    }
}
