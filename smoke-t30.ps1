$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
$out = 'D:\program\skill-hub-simple\smoke-results.txt'
$results = New-Object System.Collections.Generic.List[string]
$results.Add("=== T30 Smoke $(Get-Date -Format o) ===")

function Smoke {
  param([string]$Name, [string]$Method, [string]$Url, [string]$Body = $null)
  try {
    $params = @{ Uri = $Url; Method = $Method; TimeoutSec = 20; UseBasicParsing = $true }
    if ($Body) {
      $params.Body = [System.Text.Encoding]::UTF8.GetBytes($Body)
      $params.ContentType = 'application/json; charset=utf-8'
    }
    $resp = Invoke-WebRequest @params
    $code = [int]$resp.StatusCode
    $content = $resp.Content
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
  } catch {
    $code = 0
    $content = ''
    if ($_.Exception.Response) {
      $code = [int]$_.Exception.Response.StatusCode
      try {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $content = $reader.ReadToEnd()
        $reader.Close()
      } catch { $content = $_.Exception.Message }
    } else {
      $content = $_.Exception.Message
    }
  }
  $preview = ($content -replace '\s+', ' ')
  if ($preview.Length -gt 260) { $preview = $preview.Substring(0, 260) + '...' }
  $line = "[$code] $Name => $preview"
  $results.Add($line)
  Write-Host $line
  return @{ Status = $code; Body = $content }
}

# 1) AI config
Smoke 'ai-config GET' GET "$base/api/ai-config" | Out-Null
Smoke 'ai-config PUT' PUT "$base/api/ai-config" '{"model":"gpt-4.1-mini","maxTokens":2000,"temperature":0.2}' | Out-Null
Smoke 'ai-config GET after PUT' GET "$base/api/ai-config" | Out-Null
Smoke 'ai-config models (no endpoint)' GET "$base/api/ai-config/models" | Out-Null

# 2) Upload skill
$skillDir = 'D:\program\skill-hub-simple\tmp-t30-skill'
New-Item -ItemType Directory -Force -Path $skillDir | Out-Null
$md = @"
---
name: t30-smoke-skill
version: 1.0.0
description: T30 smoke test skill
---

# T30 Smoke Skill

## Steps
1. Read the requirement
2. Draft a plan
"@
[System.IO.File]::WriteAllText("$skillDir\SKILL.md", $md, [System.Text.UTF8Encoding]::new($false))
$zip = 'D:\program\skill-hub-simple\tmp-t30-skill.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path "$skillDir\SKILL.md" -DestinationPath $zip -Force
try {
  $form = @{ file = Get-Item $zip }
  $up = Invoke-WebRequest -Uri "$base/api/skills" -Method POST -Form $form -TimeoutSec 30 -UseBasicParsing
  $upCode = [int]$up.StatusCode
  $upBody = $up.Content
} catch {
  $upCode = 0
  $upBody = $_.Exception.Message
  if ($_.Exception.Response) { $upCode = [int]$_.Exception.Response.StatusCode }
}
$upPreview = ($upBody -replace '\s+', ' ')
if ($upPreview.Length -gt 260) { $upPreview = $upPreview.Substring(0,260)+'...' }
$results.Add("[$upCode] skill upload multipart => $upPreview")
Write-Host "[$upCode] skill upload multipart => $upPreview"

$slug = 't30-smoke-skill'
$versionLabel = '1.0.0'
if ($upBody -match '"version_label"\s*:\s*"([^"]+)"') { $versionLabel = $Matches[1] }
if ($upBody -match '"slug"\s*:\s*"([^"]+)"') { $slug = $Matches[1] }

$detailResp = Smoke 'skill detail' GET "$base/api/skills/$slug"
$detail = $detailResp.Body
$versionId = ''
if ($detail -match '"version_id"\s*:\s*(\d+)') { $versionId = $Matches[1] }
elseif ($detail -match '"versionId"\s*:\s*(\d+)') { $versionId = $Matches[1] }
$results.Add("INFO skill slug=$slug version=$versionLabel versionId=$versionId")
Write-Host "INFO skill slug=$slug version=$versionLabel versionId=$versionId"

