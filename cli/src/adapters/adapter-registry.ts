import { EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'
import type { RuntimeAdapter, RuntimeKey } from './types.js'

export class AdapterRegistry {
  private readonly adapters = new Map<RuntimeKey, RuntimeAdapter>()

  constructor(adapters: readonly RuntimeAdapter[]) {
    for (const adapter of adapters) {
      if (this.adapters.has(adapter.runtimeKey)) {
        throw new CliError('运行时适配器重复注册', 'DUPLICATE_RUNTIME_ADAPTER', EXIT_CODE.validation,
          { runtime: adapter.runtimeKey })
      }
      this.adapters.set(adapter.runtimeKey, adapter)
    }
  }

  require(runtime: string): RuntimeAdapter {
    const adapter = this.adapters.get(runtime as RuntimeKey)
    if (adapter === undefined) {
      throw new CliError('当前版本不支持指定运行时', 'UNSUPPORTED_RUNTIME', EXIT_CODE.usage,
        { runtime, supportedRuntimes: this.keys() })
    }
    return adapter
  }

  keys(): RuntimeKey[] {
    return Array.from(this.adapters.keys()).sort()
  }
}
