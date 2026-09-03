import { describe, expect, it } from 'vitest'
import { decisionLabel, importCodeLabel, importStageLabel, lifecycleLabel, reasonLabel, scopeLabel } from '../../src/modules/asset-governance/services/displayText'

describe('展示文案映射', () => {
  it('将治理状态和范围转换为中文', () => {
    expect(lifecycleLabel('PUBLISHED')).toBe('已发布')
    expect(decisionLabel('PENDING_APPROVAL')).toBe('待审批')
    expect(scopeLabel('ENVIRONMENT')).toBe('环境')
  })

  it('为未知值和门禁原因提供中文兜底', () => {
    expect(lifecycleLabel('NEW_STATE')).toBe('未知状态（NEW_STATE）')
    expect(reasonLabel('required_evidence_missing')).toBe('缺少必需的门禁证据')
    expect(reasonLabel('evidence_not_pass:STATIC_SCAN')).toBe('门禁证据未通过：STATIC_SCAN')
    expect(importStageLabel('PERSISTENCE')).toBe('数据保存')
    expect(importCodeLabel('IMPORT_FAILED')).toBe('导入处理失败')
  })
})
