import { CliError } from './errors.js'

export interface VersionExamplePayload {
  packageJson?: Record<string, unknown>
  skillMd?: string
  cli?: string
}

export function formatError(error: unknown, json: boolean): string {
  const cliError = error instanceof CliError
    ? error
    : new CliError(error instanceof Error ? error.message : '未知错误', 'UNEXPECTED_ERROR', 1)
  if (json) {
    return JSON.stringify({ ok: false, code: cliError.code, message: cliError.message, ...cliError.details })
  }
  const lines = [`错误：${cliError.message}`]
  const details = (cliError.details || {}) as {
    hint?: string
    example?: VersionExamplePayload
    suggestedNextVersion?: string
    maxFormal?: string
    prepareFlow?: string[]
    completeFrom?: string
  }
  if (details.suggestedNextVersion) {
    lines.push('', `建议下一版本：${details.suggestedNextVersion}`)
  }
  if (details.maxFormal) {
    lines.push(`平台当前最新正式版：${details.maxFormal}`)
  }
  const example = details.example
  if (example?.packageJson) {
    lines.push('', '请在包根 package.json 补全 version/category（复合包不要创建根 SKILL.md）：')
    lines.push(JSON.stringify(example.packageJson, null, 2))
  }
  if (example?.skillMd) {
    lines.push('', '或在已有根 SKILL.md 的 frontmatter 中声明 version/category（单包）：')
    lines.push(example.skillMd)
  }
  if (details.hint) {
    lines.push('', details.hint)
  }
  if (details.prepareFlow?.length) {
    lines.push('')
    for (const step of details.prepareFlow) lines.push(step)
  } else {
    lines.push('', '硬性要求：临时目录补全后必须 write-back 到技能源目录，再 verify-source + upload 源目录。')
  }
  return lines.join('\n')
}
