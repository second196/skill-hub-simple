import { describe, expect, it } from 'vitest'
import {
  runtimeIntegrationStateLabel,
  runtimeIntegrationTone,
  runtimeLabel
} from '../../src/modules/installation-recovery/services/installationDisplayText'

describe('运行时接入展示文案', () => {
  it('将运行时和接入状态完整转换为中文', () => {
    expect(runtimeLabel('codex-cli')).toBe('Codex 命令行')
    expect(runtimeLabel('claude-code-otlp')).toBe('Claude Code 遥测接入')
    expect(runtimeIntegrationStateLabel('ACTION_REQUIRED')).toBe('需要人工操作')
    expect(runtimeIntegrationStateLabel('REQUIRES_MANUAL')).toBe('需要人工处理')
    expect(runtimeIntegrationStateLabel('SERVER_ONLY_STATE')).toBe('未识别状态')
  })

  it('不把待操作、降级和人工处理显示为健康', () => {
    expect(runtimeIntegrationTone('ACTION_REQUIRED', 'UNKNOWN')).toBe('warning')
    expect(runtimeIntegrationTone('ACTIVE', 'DEGRADED')).toBe('warning')
    expect(runtimeIntegrationTone('REQUIRES_MANUAL', 'UNHEALTHY')).toBe('danger')
    expect(runtimeIntegrationTone('ACTIVE', 'HEALTHY')).toBe('healthy')
  })
})
