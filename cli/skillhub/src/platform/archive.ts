import { unzipSync, zipSync, type Zippable } from 'fflate'
import { normalizePackagePath } from './paths.js'
import { PackageValidationError } from '../shared/errors.js'
import {
  DEFAULT_PACKAGE_LIMITS,
  type PackageFile,
  type PackageLimitOverrides,
  type PackageLimits
} from '../shared/types.js'

const END_OF_CENTRAL_DIRECTORY = 0x06054b50
const CENTRAL_DIRECTORY_ENTRY = 0x02014b50
const LOCAL_FILE_HEADER = 0x04034b50
const ZIP64_16 = 0xffff
const ZIP64_32 = 0xffffffff
const FIXED_ZIP_TIME = new Date(1980, 0, 1, 0, 0, 0)

interface ArchiveEntry {
  rawPath: string
  path: string
  directory: boolean
  uncompressedSize: number
  localHeaderOffset: number
}

export function createArchive(files: PackageFile[], overrides: PackageLimitOverrides = {}): Uint8Array {
  const limits = resolvePackageLimits(overrides)
  validateFiles(files, limits)
  const entries: Zippable = {}
  for (const file of [...files].sort((left, right) => compareUtf8(left.path, right.path))) {
    entries[file.path] = [file.content, {
      attrs: 0o100644 << 16,
      mtime: FIXED_ZIP_TIME,
      os: 3
    }]
  }
  const archive = zipSync(entries, { level: 6, mtime: FIXED_ZIP_TIME, os: 3 })
  if (archive.byteLength > limits.maxArchiveBytes) {
    throw new PackageValidationError('Skill 压缩包超过大小限制', 'PACKAGE_ARCHIVE_TOO_LARGE')
  }
  return archive
}

export function readArchive(archive: Uint8Array, overrides: PackageLimitOverrides = {}): PackageFile[] {
  const limits = resolvePackageLimits(overrides)
  if (archive.byteLength > limits.maxArchiveBytes) {
    throw new PackageValidationError('Skill 压缩包超过大小限制', 'PACKAGE_ARCHIVE_TOO_LARGE')
  }
  const entries = inspectCentralDirectory(archive, limits)
  let unzipped: Record<string, Uint8Array>
  try {
    unzipped = unzipSync(archive)
  } catch (_error: unknown) {
    throw new PackageValidationError('Skill ZIP 无法解压', 'INVALID_SKILL_ARCHIVE')
  }

  const files = entries
    .filter((entry) => !entry.directory)
    .map((entry) => {
      const content = unzipped[entry.rawPath]
      if (content === undefined || content.byteLength !== entry.uncompressedSize) {
        throw new PackageValidationError('Skill ZIP 条目大小不一致', 'INVALID_SKILL_ARCHIVE', { path: entry.path })
      }
      return { path: entry.path, content }
    })
  validateFiles(files, limits)
  return files.sort((left, right) => compareUtf8(left.path, right.path))
}

export function resolvePackageLimits(overrides: PackageLimitOverrides = {}): PackageLimits {
  return { ...DEFAULT_PACKAGE_LIMITS, ...overrides }
}

