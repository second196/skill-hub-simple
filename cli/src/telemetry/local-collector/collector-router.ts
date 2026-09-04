import { randomUUID } from 'node:crypto'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { RuntimeKey } from '../../adapters/types.js'
import type {
  CollectorRegistry,
  RuntimeCollectorSource,
  RuntimeEventSpool
} from '../collectors/collector.js'
import { COLLECTOR_RUNTIME_HEADER, COLLECTOR_SECRET_HEADER, collectorSecretMatches } from './collector-auth.js'
import { COLLECTOR_PROTOCOL_VERSION, type LocalCollectorConfig } from './collector-config-store.js'

const otlpRuntimeKeys = new Set<RuntimeKey>(['codex-cli', 'claude-code-otlp'])
const editorRuntimeKeys = new Set<RuntimeKey>(['vscode', 'cursor', 'windsurf'])

export interface CollectorRouterOptions {
  config: LocalCollectorConfig
  secret: string
  instanceId: string
  registry: CollectorRegistry
  spoolFactory: (runtimeKey: RuntimeKey, spoolPartition: string) => RuntimeEventSpool
  now?: () => Date
}

/** 校验并路由本机 Hook、OTLP 和编辑器事件，不记录请求正文。 */
export class CollectorRouter {
  constructor(private readonly options: CollectorRouterOptions) {}

  async handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const requestId = randomUUID()
    try {
      const path = new URL(request.url ?? '/', 'http://127.0.0.1').pathname
      if (request.method === 'GET' && path === '/health') {
        const supplied = headerValue(request, COLLECTOR_SECRET_HEADER)
        sendJson(response, 200, {
          status: 'HEALTHY',
          protocolVersion: COLLECTOR_PROTOCOL_VERSION,
          instanceId: this.options.instanceId,
          authenticated: collectorSecretMatches(this.options.secret, supplied)
        }, requestId)
        return
      }
      if (request.method !== 'POST') {
        sendError(response, 404, 'COLLECTOR_ROUTE_NOT_FOUND', requestId)
        return
      }
      if (!collectorSecretMatches(this.options.secret, headerValue(request, COLLECTOR_SECRET_HEADER))) {
        sendError(response, 401, 'COLLECTOR_AUTHENTICATION_FAILED', requestId)
        return
      }
      if (!isJsonContentType(headerValue(request, 'content-type'))) {
        sendError(response, 415, 'COLLECTOR_CONTENT_TYPE_UNSUPPORTED', requestId)
        return
      }
      const route = routeFor(path, request)
      if (route === undefined) {
        sendError(response, 404, 'COLLECTOR_ROUTE_NOT_FOUND', requestId)
        return
      }
      if ('errorCode' in route) {
        sendError(response, 422, route.errorCode, requestId)
        return
      }
      const context = this.options.config.runtimes[route.runtimeKey]
      if (context === undefined) {
        sendError(response, 422, 'COLLECTOR_CONTEXT_UNAVAILABLE', requestId)
        return
      }
      const body = await readJsonBody(request, this.options.config.maxBodyBytes)
      const inputs = path === '/ide-event' && Array.isArray(body) ? body : [body]
      let collected = 0
      for (const input of inputs) {
        const result = await this.options.registry.capture({
          runtimeKey: route.runtimeKey,
          source: route.source,
          runtimeVersion: context.runtimeVersion,
          scopeId: context.scopeId,
          trackerVersion: context.trackerVersion,
          receivedAt: (this.options.now ?? (() => new Date()))(),
          input,
          spool: this.options.spoolFactory(route.runtimeKey, context.spoolPartition)
        })
        if (result.status === 'FAILED') {
          sendError(response, result.errorCode === 'SPOOL_WRITE_FAILED' ? 503 : 422,
            result.errorCode ?? 'COLLECTOR_FAILED', requestId)
          return
        }
        if (result.status === 'UNAVAILABLE') {
          sendError(response, 422, result.errorCode ?? 'COLLECTOR_UNAVAILABLE', requestId)
          return
        }
        collected += result.collected
      }
      sendJson(response, 202, { status: collected === 0 ? 'IGNORED' : 'COLLECTED', collected }, requestId)
    } catch (error: unknown) {
      if (error instanceof RequestBodyError) {
        sendError(response, error.status, error.code, requestId, error.closeConnection)
        return
      }
      sendError(response, 500, 'COLLECTOR_INTERNAL_ERROR', requestId)
    }
  }
}

