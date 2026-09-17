import { apiRequest } from '../clients/api-client.js'
export interface ListOptions { serviceUrl:string; category?:string; json:boolean }
export async function listCommand(options: ListOptions): Promise<string> {
  // Only return skills that can currently be installed. Offline skills stay
  // available to the web management UI, but are hidden from the CLI catalog.
  const params = new URLSearchParams({status:'ACTIVE'}); if(options.category) params.set('category',options.category)
  const items = await apiRequest<Array<Record<string, unknown>>>(options.serviceUrl, `/api/skills?${params}`)
  return options.json ? JSON.stringify(items) : (items.length ? items.map(item => `${item.name}  ${item.slug}  [${item.category}]  v${item.version_label}`).join('\n') : '暂无Skill')
}
