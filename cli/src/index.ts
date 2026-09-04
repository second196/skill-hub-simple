#!/usr/bin/env node
import { cac } from 'cac'
import { loginCommand } from './commands/login.js'
import { logoutCommand } from './commands/logout.js'
import { whoamiCommand } from './commands/whoami.js'
import { publishCommand } from './commands/publish.js'
import { telemetryCommand } from './commands/telemetry.js'
import { formatError } from './shared/output.js'
import { CliError } from './shared/errors.js'
import { homedir } from 'node:os'

const cli = cac('skillhub')

cli.command('login', '保存并验证 SkillHub 访问凭证')
  .option('--service-url <url>', 'SkillHub 服务地址')
  .option('--json', '输出 JSON')
  .action(async (options) => run(() => loginCommand(options), Boolean(options.json)))

cli.command('whoami', '查看当前访问凭证对应账户')
  .option('--service-url <url>', 'SkillHub 服务地址')
  .option('--json', '输出 JSON')
  .action(async (options) => run(() => whoamiCommand(options), Boolean(options.json)))

cli.command('logout', '删除当前服务的本地访问凭证')
  .option('--service-url <url>', 'SkillHub 服务地址')
  .option('--json', '输出 JSON')
  .action(async (options) => run(() => logoutCommand(options), Boolean(options.json)))

cli.command('publish <input-path>', '校验并上传 Skill 目录或 ZIP')
  .option('--service-url <url>', 'SkillHub 服务地址')
  .option('--scope-id <id>', '归属范围编号')
  .option('--request-id <id>', '上传请求标识')
  .option('--submit-review', '上传成功后提交审核')
  .option('--comment <text>', '审核申请说明')
  .option('--dry-run', '仅执行本地校验')
  .option('--json', '输出 JSON')
  .action(async (inputPath, options) => run(() => publishCommand({
    inputPath,
    serviceUrl: options.serviceUrl,
    scopeId: options.scopeId === undefined ? undefined : Number(options.scopeId),
    requestId: options.requestId,
    submitReview: Boolean(options.submitReview),
    comment: options.comment,
    dryRun: Boolean(options.dryRun),
    json: Boolean(options.json)
  }), Boolean(options.json)))

cli.command('telemetry <action>', '安装、诊断、修复、补报或管理本地采集器')
  .option('--runtime <runtime>', '目标运行时')
  .option('--service-url <url>', 'SkillHub 服务地址')
  .option('--scope-id <id>', '归属范围编号')
  .option('--dry-run', '仅展示计划，不修改配置')
  .option('--json', '输出 JSON')
  .action(async (action, options) => run(() => telemetryCommand({
    action,
    runtime: options.runtime ?? '',
    home: homedir(),
    serviceUrl: options.serviceUrl,
    scopeId: options.scopeId === undefined ? undefined : Number(options.scopeId),
    dryRun: Boolean(options.dryRun),
    json: Boolean(options.json)
  }), Boolean(options.json)))

cli.help((sections) => sections.map((section) => ({
  title: translateHelpTitle(section.title),
  body: section.body
    .replaceAll('Display this message', '显示帮助信息')
    .replaceAll('Display version number', '显示版本号')
    .replaceAll('(default:', '(默认值：')
})))
cli.version('0.1.0')
cli.parse()

function translateHelpTitle(title: string | undefined): string | undefined {
  if (title === 'Usage') return '用法'
  if (title === 'Commands') return '命令'
  if (title === 'Options') return '选项'
  if (title === 'Examples') return '示例'
  if (title?.startsWith('For more info')) return '查看命令详情时可添加 `--help` 参数'
  return title
}

async function run(action: () => Promise<string>, json: boolean): Promise<void> {
  try {
    process.stdout.write(`${await action()}\n`)
  } catch (error: unknown) {
    process.stderr.write(`${formatError(error, json)}\n`)
    process.exitCode = error instanceof CliError ? error.exitCode : 1
  }
}
