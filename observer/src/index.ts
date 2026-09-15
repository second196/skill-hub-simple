#!/usr/bin/env node
import { cac } from 'cac'
import { installHooks } from './install.js'
import { runHook } from './hook.js'
import { writeReport } from './report.js'
import { uploadObservations } from './upload.js'

const cli = cac('skillhub-observer')
cli.command('install', '安装 Claude Code / Codex 采集 hooks')
  .action(async () => run(() => installHooks(), false))

cli.command('report', '扫描本地会话并生成完整 HTML 报告')
  .option('--open', '生成后打开报告')
  .option('-o, --output <file>', '报告输出路径')
  .option('--service-url <url>', '对照平台Skill目录，标记是否已收录')
  .action(async (options) => run(async () => {
    const output = await writeReport({
      output: options.output,
      open: Boolean(options.open),
      serviceUrl: options.serviceUrl
    })
    return `已生成报告：${output}`
  }, false))

cli.command('upload', '上传本机全部会话观测内容')
  .option('--service-url <url>', 'SkillHub 服务地址', { default: 'http://127.0.0.1:8080' })
  .action(async (options) => run(() => uploadObservations(String(options.serviceUrl)), false))

cli.command('hook', '内部 hook 入口，不要手动调用')
  .option('--phase <phase>', 'pre | post | stop | session-end')
  .option('--provider <provider>', 'claude-code | codex')
  .action(async (options) => {
    await runHook(String(options.phase || ''), String(options.provider || ''))
  })

cli.help()
cli.version('0.1.0')
cli.parse()

async function run(action: () => Promise<string>, _json: boolean): Promise<void> {
  try {
    process.stdout.write(`${await action()}\n`)
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error)
    process.stderr.write(`错误：${message}\n`)
    process.exitCode = 1
  }
}
