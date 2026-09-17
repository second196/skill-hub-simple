import { atomicWriteFile } from './atomic.js'
import { configPath } from './paths.js'
import { readJson } from './store.js'
import type { ObserverConfig } from './types.js'

const DEFAULT_HOST = '127.0.0.1'
const DEFAULT_PORT = 8080
const DEFAULT_PROTOCOL = 'http'

export function defaultServiceUrl(): string {
  return `${DEFAULT_PROTOCOL}://${DEFAULT_HOST}:${DEFAULT_PORT}`
}

export async function loadConfig(): Promise<ObserverConfig> {
  const parsed = await readJson<Partial<ObserverConfig>>(configPath())
  return normalizeConfig(parsed)
}

export async function saveConfig(input: Partial<ObserverConfig>): Promise<ObserverConfig> {
  const current = await loadConfig()
  const merged = { ...current, ...stripUndefined(input) }
  const next = normalizeConfig(merged)
  await atomicWriteFile(configPath(), `${JSON.stringify(next, null, 2)}\n`)
  return next
}

export function normalizeServiceUrl(value: string | undefined): string {
  return String(value || '').trim().replace(/\/+$/, '')
}

export function resolveServiceUrl(value: string | undefined): string {
  const fromEnv = normalizeServiceUrl(process.env.SKILLHUB_SERVICE_URL)
  if (fromEnv) return fromEnv
  const normalized = normalizeServiceUrl(value)
  if (!normalized) throw new Error('serviceUrl is empty; run skillhub-observer config set --host <ip> --port <port>')
  return normalized
}

export function buildServiceUrl(input: { protocol?: string; host?: string; port?: number }): string {
  const protocol = normalizeProtocol(input.protocol) || DEFAULT_PROTOCOL
  const host = String(input.host || '').trim() || DEFAULT_HOST
  const port = normalizePort(input.port) || DEFAULT_PORT
  return `${protocol}://${host}:${port}`
}

export function parseServiceUrl(value: string): { protocol: string; host: string; port: number } | undefined {
  const raw = normalizeServiceUrl(value)
  if (!raw) return undefined
  try {
    const url = new URL(raw.includes('://') ? raw : `http://${raw}`)
    const port = normalizePort(url.port || (url.protocol === 'https:' ? 443 : 80))
    return {
      protocol: normalizeProtocol(url.protocol.replace(':', '')) || DEFAULT_PROTOCOL,
      host: url.hostname,
      port: port || DEFAULT_PORT
    }
  } catch {
    return undefined
  }
}

export function describeConfig(config: ObserverConfig, path = configPath()): string {
  return [
    `配置文件: ${path}`,
    `serviceUrl: ${config.serviceUrl || '(未设置)'}`,
    `host: ${config.host || '(未设置)'}`,
    `port: ${config.port || '(未设置)'}`,
    `protocol: ${config.protocol || DEFAULT_PROTOCOL}`,
    `drainIntervalSec: ${config.drainIntervalSec}`,
    `bootTask: ${config.bootTask}`,
    `环境变量覆盖: SKILLHUB_SERVICE_URL=${process.env.SKILLHUB_SERVICE_URL || '(未设置)'}`
  ].join('\n')
}

function normalizeConfig(value: Partial<ObserverConfig> | undefined): ObserverConfig {
  const drainIntervalSec = Number(value?.drainIntervalSec)
  const fromUrl = parseServiceUrl(value?.serviceUrl || '')
  const host = String(value?.host || '').trim() || fromUrl?.host || ''
  const port = normalizePort(value?.port) || fromUrl?.port || 0
  const protocol = normalizeProtocol(value?.protocol) || fromUrl?.protocol || DEFAULT_PROTOCOL
  // host/port 优先，保证 config-set --host/--port 能覆盖旧 serviceUrl
  const serviceUrl = host && port
    ? buildServiceUrl({ protocol, host, port })
    : normalizeServiceUrl(value?.serviceUrl)
  return {
    serviceUrl,
    host,
    port,
    protocol,
    drainIntervalSec: Number.isFinite(drainIntervalSec) && drainIntervalSec > 0 ? drainIntervalSec : 300,
    bootTask: value?.bootTask !== false
  }
}

function normalizePort(value: unknown): number {
  const port = Number(value)
  return Number.isInteger(port) && port > 0 && port <= 65535 ? port : 0
}

function normalizeProtocol(value: unknown): string {
  const protocol = String(value || '').trim().toLowerCase().replace(/:$/, '')
  return protocol === 'http' || protocol === 'https' ? protocol : ''
}

function stripUndefined(input: Partial<ObserverConfig>): Partial<ObserverConfig> {
  const next: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(input)) {
    if (value !== undefined) next[key] = value
  }
  return next as Partial<ObserverConfig>
}
