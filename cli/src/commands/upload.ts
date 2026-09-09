import { basename } from 'node:path'
import { prepareSkillPackage } from '../services/skill-package-service.js'
import { apiRequest } from '../clients/api-client.js'

export interface UploadOptions { inputPath:string; serviceUrl:string; category:string; json:boolean }
export async function uploadCommand(options: UploadOptions): Promise<string> {
  const prepared = await prepareSkillPackage(options.inputPath)
  const form = new FormData(); form.append('file', new Blob([prepared.archive]), `${basename(options.inputPath)}.zip`); form.append('category', options.category)
  const result = await apiRequest<Record<string, unknown>>(options.serviceUrl, '/api/skills', { method:'POST', body:form as unknown as BodyInit })
  return options.json ? JSON.stringify({ ok:true, ...result }) : `上传成功：${String(result.name)}\n标识：${String(result.slug)}\n版本：${String(result.version_label)}`
}
