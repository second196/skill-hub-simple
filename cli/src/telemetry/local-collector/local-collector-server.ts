import { createServer, type Server } from 'node:http'
import { open, readFile, rm, type FileHandle } from 'node:fs/promises'
import { createDefaultCollectorRegistry } from '../collectors/collector-registry.js'
import { JsonlSpool } from '../spool/jsonl-spool.js'
import { CollectorRouter } from './collector-router.js'
import { CollectorConfigStore } from './collector-config-store.js'
import { writeFileAtomic } from '../../platform/atomic-file.js'
import { EXIT_CODE } from '../../shared/constants.js'
import { CliError } from '../../shared/errors.js'

interface CollectorProcessRecord {
  pid: number
  instanceId: string
  protocolVersion: string
  startedAt: string
}

export interface LocalCollectorServerOptions {
  store: CollectorConfigStore
  instanceId: string
  now?: () => Date
}

/** 持有本地 Collector 单实例锁，并只监听受管 IPv4 回环地址。 */
export class LocalCollectorServer {
  private server?: Server
  private lockHandle?: FileHandle
  private processRecord?: CollectorProcessRecord

  constructor(private readonly options: LocalCollectorServerOptions) {}

  async start(): Promise<void> {
    if (this.server !== undefined) return
    const { config, secret } = await this.options.store.ensureInitialized()
    this.lockHandle = await acquireProcessLock(this.options.store.lockPath, this.options.instanceId)
    const router = new CollectorRouter({
      config,
      secret,
      instanceId: this.options.instanceId,
      registry: createDefaultCollectorRegistry(),
      spoolFactory: (runtimeKey, spoolPartition) => new JsonlSpool(
        this.options.store.skillhubHome, runtimeKey, spoolPartition, this.options.now)
    })
    const server = createServer((request, response) => { void router.handle(request, response) })
    try {
      await listen(server, config.host, config.port)
      const record = {
        pid: process.pid,
        instanceId: this.options.instanceId,
        protocolVersion: '1.0',
        startedAt: (this.options.now ?? (() => new Date()))().toISOString()
      }
      await writeFileAtomic(this.options.store.pidPath, `${JSON.stringify(record)}\n`, {
        expectedDigest: null,
        mode: 0o600
      })
      this.processRecord = record
      this.server = server
    } catch (error: unknown) {
      await close(server)
      await this.releaseFiles()
      throw error
    }
  }

  async stop(): Promise<void> {
    const server = this.server
    this.server = undefined
    if (server !== undefined) await close(server)
    await this.releaseFiles()
  }

  private async releaseFiles(): Promise<void> {
    if (this.processRecord !== undefined && await ownsProcessFile(
      this.options.store.pidPath, this.processRecord.instanceId)) {
      await rm(this.options.store.pidPath, { force: true })
    }
    this.processRecord = undefined
    if (this.lockHandle !== undefined) {
      await this.lockHandle.close()
      this.lockHandle = undefined
      await rm(this.options.store.lockPath, { force: true })
    }
  }
}

async function acquireProcessLock(path: string, instanceId: string): Promise<FileHandle> {
  try {
    const handle = await open(path, 'wx', 0o600)
    await handle.writeFile(`${JSON.stringify({ pid: process.pid, instanceId })}\n`, 'utf8')
    await handle.sync()
    return handle
  } catch (error: unknown) {
    if (errorCode(error) === 'EEXIST') {
      throw new CliError('本地采集器已有进程锁', 'COLLECTOR_ALREADY_RUNNING', EXIT_CODE.validation)
    }
    throw error
  }
}

async function listen(server: Server, host: string, port: number): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const onError = (error: Error) => { server.off('listening', onListening); reject(error) }
    const onListening = () => { server.off('error', onError); resolve() }
    server.once('error', onError)
    server.once('listening', onListening)
    server.listen(port, host)
  })
}

async function close(server: Server): Promise<void> {
  if (!server.listening) return
  await new Promise<void>((resolve, reject) => server.close((error) => error === undefined ? resolve() : reject(error)))
}

async function ownsProcessFile(path: string, instanceId: string): Promise<boolean> {
  try {
    const value: unknown = JSON.parse(await readFile(path, 'utf8'))
    return typeof value === 'object' && value !== null
      && (value as Record<string, unknown>).instanceId === instanceId
  } catch (_error: unknown) {
    return false
  }
}

function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null ? (error as NodeJS.ErrnoException).code : undefined
}
