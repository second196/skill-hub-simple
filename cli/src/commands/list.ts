import { apiRequest } from '../clients/api-client.js'
export interface ListOptions { serviceUrl:string; category?:string; json:boolean }
export async function listCommand(options: ListOptions): Promise<string> {
  const params = new URLSearchParams({includeOffline:'true'}); if(options.category) params.set('category',options.category)
  const items = await apiRequest<Array<Record<string, unknown>>>(options.serviceUrl, `/api/skills?${params}`)
  return options.json ? JSON.stringify(items) : (items.length ? items.map(item => `${item.name}  ${item.slug}  [${item.category}]  v${item.version_label}  ${item.status==='ACTIVE'?'已上架':'已下架'}`).join('\n') : '暂无技能')
}
