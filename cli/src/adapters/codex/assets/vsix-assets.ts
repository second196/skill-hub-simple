export const CODEX_EXTENSION_ID = 'kmsoft.skillhub-codex-trace'

export const VSIX_PACKAGE = {
  name: 'skillhub-codex-trace',
  displayName: '金现代 SkillHub Codex 运行观测',
  description: '将 Codex 编辑器运行事件发送到本机 SkillHub 采集器。',
  version: '0.1.0',
  publisher: 'kmsoft',
  engines: { vscode: '^1.93.0' },
  categories: ['Other'],
  activationEvents: ['onStartupFinished'],
  main: './extension.cjs'
}

export const VSIX_EXTENSION = `'use strict'
const crypto = require('node:crypto')
const fs = require('node:fs')
const http = require('node:http')
const os = require('node:os')
const path = require('node:path')

const COLLECTOR_HOST = '127.0.0.1'
const configuredPort = Number(process.env.SKILLHUB_COLLECTOR_PORT)
const COLLECTOR_PORT = Number.isSafeInteger(configuredPort) && configuredPort >= 1 && configuredPort <= 65535
  ? configuredPort : 43191
const REQUEST_TIMEOUT_MS = 150
const MAX_QUEUE_SIZE = 128
const MAX_BATCH_SIZE = 8
const SECRET_PATTERN = /^[A-Za-z0-9_-]{43}$/
let activeClient

function activate(context) {
  const vscode = require('vscode')
  const client = createEventClient(detectRuntimeKey(vscode))
  activeClient = client
  const subscriptions = []
  const subscribe = (event, handler) => {
    if (typeof event !== 'function') return
    const disposable = event(handler)
    if (disposable && typeof disposable.dispose === 'function') {
      subscriptions.push(disposable)
      context.subscriptions.push(disposable)
    }
  }

  subscribe(vscode.workspace && vscode.workspace.onDidChangeTextDocument, (documentEvent) => {
    client.emit('document_changed', {
      languageId: typeof documentEvent.languageId === 'string' ? documentEvent.languageId : 'unknown',
      changeCount: Array.isArray(documentEvent.contentChanges) ? documentEvent.contentChanges.length : 0
    })
  })
  subscribe(vscode.window && vscode.window.onDidOpenTerminal, () => {
    client.emit('terminal_opened', { terminalType: 'integrated' })
  })
  subscribe(vscode.window && vscode.window.onDidCloseTerminal, () => {
    client.emit('terminal_closed', { terminalType: 'integrated' })
  })
  subscribe(vscode.tasks && vscode.tasks.onDidStartTask, () => {
    client.emit('task_started', { taskType: 'editor-task' })
  })
  subscribe(vscode.tasks && vscode.tasks.onDidEndTask, () => {
    client.emit('task_ended', { taskType: 'editor-task' })
  })
  client.emit('extension_activated', {})
  client.setDisposables(subscriptions)
}

async function deactivate() {
  const client = activeClient
  activeClient = undefined
  if (client) await client.deactivate()
}

function createEventClient(runtimeKey) {
  const sessionId = crypto.randomUUID()
  const queue = []
  let sequence = 0
  let active = true
  let inFlight = false
  let disposables = []

  function emit(type, fields) {
    const event = {
      schema: 'skillhub.editor.event.v1',
      runtimeKey,
      sessionId,
      eventId: crypto.createHash('sha256').update(runtimeKey + ':' + sessionId + ':' + sequence).digest('hex'),
      type,
      timestamp: new Date().toISOString(),
      sequence: sequence++,
      editorType: runtimeKey,
      ...fields
    }
    if (queue.length >= MAX_QUEUE_SIZE) queue.shift()
    queue.push(event)
    void flush()
  }

  function setDisposables(value) {
    disposables = value
  }

  async function deactivateClient() {
    if (!active) return
    emit('extension_deactivated', {})
    active = false
    await flush()
    queue.length = 0
    for (const disposable of disposables.splice(0)) {
      try { disposable.dispose() } catch (_error) {}
    }
  }

  function flush() {
    if (inFlight || queue.length === 0) return Promise.resolve()
    inFlight = true
    const batch = queue.splice(0, MAX_BATCH_SIZE)
    return new Promise((resolve) => {
      fs.readFile(path.join(os.homedir(), '.skillhub', 'collector', 'collector.secret'), 'utf8', (readError, value) => {
        if (readError || !SECRET_PATTERN.test(value.trim())) {
          inFlight = false
          resolve()
          return
        }
        const request = http.request({
          hostname: COLLECTOR_HOST,
          port: COLLECTOR_PORT,
          path: '/ide-event',
          method: 'POST',
          timeout: REQUEST_TIMEOUT_MS,
          headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(JSON.stringify(batch)),
            'X-SkillHub-Collector-Key': value.trim(),
            'X-SkillHub-Runtime-Key': runtimeKey
          }
        }, (response) => {
          response.resume()
          response.once('end', finish)
        })
        let settled = false
        const finish = () => {
          if (settled) return
          settled = true
          inFlight = false
          resolve()
          if (queue.length > 0) setImmediate(() => { void flush() })
        }
        request.on('error', finish)
        request.on('timeout', () => request.destroy())
        request.end(JSON.stringify(batch))
      })
    })
  }

  return { emit, setDisposables, deactivate: deactivateClient }
}

function detectRuntimeKey(vscode) {
  const appName = String(vscode.env && vscode.env.appName || '').toLowerCase()
  if (appName.indexOf('cursor') >= 0) return 'cursor'
  if (appName.indexOf('windsurf') >= 0) return 'windsurf'
  return 'vscode'
}

module.exports = { activate, deactivate }
`

export const VSIX_MANIFEST = `<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">
  <Metadata><Identity Language="zh-CN" Id="skillhub-codex-trace" Version="0.1.0" Publisher="kmsoft" />
  <DisplayName>金现代 SkillHub Codex 运行观测</DisplayName><Description xml:space="preserve">Codex 编辑器运行观测接入</Description></Metadata>
  <Installation><InstallationTarget Id="Microsoft.VisualStudio.Code" Version="[1.93.0,)" /></Installation>
  <Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" /></Assets>
</PackageManifest>
`

export const VSIX_CONTENT_TYPES = `<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="json" ContentType="application/json" />
  <Default Extension="cjs" ContentType="application/javascript" />
  <Default Extension="vsixmanifest" ContentType="text/xml" />
</Types>
`
