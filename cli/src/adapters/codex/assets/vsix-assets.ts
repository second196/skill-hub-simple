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
const http = require('node:http')
function activate(context) {
  const timer = setInterval(() => {
    const request = http.request({ hostname: '127.0.0.1', port: 43191, path: '/status', timeout: 300 },
      (response) => response.resume())
    request.on('error', () => {})
    request.on('timeout', () => request.destroy())
    request.end()
  }, 30000)
  context.subscriptions.push({ dispose: () => clearInterval(timer) })
}
function deactivate() {}
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
