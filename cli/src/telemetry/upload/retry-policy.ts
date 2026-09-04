export interface RetryPolicyOptions {
  maxAttempts?: number
  baseDelayMs?: number
  sleep?: (milliseconds: number) => Promise<void>
  random?: () => number
}

export async function executeWithRetry<T>(
  operation: (attempt: number) => Promise<T>,
  retryable: (error: unknown) => boolean,
  options: RetryPolicyOptions = {}
): Promise<T> {
  const maxAttempts = options.maxAttempts ?? 4
  const baseDelayMs = options.baseDelayMs ?? 250
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)))
  const random = options.random ?? Math.random
  let lastError: unknown
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      return await operation(attempt)
    } catch (error: unknown) {
      lastError = error
      if (attempt >= maxAttempts || !retryable(error)) throw error
      const jitter = Math.floor(baseDelayMs * random())
      await sleep(baseDelayMs * 2 ** (attempt - 1) + jitter)
    }
  }
  throw lastError
}
