import type { IntegrationComponentStatus, IntegrationHealthStatus, InstallationState } from '../adapters/types.js'

export interface IntegrationHealthSummary {
  installationState: InstallationState
  healthStatus: IntegrationHealthStatus
  summary: string
}

export function summarizeIntegrationHealth(components: readonly IntegrationComponentStatus[]): IntegrationHealthSummary {
  if (components.length === 0 || components.every((component) => component.status === 'NOT_INSTALLED')) {
    return { installationState: 'DETECTED', healthStatus: 'UNKNOWN', summary: '尚未安装运行数据接入' }
  }
  if (components.some((component) => component.status === 'FAILED')) {
    return { installationState: 'FAILED', healthStatus: 'UNHEALTHY', summary: '运行数据接入存在失败项' }
  }
  if (components.some((component) => component.status === 'ACTION_REQUIRED')) {
    return { installationState: 'ACTION_REQUIRED', healthStatus: 'DEGRADED', summary: '运行数据接入需要人工操作' }
  }
  if (components.every((component) => component.status === 'READY')) {
    return { installationState: 'ACTIVE', healthStatus: 'HEALTHY', summary: '运行数据接入正常' }
  }
  return { installationState: 'VERIFYING', healthStatus: 'DEGRADED', summary: '运行数据接入尚未完整就绪' }
}
