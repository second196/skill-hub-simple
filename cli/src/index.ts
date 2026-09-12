#!/usr/bin/env node
import { cac } from 'cac'
import { uploadCommand } from './commands/upload.js'
import { listCommand } from './commands/list.js'
import { installCommand } from './commands/install.js'
import { formatError } from './shared/output.js'

const cli = cac('skillhub')
cli.command('upload <input-path..>', '上传一个或多个 ZIP、目录或 SKILL.md')
  .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
  .option('--category <category>', '技能分类', { default: '其他' })
  .option('--name <name>', '复合技能包名称（覆盖自动生成值）')
  .option('--description <description>', '复合技能包描述（覆盖自动生成值）')
  .option('--json', '输出 JSON')
  .action(async (inputPaths, options) => run(() => uploadCommand({
    inputPaths: Array.isArray(inputPaths) ? inputPaths : [inputPaths],
    serviceUrl: options.serviceUrl,
    category: options.category,
    name: options.name,
    description: options.description,
    json: Boolean(options.json)
  }), Boolean(options.json)))
cli.command('list', '查询平台中的全部技能')
  .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
  .option('--category <category>', '按分类筛选')
  .option('--json', '输出 JSON')
  .action(async (options) => run(() => listCommand({ serviceUrl: options.serviceUrl, category: options.category, json: Boolean(options.json) }), Boolean(options.json)))
cli.command('install <slug>', '下载并安装技能到本地目录')
  .option('--service-url <url>', 'Skill Hub 服务地址', { default: 'http://127.0.0.1:8080' })
  .option('--target <directory>', '安装目录（指定后覆盖用户级安装）')
  .option('--version <digest>', '指定版本摘要')
  .option('--json', '输出 JSON')
  .action(async (slug, options) => run(() => installCommand({ slug, serviceUrl: options.serviceUrl, target: options.target, version: options.version, json: Boolean(options.json) }), Boolean(options.json)))
cli.help(); cli.version('0.2.0'); cli.parse()

async function run(action: () => Promise<string>, json: boolean): Promise<void> {
  try { process.stdout.write(`${await action()}\n`) } catch (error: unknown) { process.stderr.write(`${formatError(error, json)}\n`); process.exitCode = 1 }
}
