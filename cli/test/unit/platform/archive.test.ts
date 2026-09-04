import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { strToU8, zipSync } from 'fflate'
import { createArchive, readArchive } from '../../../src/platform/archive.js'
import { PackageValidationError } from '../../../src/shared/errors.js'

describe('ZIP 归档边界', () => {
  it('使用固定元数据和排序路径生成确定性 ZIP', () => {
    const files = [
      { path: 'scripts/run.js', content: strToU8('console.log("ok")') },
      { path: 'SKILL.md', content: strToU8('# Skill') }
    ]

    const first = createArchive(files)
    const second = createArchive([...files].reverse())

    assert.deepEqual(first, second)
    assert.deepEqual(readArchive(first).map((file) => file.path), ['SKILL.md', 'scripts/run.js'])
  })

  it('在解压前拒绝穿越、反斜杠、重复和符号链接条目', () => {
    const traversal = zipSync({ '../outside': strToU8('bad') })
    assert.throws(() => readArchive(traversal), isCode('UNSAFE_PACKAGE_PATH'))

    const backslash = zipSync({ 'folder\\secret': strToU8('bad') })
    assert.throws(() => readArchive(backslash), isCode('UNSAFE_PACKAGE_PATH'))

    const duplicate = duplicateSecondEntry(zipSync({ 'a.txt': strToU8('a'), 'b.txt': strToU8('b') }))
    assert.throws(() => readArchive(duplicate), isCode('DUPLICATE_PACKAGE_PATH'))

    const symlink = zipSync({
      'link': [strToU8('target'), { os: 3, attrs: 0o120777 << 16 }]
    })
    assert.throws(() => readArchive(symlink), isCode('SYMLINK_NOT_ALLOWED'))
  })

  it('同时限制压缩大小、单文件大小、总解压大小和文件数量', () => {
    const archive = zipSync({ 'a.txt': strToU8('1234'), 'b.txt': strToU8('5678') })

    assert.throws(() => readArchive(archive, { maxArchiveBytes: 1 }), isCode('PACKAGE_ARCHIVE_TOO_LARGE'))
    assert.throws(() => readArchive(archive, { maxSingleFileBytes: 3 }), isCode('PACKAGE_FILE_TOO_LARGE'))
    assert.throws(() => readArchive(archive, { maxExpandedBytes: 7 }), isCode('PACKAGE_EXPANDED_TOO_LARGE'))
    assert.throws(() => readArchive(archive, { maxFiles: 1 }), isCode('PACKAGE_FILE_COUNT_EXCEEDED'))
  })
})

function isCode(code: string): (error: unknown) => boolean {
  return (error) => error instanceof PackageValidationError && error.code === code
}

function duplicateSecondEntry(archive: Uint8Array): Uint8Array {
  const result = archive.slice()
  const source = strToU8('b.txt')
  const replacement = strToU8('a.txt')
  for (let index = 0; index <= result.length - source.length; index += 1) {
    if (source.every((value, offset) => result[index + offset] === value)) {
      result.set(replacement, index)
    }
  }
  return result
}
