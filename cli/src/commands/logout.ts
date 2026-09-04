import { DEFAULT_SERVICE_URL } from '../shared/constants.js'
import { ConfigStore, normalizeServiceUrl } from '../stores/config-store.js'
import { CredentialsStore } from '../stores/credentials-store.js'

export interface LogoutCommandOptions {
  serviceUrl?: string
  json?: boolean
}

export async function logoutCommand(options: LogoutCommandOptions): Promise<string> {
  const configStore = new ConfigStore()
  const serviceUrl = normalizeServiceUrl(options.serviceUrl ?? process.env.SKILLHUB_URL ?? (await configStore.read()).serviceUrl ?? DEFAULT_SERVICE_URL)
  await new CredentialsStore().deleteToken(serviceUrl)
  return options.json
    ? JSON.stringify({ ok: true, serviceUrl })
    : `已退出 ${serviceUrl}`
}
