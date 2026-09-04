export const CODEX_HOOK_EVENTS = [
  'SessionStart',
  'SessionEnd',
  'UserPromptSubmit',
  'PreToolUse',
  'PostToolUse',
  'PreCompact',
  'PostCompact',
  'SubagentStart',
  'SubagentStop',
  'PermissionRequest',
  'Stop'
] as const

export const CODEX_HOOK_HANDLER = `#!/usr/bin/env node
'use strict'
const fs = require('node:fs')
const http = require('node:http')
const os = require('node:os')
const path = require('node:path')
const chunks = []
let bytes = 0
let tooLarge = false
process.stdin.on('data', (chunk) => {
  const value = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
  bytes += value.length
  if (bytes > 1024 * 1024) {
    tooLarge = true
    return
  }
  chunks.push(value)
})
process.stdin.on('end', () => {
  if (tooLarge) return process.exit(0)
  const secretPath = path.join(os.homedir(), '.skillhub', 'collector', 'collector.secret')
  fs.readFile(secretPath, 'utf8', (error, value) => {
    const secret = error ? '' : value.trim()
    if (!/^[A-Za-z0-9_-]{43}$/.test(secret)) return process.exit(0)
    const source = Buffer.concat(chunks)
    const request = http.request({
      hostname: '127.0.0.1',
      port: 43191,
      path: '/hook',
      method: 'POST',
      timeout: 150,
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': source.length,
        'X-SkillHub-Collector-Key': secret
      }
    }, (response) => { response.resume(); response.on('end', () => process.exit(0)) })
    request.on('error', () => process.exit(0))
    request.on('timeout', () => { request.destroy(); process.exit(0) })
    request.end(source)
  })
})
process.stdin.on('error', () => process.exit(0))
`

export const CODEX_COLLECTOR_README = `# 金现代 SkillHub Codex Collector

本目录由 SkillHub CLI 管理。Hook 仅向本机回环地址发送事件，采集器不可用时不会阻断 Codex。
`
