import { describe, expect, it } from 'vitest'
import { buildFileTree } from '../../src/modules/asset-governance/services/fileTree'

describe('技能文件树', () => {
  it('生成目录和文件层级并让目录排在同级文件前', () => {
    const rows = buildFileTree(['SKILL.md', 'scripts/run.ts', 'scripts/lib/helper.ts'])
    expect(rows.map((row) => `${row.directory ? 'd' : 'f'}:${row.path}`)).toEqual([
      'd:scripts',
      'd:scripts/lib',
      'f:scripts/lib/helper.ts',
      'f:scripts/run.ts',
      'f:SKILL.md'
    ])
    expect(rows.find((row) => row.path === 'scripts/lib/helper.ts')?.depth).toBe(2)
  })
})
