import { CollectorConfigStore } from './collector-config-store.js'
import { LocalCollectorServer } from './local-collector-server.js'

const argumentsMap = parseArguments(process.argv.slice(2))
const server = new LocalCollectorServer({
  store: new CollectorConfigStore(argumentsMap.home),
  instanceId: argumentsMap.instanceId
})

void server.start().then(() => {
  const stop = async (): Promise<void> => {
    await server.stop()
    process.exit(0)
  }
  process.once('SIGINT', () => { void stop() })
  process.once('SIGTERM', () => { void stop() })
}).catch(() => {
  process.exitCode = 1
})

function parseArguments(values: string[]): { home: string; instanceId: string } {
  const homeIndex = values.indexOf('--home')
  const instanceIndex = values.indexOf('--instance-id')
  const home = homeIndex >= 0 ? values[homeIndex + 1] : undefined
  const instanceId = instanceIndex >= 0 ? values[instanceIndex + 1] : undefined
  if (home === undefined || instanceId === undefined || home.length === 0 || instanceId.length === 0) {
    throw new Error('本地采集器启动参数无效')
  }
  return { home, instanceId }
}
