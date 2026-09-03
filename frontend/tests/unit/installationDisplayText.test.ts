import { describe, expect, it } from 'vitest'
import { digestLabel, installationLabel, operationLabel } from '../../src/modules/installation-recovery/services/installationDisplayText'

describe('安装展示文案', () => {
  it('将安装状态和操作类型转换为中文', () => {
    expect(installationLabel('INSTALLING_TRACKER')).toBe('安装运行跟踪器中')
    expect(installationLabel('REQUIRES_MANUAL')).toBe('需要人工处理')
    expect(operationLabel('ROLLBACK')).toBe('回退')
  })

  it('不猜测缺失版本并截断已知摘要', () => {
    expect(digestLabel(undefined)).toBe('未记录版本')
    expect(digestLabel('a'.repeat(64))).toBe('aaaaaaaaaaaa…')
  })
})