type CollectorRoute = { runtimeKey: RuntimeKey; source: RuntimeCollectorSource } | { errorCode: string }

function routeFor(path: string, request: IncomingMessage): CollectorRoute | undefined {
  if (path === '/hook') return { runtimeKey: 'codex-cli', source: 'hook' }
  const runtimeKey = headerValue(request, COLLECTOR_RUNTIME_HEADER) as RuntimeKey | undefined
  if (path === '/v1/logs') {
    return runtimeKey !== undefined && otlpRuntimeKeys.has(runtimeKey)
      ? { runtimeKey, source: 'otel-log' }
      : { errorCode: 'COLLECTOR_SOURCE_UNSUPPORTED' }
  }
  if (path === '/ide-event') {
    return runtimeKey !== undefined && editorRuntimeKeys.has(runtimeKey)
      ? { runtimeKey, source: 'editor-extension' }
      : { errorCode: 'COLLECTOR_SOURCE_UNSUPPORTED' }
  }
  return undefined
}

async function readJsonBody(request: IncomingMessage, maxBytes: number): Promise<unknown> {
  const contentLength = headerValue(request, 'content-length')
  if (contentLength !== undefined) {
    const parsed = Number(contentLength)
    if (!Number.isSafeInteger(parsed) || parsed < 0) throw new RequestBodyError(400, 'COLLECTOR_CONTENT_LENGTH_INVALID')
    if (parsed > maxBytes) throw new RequestBodyError(413, 'COLLECTOR_REQUEST_TOO_LARGE', true)
  }
  const chunks = await readLimitedBody(request, maxBytes)
  try {
    return JSON.parse(chunks.toString('utf8')) as unknown
  } catch (_error: unknown) {
    throw new RequestBodyError(400, 'COLLECTOR_JSON_INVALID')
  }
}

async function readLimitedBody(request: IncomingMessage, maxBytes: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let bytes = 0
    const cleanup = (): void => {
      request.off('data', onData)
      request.off('end', onEnd)
      request.off('error', onError)
      request.off('aborted', onAborted)
    }
    const onData = (value: Buffer | string): void => {
      const chunk = Buffer.isBuffer(value) ? value : Buffer.from(value)
      bytes += chunk.length
      if (bytes > maxBytes) {
        cleanup()
        request.pause()
        reject(new RequestBodyError(413, 'COLLECTOR_REQUEST_TOO_LARGE', true))
        return
      }
      chunks.push(chunk)
    }
    const onEnd = (): void => { cleanup(); resolve(Buffer.concat(chunks)) }
    const onError = (): void => { cleanup(); reject(new RequestBodyError(400, 'COLLECTOR_REQUEST_READ_FAILED')) }
    const onAborted = (): void => { cleanup(); reject(new RequestBodyError(400, 'COLLECTOR_REQUEST_ABORTED')) }
    request.on('data', onData)
    request.once('end', onEnd)
    request.once('error', onError)
    request.once('aborted', onAborted)
  })
}

function headerValue(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name]
  return Array.isArray(value) ? value[0] : value
}

function isJsonContentType(value: string | undefined): boolean {
  return value?.split(';', 1)[0]?.trim().toLowerCase() === 'application/json'
}

function sendError(
  response: ServerResponse,
  status: number,
  code: string,
  requestId: string,
  closeConnection = false
): void {
  sendJson(response, status, { status: 'REJECTED', code }, requestId, closeConnection)
}

function sendJson(
  response: ServerResponse,
  status: number,
  value: Record<string, unknown>,
  requestId: string,
  closeConnection = false
): void {
  if (response.headersSent || response.writableEnded) return
  response.statusCode = status
  response.setHeader('Content-Type', 'application/json; charset=utf-8')
  response.setHeader('X-Request-Id', requestId)
  if (closeConnection) response.setHeader('Connection', 'close')
  response.end(JSON.stringify({ ...value, requestId }))
}

class RequestBodyError extends Error {
  constructor(readonly status: number, readonly code: string, readonly closeConnection = false) {
    super(code)
  }
}
