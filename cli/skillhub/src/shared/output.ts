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
  }
  if (details.suggestedNextVersion) {
    lines.push('', `建议下一版本：${details.suggestedNextVersion}`)
  }
  if (details.maxFormal) {
    lines.push(`平台当前最新正式版：${details.maxFormal}`)
  }
  const example = details.example
  if (example?.packageJson) {
    lines.push('', '请在包根 package.json 补全 version（复合包不要创建根 SKILL.md）：')
    lines.push(JSON.stringify(example.packageJson, null, 2))
  }
  if (example?.skillMd) {
    lines.push('', '或在已有根 SKILL.md 的 frontmatter 中声明 version（单包）：')
    lines.push(example.skillMd)
  }
  if (details.hint) {
    lines.push('', details.hint)
  }
  lines.push('', '官方补全流程（源目录不完整时）：')
  lines.push('  1. 创建临时目录，完整复制 Skill')
  lines.push('  2. 在临时目录补全包内 version / category')
  lines.push('  3. 将临时目录完整内容覆盖回技能源目录')
  lines.push('  4. 删除临时目录')
  lines.push('  5. skillhub upload <skill-dir> --json')
  return lines.join('\n')
}
