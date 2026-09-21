import { createHash } from 'node:crypto'
import type { PackageFile } from '../shared/types.js'
import { shouldExcludeFromVersionDigest } from '../platform/paths.js'

/**
 * versionDigest — content fingerprint of a skill package.
 *
 * Exact algorithm (backend MUST match this to validate / compare digests):
 *
 * ```
 * // files: business package files after path exclusions
 * // exclusions: .git, node_modules, .DS_Store, *.tmp, credentials, ...
 * // paths: POSIX separators only (forward slash), already normalized
 * // sort: UTF-8 byte order of posix path
 *
 * lines = files
 *   .filter(f => !shouldExcludeFromVersionDigest(f.path))
 *   .map(f => ({ path: posix(f.path), bytes: f.content }))
 *   .sort((a, b) => compareUtf8(a.path, b.path))
 *   .map(f => `${f.path}\n${sha256Hex(f.bytes)}\n`)
 *
 * versionDigest = sha256Hex(utf8Bytes(lines.join('')))
 * ```
 *
 * Notes for backend parity:
 * - Do NOT hash the version label string; digest is content-only.
 * - `sha256Hex` is lowercase hex SHA-256.
 * - Each file line is: `posixPath` + `\n` + `sha256(fileBytes)` + `\n`
 * - File bytes are the exact package bytes (as stored in the ZIP/dir).
 * - Join is a plain concatenation of those lines (no extra separators).
 * - Empty file list still produces sha256 of empty string.
 *
 * CLI stores the result on PreparedSkillPackage.versionDigest.
 */
export function computeVersionDigest(files: PackageFile[]): string {
  const selected = files
    .filter((file) => !shouldExcludeFromVersionDigest(file.path))
    .map((file) => ({ path: toPosixPath(file.path), content: file.content }))

  selected.sort((left, right) => compareUtf8(left.path, right.path))

  const lines = selected.map((file) => {
    return `${file.path}\n${sha256Hex(file.content)}\n`
  })
  return sha256Hex(new TextEncoder().encode(lines.join('')))
}

/** Lowercase hex SHA-256 of bytes or UTF-8 string. */
export function sha256Hex(value: Uint8Array | string): string {
  return createHash('sha256').update(value).digest('hex')
}

export function toPosixPath(value: string): string {
  return value.replace(/\\/g, '/')
}

function compareUtf8(left: string, right: string): number {
  return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'))
}