# 3) Ingest
$ingestBody = @"
{
  "client": { "clientId": "t30-client", "hostname": "win-smoke", "os": "windows" },
  "sessions": [{
    "sessionId": "t30-session-1",
    "clientName": "codex",
    "startedAt": "2026-10-16T00:00:00Z",
    "endedAt": "2026-10-16T00:10:00Z",
    "title": "T30 smoke session",
    "turns": [{
      "turnIndex": 1,
      "userText": "帮我整理需求",
      "startedAt": "2026-10-16T00:01:00Z",
      "steps": [
        { "stepId": "s1", "seq": 1, "type": "assistant", "ts": "2026-10-16T00:01:01Z",
          "payload": { "text": "starting" } },
        { "stepId": "s2", "seq": 2, "type": "skill", "ts": "2026-10-16T00:01:02Z",
          "skillSlug": "$slug", "skillName": "T30 Smoke Skill",
          "skillVersionLabel": "$versionLabel",
          "payload": { "name": "T30 Smoke Skill", "skill_version_label": "$versionLabel" } },
        { "stepId": "s3", "seq": 3, "type": "tool", "ts": "2026-10-16T00:01:03Z",
          "payload": { "tool": "Read", "path": "SKILL.md", "ok": true } }
      ]
    }]
  }]
}
"@
Smoke 'observations ingest' POST "$base/api/observations/ingest" $ingestBody | Out-Null

# 4) Observation + quality
Smoke 'observations overview' GET "$base/api/observations" | Out-Null
Smoke 'observations skills' GET "$base/api/observations/skills" | Out-Null
Smoke 'quality-report GET' GET "$base/api/observations/skills/$slug/quality-report" | Out-Null
Smoke 'quality-report summary' GET "$base/api/observations/skills/$slug/quality-report/summary" | Out-Null
Smoke 'quality-report analyze static' POST "$base/api/observations/skills/$slug/quality-report/analyze" '{"scope":"static"}' | Out-Null
Smoke 'quality-report analyze chain' POST "$base/api/observations/skills/$slug/quality-report/analyze" '{"scope":"chain"}' | Out-Null

# 5) Optimize
Smoke 'optimize-tasks GET' GET "$base/api/observations/skills/$slug/optimize-tasks" | Out-Null
Smoke 'optimize-tasks GET?version' GET "$base/api/observations/skills/$slug/optimize-tasks?version=$versionLabel" | Out-Null
$createBody = '{"mode":"repair","version":"'+$versionLabel+'","runMode":"stepwise","createdBy":"t30-smoke"}'
$created = Smoke 'optimize-tasks POST stepwise' POST "$base/api/observations/skills/$slug/optimize-tasks" $createBody
$taskId = ''
if ($created.Body -match '"id"\s*:\s*(\d+)') { $taskId = $Matches[1] }
$results.Add("INFO optimize taskId=$taskId")
Write-Host "INFO optimize taskId=$taskId"
Smoke 'react-steps GET' GET "$base/api/observations/optimize-tasks/$taskId/react-steps" | Out-Null
Start-Sleep -Seconds 7
Smoke 'optimize-tasks GET after claim' GET "$base/api/observations/skills/$slug/optimize-tasks" | Out-Null
Smoke 'react-steps after claim' GET "$base/api/observations/optimize-tasks/$taskId/react-steps" | Out-Null

$createBody2 = '{"mode":"edit","version":"'+$versionLabel+'","runMode":"auto","createdBy":"t30-smoke"}'
$created2 = Smoke 'optimize-tasks POST auto cancel-target' POST "$base/api/observations/skills/$slug/optimize-tasks" $createBody2
$taskId2 = ''
if ($created2.Body -match '"id"\s*:\s*(\d+)') { $taskId2 = $Matches[1] }
Smoke 'optimize-tasks cancel' POST "$base/api/observations/optimize-tasks/$taskId2/cancel" '{"operator":"t30","reason":"smoke cancel"}' | Out-Null

