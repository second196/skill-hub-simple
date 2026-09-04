import { spawn } from 'node:child_process'
import { EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'

export interface ProcessProbeDefinition {
  key: string
  executable: string
  args: readonly string[]
  timeoutMilliseconds?: number
}

export interface ProcessProbeResult {
  exitCode: number | null
  stdout: string
  stderr: string
}

/** 仅运行构造时注入的固定探测定义，调用方不能传入可执行文件或参数。 */
export class ProcessProbeRegistry {
  private readonly definitions = new Map<string, ProcessProbeDefinition>()

  constructor(definitions: readonly ProcessProbeDefinition[]) {
    for (const definition of definitions) {
      if (this.definitions.has(definition.key)) {
        throw new CliError('进程探测定义重复', 'DUPLICATE_PROCESS_PROBE', EXIT_CODE.validation)
      }
      this.definitions.set(definition.key, definition)
    }
  }

  async run(key: string): Promise<ProcessProbeResult> {
    const definition = this.definitions.get(key)
    if (definition === undefined) {
      throw new CliError('进程探测不受支持', 'UNSUPPORTED_PROCESS_PROBE', EXIT_CODE.validation)
    }
    return new Promise((resolve, reject) => {
      const child = spawn(definition.executable, [...definition.args], {
        shell: false,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: definition.timeoutMilliseconds ?? 5000
      })
      let stdout = ''
      let stderr = ''
      child.stdout.on('data', (value: Buffer) => { stdout = appendLimited(stdout, value) })
      child.stderr.on('data', (value: Buffer) => { stderr = appendLimited(stderr, value) })
      child.on('error', (error) => reject(new CliError(
        `无法执行运行时探测：${error.message}`, 'PROCESS_PROBE_FAILED', EXIT_CODE.validation)))
      child.on('close', (exitCode) => resolve({ exitCode, stdout, stderr }))
    })
  }
}

function appendLimited(current: string, value: Buffer): string {
  return `${current}${value.toString('utf8')}`.slice(0, 32 * 1024)
}
