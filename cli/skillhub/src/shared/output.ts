import { CliError } from './errors.js'

export function formatError(error: unknown, json: boolean): string {
  const cliError = error instanceof CliError
    ? error
    : new CliError(error instanceof Error ? error.message : '未知错误', 'UNEXPECTED_ERROR', 1)
  return json
    ? JSON.stringify({ ok: false, code: cliError.code, message: cliError.message, ...cliError.details })
    : `错误：${cliError.message}`
}