$listed = Smoke 'optimize-tasks GET for candidates' GET "$base/api/observations/skills/$slug/optimize-tasks"
$candId = ''
if ($listed.Body -match '"candidates"\s*:\s*\[\s*\{\s*"id"\s*:\s*(\d+)') { $candId = $Matches[1] }
if ($candId) {
  Smoke 'optimize-candidate GET' GET "$base/api/observations/optimize-candidates/$candId" | Out-Null
  Smoke 'optimize-candidate decision reject' POST "$base/api/observations/optimize-candidates/$candId/decision" '{"decision":"reject","operator":"t30","reason":"smoke"}' | Out-Null
} else {
  Smoke 'optimize-candidate GET missing' GET "$base/api/observations/optimize-candidates/999999" | Out-Null
  Smoke 'optimize-candidate decision missing' POST "$base/api/observations/optimize-candidates/999999/decision" '{"decision":"adopt"}' | Out-Null
}

# 6) Conversations
$conv = Smoke 'create conversation' POST "$base/api/observations/skills/$slug/versions/$versionId/conversations" '{}'
$convId = ''
if ($conv.Body -match '"id"\s*:\s*(\d+)') { $convId = $Matches[1] }
$results.Add("INFO conversation id=$convId")
Write-Host "INFO conversation id=$convId"
Smoke 'list conversations' GET "$base/api/observations/skills/$slug/conversations" | Out-Null
Smoke 'post message' POST "$base/api/observations/conversations/$convId/messages" '{"content":"帮我优化这个 skill","role":"user"}' | Out-Null
Smoke 'list messages' GET "$base/api/observations/conversations/$convId/messages?after=0" | Out-Null

# SSE via curl.exe with simple args
$sseOut = 'D:\program\skill-hub-simple\t30-sse.txt'
if (Test-Path $sseOut) { Remove-Item $sseOut -Force }
$sseUrl = "$base/api/observations/conversations/$convId/events"
cmd /c "curl.exe -s -N --max-time 4 `"$sseUrl`" > `"$sseOut`" 2>nul"
$sseText = ''
if (Test-Path $sseOut) { $sseText = [System.IO.File]::ReadAllText($sseOut) }
$sseOk = if ($sseText -match 'event:|data:|heartbeat|ping|id:') { 'YES' } else { 'NO' }
$ssePreview = ($sseText -replace '\s+', ' ')
if ($ssePreview.Length -gt 200) { $ssePreview = $ssePreview.Substring(0,200)+'...' }
$results.Add("[$sseOk] SSE GET /conversations/$convId/events => $ssePreview")
Write-Host "[$sseOk] SSE events"

Smoke 'advance NO_WAITING_TASK expected' POST "$base/api/observations/conversations/$convId/advance" '{"note":"please proceed"}' | Out-Null

# 7) Sessions
$sess = Smoke 'sessions list' GET "$base/api/observations/sessions?clientId=t30-client"
$sessionId = ''
if ($sess.Body -match '"session_id"\s*:\s*(\d+)') { $sessionId = $Matches[1] }
elseif ($sess.Body -match '"sessionId"\s*:\s*(\d+)') { $sessionId = $Matches[1] }
elseif ($sess.Body -match '"id"\s*:\s*(\d+)') { $sessionId = $Matches[1] }
$results.Add("INFO sessionId=$sessionId")
Write-Host "INFO sessionId=$sessionId"

if ($sessionId) {
  Smoke 'session GET old' GET "$base/api/observations/sessions/$sessionId" | Out-Null
  Smoke 'session chain old' GET "$base/api/observations/sessions/$sessionId/chain" | Out-Null
  Smoke 'session dashboard' GET "$base/api/observations/sessions/$sessionId/dashboard" | Out-Null
  Smoke 'session dashboard node' GET "$base/api/observations/sessions/$sessionId/dashboard/node?nodeId=root" | Out-Null
  Smoke 'session analysis' GET "$base/api/observations/sessions/$sessionId/analysis" | Out-Null
}
Smoke 'search' GET "$base/api/observations/search?q=%E9%9C%80%E6%B1%82&limit=10" | Out-Null
Smoke 'skill versions' GET "$base/api/observations/skills/$slug/versions" | Out-Null

$results | Out-File -Encoding utf8 $out
Write-Host "Saved to $out"
