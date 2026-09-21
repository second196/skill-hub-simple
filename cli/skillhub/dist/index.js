#!/usr/bin/env node
import { cac } from 'cac';
import { uploadCommand } from './commands/upload.js';
import { listCommand } from './commands/list.js';
import { installCommand } from './commands/install.js';
import { formatError } from './shared/output.js';
import { VERSION_GATE_ERROR_CODES } from './shared/constants.js';
const cli = cac('skillhub');
cli.command('upload <input-path..>', '上传一个或多个 ZIP、目录或 SKILL.md')
    .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
    .option('--category <category>', 'Skill分类', { default: '其他' })
    .option('--name <name>', '复合Skill包名称（覆盖自动生成值）')
    .option('--description <description>', '复合Skill包描述（覆盖自动生成值）')
    // Do NOT name this --version: cac registers global -v/--version for CLI version display.
    .option('--skill-version <semver>', '技能包 SemVer（复合包无根 SKILL.md 时必填，或读包根 package.json；不要用 --version）')
    .option('--json', '输出 JSON')
    .action(async (inputPaths, options) => run(() => uploadCommand({
    inputPaths: Array.isArray(inputPaths) ? inputPaths : [inputPaths],
    serviceUrl: options.serviceUrl,
    category: options.category,
    name: options.name,
    description: options.description,
    version: options.skillVersion,
    json: Boolean(options.json)
}), Boolean(options.json)));
cli.command('list', '查询当前可安装的Skill')
    .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
    .option('--category <category>', '按分类筛选')
    .option('--json', '输出 JSON')
    .action(async (options) => run(() => listCommand({ serviceUrl: options.serviceUrl, category: options.category, json: Boolean(options.json) }), Boolean(options.json)));
cli.command('install <slug>', '下载并安装Skill到本地目录')
    .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
    .option('--target <directory>', '安装目录（指定后覆盖用户级安装）')
    .option('--digest <digest>', '指定版本摘要（platform version_digest；不要用 --version）')
    .option('--json', '输出 JSON')
    .action(async (slug, options) => run(() => installCommand({ slug, serviceUrl: options.serviceUrl, target: options.target, version: options.digest, json: Boolean(options.json) }), Boolean(options.json)));
cli.command('version-codes', '导出版本门禁错误码')
    .action(async () => run(async () => JSON.stringify(VERSION_GATE_ERROR_CODES, null, 2), true));
cli.help();
cli.version('0.3.0');
cli.parse();
async function run(action, json) {
    try {
        process.stdout.write(`${await action()}\n`);
    }
    catch (error) {
        process.stderr.write(`${formatError(error, json)}\n`);
        process.exitCode = 1;
    }
}
