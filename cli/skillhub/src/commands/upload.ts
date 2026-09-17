import { basename } from 'node:path'
import { prepareSkillPackage } from '../services/skill-package-service.js'
import { apiRequest } from '../clients/api-client.js'
import { CliError } from '../shared/errors.js'

export interface UploadOptions {
  inputPaths: string[]
  serviceUrl: string
  category: string
  name?: string
  description?: string
  json: boolean
}

export async function uploadCommand(options: UploadOptions): Promise<string> {
  const results: Array<Record<string, unknown>> = []
  for (const inputPath of options.inputPaths) {
    try {
      const prepared = await prepareSkillPackage(inputPath, {}, {
        name: options.name,
        description: options.description
      })
      const form = new FormData()
      form.append('file', new Blob([prepared.archive]), `${basename(inputPath)}.zip`)
      form.append('category', options.category)
      const result = await apiRequest<Record<string, unknown>>(options.serviceUrl, '/api/skills', {
        method: 'POST',
        body: form as unknown as BodyInit
      })
      results.push({ ok: true, inputPath, ...result })
    } catch (error: unknown) {
      results.push({
        ok: false,
        inputPath,
        message: error instanceof Error ? error.message : '上传失败',
        code: error instanceof CliError ? error.code : 'UPLOAD_FAILED'
      })
    }
  }

  const succeeded = results.filter((item) => item.ok)
  const failed = results.filter((item) => !item.ok)
  if (failed.length > 0) process.exitCode = 1
  if (options.json) {
    return JSON.stringify({
      ok: failed.length === 0,
      total: results.length,
      succeeded: succeeded.length,
      failed: failed.length,
      results
    })
  }
  const lines = [`上传完成：成功 ${succeeded.length} 个，失败 ${failed.length} 个`]
  for (const item of succeeded) {
    lines.push(`成功：${String(item.inputPath)} → ${String(item.name)}（${String(item.slug)}）`)
  }
  for (const item of failed) {
    lines.push(`失败：${String(item.inputPath)} → ${String(item.message)}`)
  }
  return lines.join('\n')
}
