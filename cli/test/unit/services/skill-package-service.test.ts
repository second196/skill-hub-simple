import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, it } from 'node:test'
import { strToU8, zipSync } from 'fflate'
import { prepareSkillPackage } from '../../../src/services/skill-package-service.js'
import { PackageValidationError } from '../../../src/shared/errors.js'

const temporaryDirectories: string[] = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true, force: true })))
})

describe('Skill 包校验', () => {
  it('目录与等价 ZIP 产生相同清单和版本摘要，并保留完整相对结构', async () => {
    const directory = await createSkillDirectory()
    const fromDirectory = await prepareSkillPackage(directory)
    const zipPath = join(directory, '..', 'demo-skill.zip')
    await writeFile(zipPath, zipSync({
      'scripts/run.js': strToU8('console.log("ok")\n'),
      'SKILL.md': strToU8(validSkillMarkdown())
    }, { mtime: new Date(2025, 1, 2) }))

    const fromZip = await prepareSkillPackage(zipPath)

    assert.deepEqual(fromDirectory.manifest, fromZip.manifest)
    assert.equal(fromDirectory.versionDigest, fromZip.versionDigest)
    assert.deepEqual(fromDirectory.manifest.map((entry) => entry.path), ['SKILL.md', 'scripts/run.js'])
    assert.equal(fromDirectory.metadata.name, 'demo-skill')
    assert.equal(fromDirectory.metadata.version, '1.2.3')
  })

  it('目录打包排除版本库和本地凭据文件', async () => {
    const directory = await createSkillDirectory()
    await mkdir(join(directory, '.git'))
    await writeFile(join(directory, '.git', 'config'), 'secret')
    await writeFile(join(directory, '.env'), 'TOKEN=secret')
    await writeFile(join(directory, 'credentials.json'), '{"token":"secret"}')

    const prepared = await prepareSkillPackage(directory)

    assert.deepEqual(prepared.manifest.map((entry) => entry.path), ['SKILL.md', 'scripts/run.js'])
    assert.doesNotMatch(Buffer.from(prepared.archive).toString('latin1'), /TOKEN=secret|credentials/)
  })

  it('拒绝预制 ZIP 中的凭据文件', async () => {
    const directory = await createTemporaryDirectory('skill-package-secret-')
    const zipPath = join(directory, 'secret.zip')
    await writeFile(zipPath, zipSync({
      'SKILL.md': strToU8(validSkillMarkdown()),
      '.env': strToU8('SKILLHUB_TOKEN=secret')
    }))

    await assert.rejects(prepareSkillPackage(zipPath), isCode('SENSITIVE_PACKAGE_PATH'))
  })

  it('拒绝缺少主描述、坏 YAML、缺少字段和非法语义版本', async (context) => {
    await context.test('缺少 SKILL.md', async () => {
      const directory = await createTemporaryDirectory('skill-package-missing-')
      await writeFile(join(directory, 'README.md'), '# Missing Skill')
      await assert.rejects(prepareSkillPackage(directory), isCode('SKILL_FILE_REQUIRED'))
    })
    await context.test('YAML 无法解析', async () => {
      const directory = await createTemporaryDirectory('skill-package-yaml-')
      await writeFile(join(directory, 'SKILL.md'), '---\nname: [broken\n---\n')
      await assert.rejects(prepareSkillPackage(directory), isCode('INVALID_SKILL_FRONTMATTER'))
    })
    await context.test('缺少描述', async () => {
      const directory = await createTemporaryDirectory('skill-package-description-')
      await writeFile(join(directory, 'SKILL.md'), '---\nname: demo\nversion: 1.0.0\n---\n')
      await assert.rejects(prepareSkillPackage(directory), isCode('INVALID_SKILL_METADATA'))
    })
    await context.test('版本不符合语义化版本', async () => {
      const directory = await createTemporaryDirectory('skill-package-version-')
      await writeFile(join(directory, 'SKILL.md'), '---\nname: demo\ndescription: 示例\nversion: latest\n---\n')
      await assert.rejects(prepareSkillPackage(directory), isCode('INVALID_SKILL_METADATA'))
    })
  })

  it('拒绝目录中的符号链接', async (context) => {
    const directory = await createSkillDirectory()
    try {
      await symlink(join(directory, 'SKILL.md'), join(directory, 'linked-skill.md'))
    } catch (error: unknown) {
      if (process.platform === 'win32' && isPermissionError(error)) {
        context.skip('当前 Windows 账户没有创建符号链接权限')
        return
      }
      throw error
    }
    await assert.rejects(prepareSkillPackage(directory), isCode('SYMLINK_NOT_ALLOWED'))
  })

  it('支持可配置文件数量边界', async () => {
    const directory = await createSkillDirectory()
    await assert.rejects(
      prepareSkillPackage(directory, { maxFiles: 1 }),
      isCode('PACKAGE_FILE_COUNT_EXCEEDED')
    )
  })
})

async function createSkillDirectory(): Promise<string> {
  const directory = await createTemporaryDirectory('skill-package-valid-')
  await mkdir(join(directory, 'scripts'))
  await writeFile(join(directory, 'SKILL.md'), validSkillMarkdown())
  await writeFile(join(directory, 'scripts', 'run.js'), 'console.log("ok")\n')
  return directory
}

async function createTemporaryDirectory(prefix: string): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), prefix))
  temporaryDirectories.push(directory)
  return directory
}

function validSkillMarkdown(): string {
  return '---\nname: demo-skill\ndescription: 用于测试的 Skill\nversion: 1.2.3\n---\n# Demo\n'
}

function isCode(code: string): (error: unknown) => boolean {
  return (error) => error instanceof PackageValidationError && error.code === code
}

function isPermissionError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as NodeJS.ErrnoException).code === 'EPERM'
}
