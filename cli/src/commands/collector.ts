import type { CollectorProcessManager, CollectorProcessStatus } from '../telemetry/local-collector/collector-process-manager.js'

export type CollectorCommandAction = 'collector-start' | 'collector-stop' | 'collector-status'

/** 执行本地 Collector 生命周期动作并返回中文或稳定 JSON 结果。 */
export async function collectorCommand(
  action: CollectorCommandAction,
  manager: Pick<CollectorProcessManager, 'start' | 'stop' | 'status'>,
  json: boolean
): Promise<string> {
  const result = action === 'collector-start'
    ? await manager.start()
    : action === 'collector-stop'
      ? await manager.stop()
      : await manager.status()
  if (json) return JSON.stringify({ ok: result.state === 'HEALTHY' || result.state === 'STOPPED', action, ...result })
  return [
    `本地采集器状态：${stateText(result.state)}`,
    `监听地址：${result.host}:${result.port}`,
    `状态说明：${result.message}`,
    ...(result.pid === undefined ? [] : [`进程编号：${result.pid}`]),
    ...(result.protocolVersion === undefined ? [] : [`协议版本：${result.protocolVersion}`])
  ].join('\n')
}

function stateText(value: CollectorProcessStatus['state']): string {
  const labels: Record<CollectorProcessStatus['state'], string> = {
    STOPPED: '已停止',
    STARTING: '正在启动',
    HEALTHY: '健康',
    PORT_CONFLICT: '端口冲突',
    AUTH_MISMATCH: '鉴权不匹配',
    STALE_PROCESS: '存在陈旧状态',
    FAILED: '异常'
  }
  return labels[value]
}
