import { createHash } from 'node:crypto'

/** Lowercase hex SHA-256 of bytes or UTF-8 string. */
export function sha256Hex(value: Uint8Array | string): string {
  return createHash('sha256').update(value).digest('hex')
}