function inspectCentralDirectory(archive: Uint8Array, limits: PackageLimits): ArchiveEntry[] {
  const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength)
  const eocdOffset = findEndOfCentralDirectory(view)
  if (eocdOffset < 0) invalidArchive()

  const disk = view.getUint16(eocdOffset + 4, true)
  const centralDisk = view.getUint16(eocdOffset + 6, true)
  const entriesOnDisk = view.getUint16(eocdOffset + 8, true)
  const entryCount = view.getUint16(eocdOffset + 10, true)
  const centralSize = view.getUint32(eocdOffset + 12, true)
  const centralOffset = view.getUint32(eocdOffset + 16, true)
  const commentLength = view.getUint16(eocdOffset + 20, true)
  if (entryCount === ZIP64_16 || centralSize === ZIP64_32 || centralOffset === ZIP64_32) {
    throw new PackageValidationError('暂不支持 ZIP64 Skill 包', 'UNSUPPORTED_SKILL_ARCHIVE')
  }
  if (disk !== 0 || centralDisk !== 0 || entriesOnDisk !== entryCount) {
    throw new PackageValidationError('暂不支持分卷 Skill ZIP', 'UNSUPPORTED_SKILL_ARCHIVE')
  }
  if (entryCount > limits.maxFiles + limits.maxFiles) {
    throw new PackageValidationError('Skill 包文件数量超过限制', 'PACKAGE_FILE_COUNT_EXCEEDED')
  }
  if (eocdOffset + 22 + commentLength !== archive.byteLength || centralOffset + centralSize > eocdOffset) {
    invalidArchive()
  }

  const paths = new Set<string>()
  const entries: ArchiveEntry[] = []
  let fileCount = 0
  let expandedBytes = 0
  let offset = centralOffset
  for (let index = 0; index < entryCount; index += 1) {
    if (offset + 46 > eocdOffset || view.getUint32(offset, true) !== CENTRAL_DIRECTORY_ENTRY) invalidArchive()
    const flags = view.getUint16(offset + 8, true)
    if ((flags & 0x1) !== 0) {
      throw new PackageValidationError('不支持加密 Skill ZIP', 'UNSUPPORTED_SKILL_ARCHIVE')
    }
    const uncompressedSize = view.getUint32(offset + 24, true)
    const pathLength = view.getUint16(offset + 28, true)
    const extraLength = view.getUint16(offset + 30, true)
    const entryCommentLength = view.getUint16(offset + 32, true)
    const externalAttributes = view.getUint32(offset + 38, true)
    const localHeaderOffset = view.getUint32(offset + 42, true)
    if (uncompressedSize === ZIP64_32 || localHeaderOffset === ZIP64_32) {
      throw new PackageValidationError('暂不支持 ZIP64 Skill 包', 'UNSUPPORTED_SKILL_ARCHIVE')
    }
    const pathStart = offset + 46
    const pathEnd = pathStart + pathLength
    const nextOffset = pathEnd + extraLength + entryCommentLength
    if (pathEnd > eocdOffset || nextOffset > eocdOffset) invalidArchive()

    const rawPath = decodePath(archive.subarray(pathStart, pathEnd))
    const directory = rawPath.endsWith('/')
    const path = normalizePackagePath(directory ? rawPath.slice(0, -1) : rawPath, limits.maxPathLength)
    if (paths.has(path)) {
      throw new PackageValidationError('Skill 包包含重复路径', 'DUPLICATE_PACKAGE_PATH', { path })
    }
    paths.add(path)
    const unixMode = externalAttributes >>> 16
    if ((unixMode & 0xf000) === 0xa000) {
      throw new PackageValidationError('Skill 包不允许符号链接', 'SYMLINK_NOT_ALLOWED', { path })
    }
    validateLocalHeader(view, archive, localHeaderOffset, rawPath)

    if (!directory) {
      fileCount += 1
      expandedBytes += uncompressedSize
      if (fileCount > limits.maxFiles) {
        throw new PackageValidationError('Skill 包文件数量超过限制', 'PACKAGE_FILE_COUNT_EXCEEDED')
      }
      if (uncompressedSize > limits.maxSingleFileBytes) {
        throw new PackageValidationError('Skill 包内单个文件超过大小限制', 'PACKAGE_FILE_TOO_LARGE', { path })
      }
      if (expandedBytes > limits.maxExpandedBytes) {
        throw new PackageValidationError('Skill 包解压后超过大小限制', 'PACKAGE_EXPANDED_TOO_LARGE')
      }
    }
    entries.push({ rawPath, path, directory, uncompressedSize, localHeaderOffset })
    offset = nextOffset
  }
  if (offset !== centralOffset + centralSize) invalidArchive()
  return entries
}

function validateLocalHeader(
  view: DataView,
  archive: Uint8Array,
  offset: number,
  expectedPath: string
): void {
  if (offset + 30 > archive.byteLength || view.getUint32(offset, true) !== LOCAL_FILE_HEADER) invalidArchive()
  const pathLength = view.getUint16(offset + 26, true)
  const extraLength = view.getUint16(offset + 28, true)
  const pathStart = offset + 30
  const pathEnd = pathStart + pathLength
  if (pathEnd + extraLength > archive.byteLength || decodePath(archive.subarray(pathStart, pathEnd)) !== expectedPath) {
    invalidArchive()
  }
}

function validateFiles(files: PackageFile[], limits: PackageLimits): void {
  if (files.length > limits.maxFiles) {
    throw new PackageValidationError('Skill 包文件数量超过限制', 'PACKAGE_FILE_COUNT_EXCEEDED')
  }
  const paths = new Set<string>()
  let expandedBytes = 0
  for (const file of files) {
    const path = normalizePackagePath(file.path, limits.maxPathLength)
    if (path !== file.path || paths.has(path)) {
      throw new PackageValidationError('Skill 包包含重复路径', 'DUPLICATE_PACKAGE_PATH', { path })
    }
    paths.add(path)
    if (file.content.byteLength > limits.maxSingleFileBytes) {
      throw new PackageValidationError('Skill 包内单个文件超过大小限制', 'PACKAGE_FILE_TOO_LARGE', { path })
    }
    expandedBytes += file.content.byteLength
    if (expandedBytes > limits.maxExpandedBytes) {
      throw new PackageValidationError('Skill 包解压后超过大小限制', 'PACKAGE_EXPANDED_TOO_LARGE')
    }
  }
}

function decodePath(bytes: Uint8Array): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch (_error: unknown) {
    throw new PackageValidationError('Skill 包路径必须使用 UTF-8', 'INVALID_PACKAGE_PATH_ENCODING')
  }
}

function findEndOfCentralDirectory(view: DataView): number {
  if (view.byteLength < 22) return -1
  const minimumOffset = Math.max(0, view.byteLength - 0xffff - 22)
  for (let offset = view.byteLength - 22; offset >= minimumOffset; offset -= 1) {
    if (view.getUint32(offset, true) === END_OF_CENTRAL_DIRECTORY) return offset
  }
  return -1
}

function invalidArchive(): never {
  throw new PackageValidationError('Skill ZIP 目录结构无效', 'INVALID_SKILL_ARCHIVE')
}

function compareUtf8(left: string, right: string): number {
  return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'))
}
