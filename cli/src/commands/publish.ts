import { randomUUID } from 'node:crypto'
import { SkillHubClient, type PackageImportResult, type ReviewSubmissionResult } from '../clients/skillhub-client.js'
import { prepareSkillPackage } from '../services/skill-package-service.js'
import { resolveStoredToken } from '../services/auth-service.js'
import { DEFAULT_SERVICE_URL, EXIT_CODE } from '../shared/constants.js'
import { CliError } from '../shared/errors.js'
import { ConfigStore, normalizeServiceUrl } from '../stores/config-store.js'

export interface PublishCommandOptions {
  inputPath: string
  serviceUrl?: string
  scopeId?: number
  dryRun?: boolean
  submitReview?: boolean
  requestId?: string
  comment?: string
  json?: boolean
}

export async function publishCommand(options: PublishCommandOptions): Promise<string> {
  if (options.inputPath.trim().length === 0) {
    throw new CliError('请提供 Skill 目录或 ZIP 路径', 'SKILL_PATH_REQUIRED', EXIT_CODE.usage)
  }
  const prepared = await prepareSkillPackage(options.inputPath)
  if (options.dryRun === true) return dryRunOutput(prepared, Boolean(options.json))
  if (!Number.isSafeInteger(options.scopeId) || (options.scopeId ?? 0) <= 0) {
    throw new CliError('上传 Skill 时必须提供有效的范围编号', 'SCOPE_ID_REQUIRED', EXIT_CODE.usage)
  }

  const configStore = new ConfigStore()
  const currentConfig = await configStore.read()
  const serviceUrl = normalizeServiceUrl(
    options.serviceUrl ?? process.env.SKILLHUB_URL ?? currentConfig.serviceUrl ?? DEFAULT_SERVICE_URL)
  const token = await resolveStoredToken(serviceUrl)
  const requestId = validRequestId(options.requestId ?? randomUUID())
  const client = new SkillHubClient(serviceUrl, token)
  const serverValidation = await client.validateSkillPackage(
    prepared.archive, prepared.artifactDigest, prepared.versionDigest, options.scopeId as number, requestId)
  if (serverValidation.artifactDigest !== prepared.artifactDigest
      || serverValidation.versionDigest !== prepared.versionDigest) {
    throw new CliError('客户端与服务端的 Skill 摘要不一致', 'SERVER_DIGEST_MISMATCH', EXIT_CODE.validation,
      { requestId })
  }

  const upload = await client.importSkillPackage(
    prepared.archive, prepared.artifactDigest, prepared.versionDigest, options.scopeId as number, requestId)
  if (upload.status !== 'SUCCEEDED' || upload.versionDigest === undefined) {
    throw new CliError(upload.failureReason ?? 'Skill 上传失败', upload.failureCode ?? 'SKILL_UPLOAD_FAILED',
      EXIT_CODE.validation, { requestId, failureStage: upload.failureStage })
  }
  if (upload.artifactDigest !== prepared.artifactDigest || upload.versionDigest !== prepared.versionDigest) {
    throw new CliError('上传结果与本地 Skill 摘要不一致', 'UPLOAD_DIGEST_MISMATCH', EXIT_CODE.validation,
      { requestId })
  }

  let review: ReviewSubmissionResult | undefined
  if (options.submitReview === true) {
    if (upload.lifecycleState !== 'DRAFT') {
      throw new CliError('只有草稿版本可以提交审核', 'REVIEW_REQUIRES_DRAFT', EXIT_CODE.validation, { requestId })
    }
    review = await client.submitReview(upload.versionDigest, options.comment, requestId)
  }
  return publishOutput(serviceUrl, requestId, prepared.manifest.length, upload, review, Boolean(options.json))
}

function dryRunOutput(prepared: Awaited<ReturnType<typeof prepareSkillPackage>>, json: boolean): string {
  const result = {
    ok: true,
    dryRun: true,
    lifecycleState: 'LOCAL_VALIDATED',
    artifactDigest: prepared.artifactDigest,
    versionDigest: prepared.versionDigest,
    fileCount: prepared.manifest.length
  }
  return json
    ? JSON.stringify(result)
    : `本地校验完成\n版本：${prepared.metadata.version}\n文件数：${prepared.manifest.length}\n版本摘要：${prepared.versionDigest}`
}

function publishOutput(
  serviceUrl: string,
  requestId: string,
  fileCount: number,
  upload: PackageImportResult,
  review: ReviewSubmissionResult | undefined,
  json: boolean
): string {
  const consoleUrl = upload.consolePath === undefined ? undefined : `${serviceUrl}${upload.consolePath}`
  const result = { ok: true, requestId, fileCount, upload, review, consoleUrl }
  if (json) return JSON.stringify(result)
  const lines = [
    `本地校验完成，共 ${fileCount} 个文件`,
    '服务端复检完成',
    `上传完成，版本状态：${upload.lifecycleState ?? '未知'}`,
    `版本摘要：${upload.versionDigest}`
  ]
  if (upload.duplicate) lines.push('本次上传命中已有版本，未创建重复数据')
  if (review !== undefined) lines.push(`审核申请已提交，状态：${review.status}`)
  if (consoleUrl !== undefined) lines.push(`控制台地址：${consoleUrl}`)
  return lines.join('\n')
}

function validRequestId(value: string): string {
  const requestId = value.trim()
  if (requestId.length === 0 || requestId.length > 128) {
    throw new CliError('请求标识长度必须为 1 到 128 个字符', 'INVALID_REQUEST_ID', EXIT_CODE.usage)
  }
  return requestId
}
