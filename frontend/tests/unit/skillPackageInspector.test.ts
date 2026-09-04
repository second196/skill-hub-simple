import { describe, expect, it } from 'vitest'
import { parseSkillManifest } from '../../src/modules/asset-governance/services/skillPackageInspector'

describe('技能包元数据读取', () => {
  it('从 SKILL.md 元数据头读取发布信息', () => {
    const result = parseSkillManifest(`---
name: document-review
displayName: 文档评审
description: 检查文档结构和规范
version: 2.1.0
---
# 文档评审`, 'fallback.zip')

    expect(result).toEqual({
      name: '文档评审',
      description: '检查文档结构和规范',
      version: '2.1.0',
      slug: 'document-review'
    })
  })

  it('缺少元数据时使用文件名和正文摘要', () => {
    const result = parseSkillManifest('# 示例技能\n用于生成发布说明。', 'sample-skill.zip')
    expect(result.slug).toBe('sample-skill')
    expect(result.description).toBe('示例技能')
  })
})
