import { readFile } from 'node:fs/promises'
import { extname, basename } from 'node:path'

const KEEP_EXT = new Set(['.md', '.mdx', '.txt', '.rst'])
const DROP_EXT = new Set([
  '.ts', '.js', '.mjs', '.cjs', '.jsx', '.tsx', '.vue', '.py', '.java', '.go', '.rs',
  '.css', '.scss', '.json', '.yml', '.yaml', '.xml', '.html', '.sh', '.zsh', '.bash',
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.pdf', '.zip', '.gz', '.class',
  '.wasm', '.bin', '.exe', '.dll', '.so'
])

// Keep payloads small enough for CLI upload/heap. Oversized text is truncated, not summarized.
export const MAX_DOCUMENT_BYTES = 256 * 1024

export function isDocumentPath(path: string | undefined): boolean {
  if (!path) return false
  const normalized = path.replace(/\\/g, '/')
  const name = basename(normalized)
  if (name.toLowerCase() === 'skill.md') return true
  const ext = extname(name).toLowerCase()
  if (DROP_EXT.has(ext)) return false
  return KEEP_EXT.has(ext)
}

export function isCodePath(path: string | undefined): boolean {
  if (!path) return false
  const ext = extname(path).toLowerCase()
  return DROP_EXT.has(ext)
}

export async function readDocument(path: string): Promise<string | undefined> {
  try {
    const buf = await readFile(path)
    if (buf.includes(0)) return undefined
    if (buf.length > MAX_DOCUMENT_BYTES) {
      const head = buf.subarray(0, MAX_DOCUMENT_BYTES).toString('utf8')
      return `${head}\n\n[document truncated; original ${buf.length} bytes]`
    }
    return buf.toString('utf8')
  } catch {
    return undefined
  }
}

export function truncateText(value: string, maxBytes = MAX_DOCUMENT_BYTES): string {
  if (value.length <= maxBytes) return value
  return `${value.slice(0, maxBytes)}\n\n[document truncated; original ${value.length} chars]`
}

export function extractPaths(value: unknown, found = new Set<string>()): string[] {
  if (typeof value === 'string') {
    const matches = value.match(/(?:^|[\s'"=])((?:~|\/|\.\/|\.\.\/|[A-Za-z]:[\\/])[^\s'"]+)/g)
    if (matches) {
      for (const match of matches) {
        const path = match.replace(/^[\s'"=]+/, '').replace(/[)'"]+$/, '')
        if (isDocumentPath(path) || /SKILL\.md$/i.test(path)) found.add(path)
      }
    }
    if (isDocumentPath(value) || /SKILL\.md$/i.test(value)) found.add(value)
  } else if (Array.isArray(value)) {
    for (const item of value) extractPaths(item, found)
  } else if (value && typeof value === 'object') {
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      if (['path', 'file_path', 'filePath', 'filename', 'target_file', 'targetFile'].includes(key) && typeof item === 'string') {
        found.add(item)
      }
      extractPaths(item, found)
    }
  }
  return [...found]
}

export function valueContains(value: unknown, needle: string): boolean {
  if (!needle) return false
  if (typeof value === 'string') {
    if (value.includes(needle)) return true
    const escaped = JSON.stringify(needle).slice(1, -1)
    return escaped !== needle && value.includes(escaped)
  }
  if (Array.isArray(value)) return value.some((item) => valueContains(item, needle))
  if (value && typeof value === 'object') return Object.values(value as Record<string, unknown>).some((item) => valueContains(item, needle))
  return false
}
