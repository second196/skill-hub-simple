import { createHash } from 'node:crypto'
import type { ClientName } from './types.js'

export function sessionSafeId(clientName: ClientName | string, sessionId: string): string {
  return createHash('sha256').update(`${clientName}\0${sessionId}`).digest('hex')
}

export function ingestSessionKey(clientName: ClientName | string, sessionId: string): string {
  const raw = `${clientName}:${sessionId}`
  if (Buffer.byteLength(raw) <= 256) return raw
  return `${clientName}:sha256:${createHash('sha256').update(sessionId).digest('hex')}`
}

export function sessionStateKey(clientName: ClientName | string, sessionId: string): string {
  return `${clientName}:${sessionId}`
}
