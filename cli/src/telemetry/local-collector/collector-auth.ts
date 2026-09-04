import { createHash, timingSafeEqual } from 'node:crypto'

export const COLLECTOR_SECRET_HEADER = 'x-skillhub-collector-key'
export const COLLECTOR_RUNTIME_HEADER = 'x-skillhub-runtime-key'

/** 使用固定长度摘要比较本地 Collector 密钥，避免直接字符串比较泄露时序。 */
export function collectorSecretMatches(expected: string, supplied: string | undefined): boolean {
  if (supplied === undefined || supplied.length === 0) return false
  return timingSafeEqual(secretDigest(expected), secretDigest(supplied))
}

function secretDigest(value: string): Buffer {
  return createHash('sha256').update(value, 'utf8').digest()
}
