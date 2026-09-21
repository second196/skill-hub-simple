#!/usr/bin/env node
import { cac } from 'cac';
import { uploadCommand, prepareCommand, checkCommand, verifySourceCommand } from './commands/upload.js';
import { listCommand } from './commands/list.js';
import { installCommand } from './commands/install.js';
import { formatError } from './shared/output.js';
import { VERSION_GATE_ERROR_CODES } from './shared/constants.js';
const cli = cac('skillhub');
cli.command('upload <input-path...>', '上传 Skill（version/category 必须已在包内；成功=平台核验+源目录完整）')
    .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
    .option('--category <category>', 'Skill分类（包内 category 优先；平台已有 skill 会继承原分类）')
    .option('--name <name>', '复合Skill包名称（覆盖自动生成值）')
    .option('--description <description>', '复合Skill包描述（覆盖自动生成值）')
    .option('--source-dir <dir>', '技能源目录（上传后必须仍通过 verify-source；默认与 input-path 相同）')
    .option('--json', '输出 JSON')
    .option('--no-check', '跳过上传前本地包完整性检查')
    .action(async (inputPaths, options) => run(() => uploadCommand({
    inputPaths: Array.isArray(inputPaths) ? inputPaths : [inputPaths],
    serviceUrl: options.serviceUrl,
    category: options.category,
    name: options.name,
    description: options.description,
    sourceDir: options.sourceDir,
    json: Boolean(options.json),
    check: options.check !== false
}), Boolean(options.json)));
cli.command('check <input-path>', '检查 Skill 源目录元数据是否完整（不发起上传）')
    .option('--json', '输出 JSON')
    .action(async (inputPath, options) => run(() => checkCommand({
    inputPath,
    json: Boolean(options.json)
}), Boolean(options.json)));
cli.command('verify-source <source-path>', '核验技能源目录磁盘上的元数据是否完整（write-back 门禁）')
    .option('--json', '输出 JSON')
    .action(async (sourcePath, options) => run(() => verifySourceCommand({
    sourcePath,
    json: Boolean(options.json)
}), Boolean(options.json)));
cli.command('prepare <source-path>', '校验并写回技能源目录；可用 --complete-from 从临时目录覆盖回源')
    .option('--complete-from <temp-dir>', '完整临时包路径：覆盖到源目录后删除临时目录')
    .option('--json', '输出 JSON')
    .action(async (sourcePath, options) => run(() => prepareCommand({
    inputPath: sourcePath,
    completeFrom: options.completeFrom,
    json: Boolean(options.json)
}), Boolean(options.json)));
cli.command('list', '查询当前可安装的Skill')
    .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
    .option('--category <category>', '按分类筛选')
    .option('--json', '输出 JSON（数组；字段含 name/slug/category/version_label/status）')
    .action(async (options) => run(() => listCommand({
    serviceUrl: options.serviceUrl,
    category: options.category,
    json: Boolean(options.json)
}), Boolean(options.json)));
cli.command('install <slug>', '下载并安装Skill到本地目录')
    .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
    .option('--target <directory>', '安装目录（指定后覆盖用户级安装）')
    .option('--skill-version <semver>', '安装指定版本（包内 SemVer version_label；不要用 --version）')
    .option('--json', '输出 JSON')
    .action(async (slug, options) => run(() => installCommand({
    slug,
    serviceUrl: options.serviceUrl,
    target: options.target,
    skillVersion: options.skillVersion,
    json: Boolean(options.json)
}), Boolean(options.json)));
cli.command('version-codes', '导出版本门禁错误码')
    .action(async () => run(async () => JSON.stringify(VERSION_GATE_ERROR_CODES, null, 2), true));
cli.help();
cli.version('0.4.1');
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
