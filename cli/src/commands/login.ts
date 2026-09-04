import { SkillHubClient } from '../clients/skillhub-client.js'
import { DEFAULT_SERVICE_URL } from '../shared/constants.js'
import { ConfigStore, normalizeServiceUrl } from '../stores/config-store.js'
import { CredentialsStore } from '../stores/credentials-store.js'
import { resolveLoginToken } from '../services/auth-service.js'

export interface LoginCommandOptions {
  serviceUrl?: string
  token?: string
  json?: boolean
}

export async function loginCommand(options: LoginCommandOptions): Promise<string> {
  const configStore = new ConfigStore()
  const credentialsStore = new CredentialsStore()
  const currentConfig = await configStore.read()
  const serviceUrl = normalizeServiceUrl(options.serviceUrl ?? process.env.SKILLHUB_URL ?? currentConfig.serviceUrl ?? DEFAULT_SERVICE_URL)
  const token = await resolveLoginToken(options.token)
  const identity = await new SkillHubClient(serviceUrl, token).currentIdentity()
  await configStore.setServiceUrl(serviceUrl)
  await credentialsStore.setToken(serviceUrl, token)
  return options.json
    ? JSON.stringify({ ok: true, serviceUrl, username: identity.username })
    : `已登录 ${serviceUrl}，当前账户：${identity.username}`
}
