import { EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'
import { CredentialsStore } from '../stores/credentials-store.js'

export async function resolveLoginToken(explicitToken?: string): Promise<string> {
  const token = explicitToken ?? process.env.SKILLHUB_TOKEN
  if (token !== undefined && token.trim().length > 0) return token.trim()
  return readHiddenToken()
}

export async function resolveStoredToken(serviceUrl: string): Promise<string> {
  const token = process.env.SKILLHUB_TOKEN ?? await new CredentialsStore().getToken(serviceUrl)
  if (token === undefined || token.trim().length === 0) {
    throw new CliError('当前服务尚未登录', 'NOT_LOGGED_IN', EXIT_CODE.authentication, { serviceUrl })
  }
  return token.trim()
}

async function readHiddenToken(): Promise<string> {
  if (!process.stdin.isTTY || typeof process.stdin.setRawMode !== 'function') {
    throw new CliError(
      '非交互环境请通过 SKILLHUB_TOKEN 提供访问凭证',
      'TOKEN_REQUIRED',
      EXIT_CODE.usage
    )
  }

  process.stderr.write('请输入访问凭证：')
  const previousRawMode = process.stdin.isRaw
  process.stdin.setRawMode(true)
  process.stdin.resume()
  return new Promise<string>((resolve, reject) => {
    let value = ''
    const cleanup = (): void => {
      process.stdin.off('data', handleData)
      process.stdin.off('error', handleError)
      process.stdin.setRawMode?.(Boolean(previousRawMode))
      process.stdin.pause()
    }
    const complete = (): void => {
      cleanup()
      process.stderr.write('\n')
      if (value.trim().length === 0) {
        reject(new CliError('访问凭证不能为空', 'TOKEN_REQUIRED', EXIT_CODE.usage))
      } else {
        resolve(value.trim())
      }
    }
    const handleError = (): void => {
      cleanup()
      process.stderr.write('\n')
      reject(new CliError('读取访问凭证失败', 'TOKEN_INPUT_FAILED', EXIT_CODE.generic))
    }
    const handleData = (chunk: Buffer | string): void => {
      for (const character of chunk.toString()) {
        if (character === '\u0003') {
          cleanup()
          process.stderr.write('\n')
          reject(new CliError('已取消登录', 'LOGIN_CANCELLED', EXIT_CODE.usage))
          return
        }
        if (character === '\r' || character === '\n') {
          complete()
          return
        }
        if (character === '\u007f' || character === '\b') {
          value = value.slice(0, -1)
        } else if (character >= ' ') {
          value += character
        }
      }
    }
    process.stdin.on('data', handleData)
    process.stdin.on('error', handleError)
  })
}
