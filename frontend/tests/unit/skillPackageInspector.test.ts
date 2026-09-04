import { describe, expect, it } from 'vitest'
import {
  packageFolderAsZip,
  slugFromPackageName
} from '../../src/modules/asset-governance/services/skillPackageInspector'

function folderFile(content: string, name: string, relativePath: string): File {
  const file = new File([content], name, { type: 'text/plain' })
  Object.defineProperty(file, 'webkitRelativePath', { value: relativePath })
  return file
}

function asFileList(files: File[]): FileList {
  return files as unknown as FileList
}

describe('技能包发布准备', () => {
  it('从 ZIP 文件名生成稳定的技能标识', () => {
    expect(slugFromPackageName('Document Review.zip')).toBe('document-review')
    expect(slugFromPackageName('sample-skill.ZIP')).toBe('sample-skill')
  })

  it('将文件夹原样打包为由服务端继续校验的 ZIP', async () => {
    const archive = await packageFolderAsZip(asFileList([
      folderFile('---\nname: demo\n---', 'SKILL.md', 'demo/SKILL.md'),
      folderFile('# Demo', 'README.md', 'demo/README.md'),
      folderFile('secret', '.env', 'demo/.env')
    ]))

    expect(archive.name).toBe('demo.zip')
    expect(archive.type).toBe('application/zip')
    expect(archive.size).toBeGreaterThan(0)
    expect(new TextDecoder().decode(await archive.arrayBuffer())).toContain('SKILL.md')
    expect(new TextDecoder().decode(await archive.arrayBuffer())).toContain('.env')
  })

  it('拒绝根目录缺少 SKILL.md 的文件夹', async () => {
    await expect(packageFolderAsZip(asFileList([
      folderFile('# Demo', 'README.md', 'demo/README.md')
    ]))).rejects.toThrow('所选文件夹根目录必须包含 SKILL.md')
  })
})
