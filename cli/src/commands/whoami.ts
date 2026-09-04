import { SkillHubClient } from '../clients/skillhub-client.js'
import { DEFAULT_SERVICE_URL } from '../shared/constants.js'
import { ConfigStore, normalizeServiceUrl } from '../stores/config-store.js'
import { resolveStoredToken } from '../services/auth-service.js'

export interface WhoamiCommandOptions {
  serviceUrl?: string
  json?: boolean
}

export async function whoamiCommand(options: WhoamiCommandOptions): Promise<string> {
  const configStore = new ConfigStore()
  const serviceUrl = normalizeServiceUrl(options.serviceUrl ?? process.env.SKILLHUB_URL ?? (await configStore.read()).serviceUrl ?? DEFAULT_SERVICE_URL)
  const token = await resolveStoredToken(serviceUrl)
  const identity = await new SkillHubClient(serviceUrl, token).currentIdentity()
  return options.json
    ? JSON.stringify({ ok: true, serviceUrl, username: identity.username })
    : `服务：${serviceUrl}\n账户：${identity.username}`
}
