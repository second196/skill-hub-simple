#!/usr/bin/env node
import { cac } from 'cac'
import { uploadCommand, prepareCommand, checkCommand } from './commands/upload.js'
import { listCommand } from './commands/list.js'
import { installCommand } from './commands/install.js'
import { formatError } from './shared/output.js'
import { VERSION_GATE_ERROR_CODES } from './shared/constants.js'

const cli = cac('skillhub')
cli.command('upload <input-path...>', '上传一个或多个 ZIP、目录或 SKILL.md（version 必须已在包内声明）')
  .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
  .option('--category <category>', 'Skill分类（包内 category 优先；平台已有 skill 会继承原分类）')
  .option('--name <name>', '复合Skill包名称（覆盖自动生成值）')
  .option('--description <description>', '复合Skill包描述（覆盖自动生成值）')
  .option('--json', '输出 JSON')
  .option('--no-check', '跳过上传前本地包完整性检查')
  .action(async (inputPaths, options) => run(() => uploadCommand({
    inputPaths: Array.isArray(inputPaths) ? inputPaths : [inputPaths],
    serviceUrl: options.serviceUrl,
    category: options.category,
    name: options.name,
    description: options.description,
    json: Boolean(options.json),
    check: options.check !== false
  }), Boolean(options.json)))

cli.command('check <input-path>', '检查 Skill 包内元数据是否完整（不发起上传）')
  .option('--json', '输出 JSON')
  .action(async (inputPath, options) => run(() => checkCommand({
    inputPath,
    json: Boolean(options.json)
  }), Boolean(options.json)))

cli.command('prepare <input-path>', '临时目录校验后，用完整包覆盖技能源目录（不注入 version）')
  .option('--json', '输出 JSON')
  .action(async (inputPath, options) => run(() => prepareCommand({
    inputPath,
    json: Boolean(options.json)
  }), Boolean(options.json)))

cli.command('list', '查询当前可安装的Skill')
  .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
  .option('--category <category>', '按分类筛选')
  .option('--json', '输出 JSON（数组；字段含 name/slug/category/version_label/status）')
  .action(async (options) => run(() => listCommand({
    serviceUrl: options.serviceUrl,
    category: options.category,
    json: Boolean(options.json)
  }), Boolean(options.json)))

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
  }), Boolean(options.json)))

cli.command('version-codes', '导出版本门禁错误码')
  .action(async () => run(async () => JSON.stringify(VERSION_GATE_ERROR_CODES, null, 2), true))

cli.help()
cli.version('0.4.0')
cli.parse()

async function run(action: () => Promise<string>, json: boolean): Promise<void> {
  try {
    process.stdout.write(`${await action()}\n`)
  } catch (error: unknown) {
    process.stderr.write(`${formatError(error, json)}\n`)
    process.exitCode = 1
  }
}
