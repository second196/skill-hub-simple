import { CliError } from '../shared/errors.js'
import { EXIT_CODE } from '../shared/constants.js'

export function serviceUrl(value: string): string { return value.replace(/\/+$/, '') }
export async function apiRequest<T>(base: string, path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try { response = await fetch(`${serviceUrl(base)}${path}`, init) }
  catch (_error) { throw new CliError('无法连接 Skill Hub 服务', 'SERVICE_UNREACHABLE', EXIT_CODE.network) }
  if (!response.ok) { const body = await response.json().catch(() => ({})) as {message?:string}; throw new CliError(body.message || `服务返回 ${response.status}`, `HTTP_${response.status}`, response.status >= 500 ? EXIT_CODE.network : EXIT_CODE.generic) }
  return await response.json() as T
}
export async function download(base: string, path: string): Promise<Uint8Array> {
  let response: Response
  try { response = await fetch(`${serviceUrl(base)}${path}`) } catch (_error) { throw new CliError('无法连接 Skill Hub 服务', 'SERVICE_UNREACHABLE', EXIT_CODE.network) }
  if (!response.ok) throw new CliError(`下载失败（${response.status}）`, `HTTP_${response.status}`, response.status >= 500 ? EXIT_CODE.network : EXIT_CODE.generic)
  return new Uint8Array(await response.arrayBuffer())
}
