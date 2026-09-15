import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { spawn } from 'node:child_process'
import { platform } from 'node:os'
import { collectEvents } from './collect.js'
import { observabilityDir } from './paths.js'
import { fetchPlatformSkills, platformIndex } from './platform.js'
import { buildTimeline, skillStats, trendCounts, type TimelineSession } from './timeline.js'
import type { ObservationEvent, PlatformSkill } from './types.js'

export async function writeReport(options: {
  output?: string
  open?: boolean
  serviceUrl?: string
}): Promise<string> {
  const events = await collectEvents()
  let platform: PlatformSkill[] = []
  if (options.serviceUrl) {
    try {
      platform = await fetchPlatformSkills(options.serviceUrl)
    } catch (error) {
      platform = []
      process.stderr.write(`未能读取平台Skill列表，报告将标记为未对照平台：${error instanceof Error ? error.message : String(error)}\n`)
    }
  }
  const html = renderReport(events, platform, Boolean(options.serviceUrl))
  const output = resolve(options.output || `${observabilityDir()}/report.html`)
  await mkdir(dirname(output), { recursive: true })
  await writeFile(output, html, 'utf8')
  if (options.open) await openFile(output)
  return output
}

export function renderReport(events: ObservationEvent[], platform: PlatformSkill[], compared: boolean): string {
  const sessions = buildTimeline(events)
  const skills = skillStats(events)
  const trend = trendCounts(events)
  const index = platformIndex(platform)
  const skillCount = skills.length
  const callCount = skills.reduce((sum, item) => sum + item.callCount, 0)
  const sessionCount = sessions.length
  const clientCount = new Set(sessions.map((item) => item.clientId)).size
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>SkillHub 本地观测报告</title>
  <style>${reportCss()}</style>
</head>
<body>
  <header class="hero">
    <p class="kicker">SkillHub Observer</p>
    <h1>本地Skill观测报告</h1>
    <p class="copy">包含本机全部会话、回合和助手回复。上传到平台时同样保留完整原文，不只上传平台Skill。</p>
    <p class="meta">生成时间 ${escapeHtml(new Date().toISOString())} · 事件 ${events.length} 条</p>
  </header>
  <section class="kpis" aria-label="汇总指标">
    ${kpi('已观测Skill', skillCount)}
    ${kpi('Skill调用', callCount)}
    ${kpi('会话', sessionCount)}
    ${kpi('客户端', clientCount)}
  </section>
  <section class="card">
    <div class="card-head">
      <h2>近 7 日调用趋势</h2>
      <p>按Skill调用次数统计。</p>
    </div>
    ${lineChart(trend)}
    <table class="a11y-table">
      <caption>近 7 日调用次数</caption>
      <thead><tr><th>日期</th><th>次数</th></tr></thead>
      <tbody>${trend.map((point) => `<tr><td>${escapeHtml(point.day)}</td><td>${point.count}</td></tr>`).join('')}</tbody>
    </table>
  </section>
  <section class="card">
    <div class="card-head">
      <h2>Skill列表</h2>
      <p>${compared ? '已对照平台Skill目录。' : '未提供 --service-url，无法判断是否可上传。'}</p>
    </div>
    <div class="skill-list">
      ${skills.length ? skills.map((skill) => {
        const uploadable = compared && (index.slugs.has(skill.slug) || Boolean(index.slugByKey.get(normalize(skill.name))))
        const badge = !compared ? '未对照平台' : uploadable ? '平台已收录' : '仅本地'
        return `<article class="skill-row">
          <div>
            <h3>${escapeHtml(skill.name)}</h3>
            <code>${escapeHtml(skill.slug)}</code>
          </div>
          <p>${skill.callCount} 次调用 · ${skill.sessionCount} 个会话</p>
          <span class="badge ${uploadable ? 'ok' : 'muted'}">${badge}</span>
        </article>`
      }).join('') : '<p class="empty">还没有采集到Skill调用。</p>'}
    </div>
  </section>
  ${sessions.map((session) => renderSession(session)).join('')}
</body>
</html>`
}

function renderSession(session: TimelineSession): string {
  return `<section class="card session">
    <details open>
      <summary>
        <strong>${escapeHtml(labelClient(session.clientName))}</strong>
        <span>${escapeHtml(session.sessionId)}</span>
        <span>${escapeHtml(session.startedAt || '')}</span>
      </summary>
      ${session.turns.map((turn) => `
        <article class="turn">
          <header>
            <span class="pill">Turn ${turn.turnIndex}</span>
            <time>${escapeHtml(turn.startedAt)}</time>
          </header>
          <div class="user">${turn.userText ? escapeHtml(turn.userText) : '（无用户原文）'}</div>
          <ol class="chain">
            ${turn.steps.map((step) => `<li class="step ${step.type}">
              <span class="type">${escapeHtml(step.type)}</span>
              <div>
                <p class="step-title">${escapeHtml(stepTitle(step))}</p>
                <pre>${escapeHtml(stepBody(step))}</pre>
              </div>
            </li>`).join('')}
          </ol>
        </article>
      `).join('')}
    </details>
  </section>`
}

function stepTitle(step: ObservationEvent): string {
  if (step.type === 'user') return '用户输入'
  if (step.type === 'assistant') return '助手回复'
  if (step.type === 'skill') return String(step.payload.name || step.skill_name || step.skill_slug || 'Skill')
  if (step.type === 'document') return String(step.payload.path || '文档')
  return String(step.payload.name || '工具')
}

function stepBody(step: ObservationEvent): string {
  if (step.type === 'user' || step.type === 'assistant') return String(step.payload.text || '')
  if (step.type === 'document') return String(step.payload.content || '')
  try {
    return JSON.stringify(step.payload, null, 2)
  } catch {
    return String(step.payload)
  }
}

function labelClient(name: string): string {
  if (name === 'claude-code') return 'Claude Code'
  if (name === 'codex') return 'Codex'
  return name
}

function kpi(label: string, value: number): string {
  return `<article class="kpi"><span>${escapeHtml(label)}</span><strong>${value}</strong></article>`
}

function lineChart(points: Array<{ day: string; count: number }>): string {
  const width = 720
  const height = 180
  const max = Math.max(1, ...points.map((point) => point.count))
  const coords = points.map((point, index) => {
    const x = points.length === 1 ? width / 2 : (index / (points.length - 1)) * (width - 32) + 16
    const y = height - 24 - (point.count / max) * (height - 48)
    return `${x},${y}`
  })
  const labels = points.map((point, index) => {
    const x = points.length === 1 ? width / 2 : (index / (points.length - 1)) * (width - 32) + 16
    return `<text x="${x}" y="${height - 6}" text-anchor="middle">${escapeHtml(point.day.slice(5))} · ${point.count}</text>`
  }).join('')
  return `<svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="近七日Skill调用趋势">
    <polyline fill="none" stroke="#315cff" stroke-width="3" points="${coords.join(' ')}"></polyline>
    ${coords.map((point) => `<circle cx="${point.split(',')[0]}" cy="${point.split(',')[1]}" r="4" fill="#315cff"></circle>`).join('')}
    ${labels}
  </svg>`
}

function reportCss(): string {
  return `
    :root { font-family: "Plus Jakarta Sans", Inter, "PingFang SC", sans-serif; color: #14213d; background: #f6f8fc; }
    body { max-width: 1100px; margin: 0 auto; padding: 48px 24px 80px; }
    .hero h1 { margin: 0; font-size: 42px; letter-spacing: -.05em; }
    .kicker { color: #315cff; font-size: 12px; font-weight: 800; letter-spacing: .16em; text-transform: uppercase; }
    .copy, .meta, .card-head p { color: #667085; line-height: 1.7; }
    .kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin: 28px 0; }
    .step.assistant .type { color: #0f766e; background: #ecfdf5; }
    .step.assistant pre { background: #f0fdf8; border-color: #bbf7d0; }
    .kpi, .card { background: #fff; border: 1px solid #e5e9f2; border-radius: 20px; box-shadow: 0 1px 2px rgba(20,33,61,.04); }
    .kpi { padding: 22px; display: grid; gap: 8px; }
    .kpi strong { font-size: 32px; letter-spacing: -.04em; }
    .card { padding: 24px; margin: 18px 0; }
    .skill-row { display: grid; grid-template-columns: minmax(0,1fr) auto auto; gap: 16px; align-items: center; padding: 16px 0; border-bottom: 1px solid #edf0f7; }
    .badge { padding: 6px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; background: #eef3ff; color: #315cff; }
    .badge.muted { background: #f3f4f6; color: #667085; }
    .user, pre { background: #f3f6ff; border: 1px solid #dce5ff; border-radius: 14px; padding: 14px 16px; white-space: pre-wrap; overflow: auto; }
    .chain { list-style: none; padding: 0; display: grid; gap: 12px; }
    .step { display: grid; grid-template-columns: 88px minmax(0,1fr); gap: 12px; }
    .type { height: fit-content; padding: 4px 8px; border-radius: 999px; background: #eef2fa; font-size: 12px; font-weight: 700; text-align: center; }
    .a11y-table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
    .a11y-table th, .a11y-table td { text-align: left; padding: 6px 0; border-bottom: 1px solid #edf0f7; }
    .chart { width: 100%; height: 180px; }
    .empty { color: #667085; }
    @media (max-width: 760px) { .kpis, .skill-row, .step { grid-template-columns: 1fr; } }
  `
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char] || char))
}

function normalize(value: string): string {
  return value.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

async function openFile(path: string): Promise<void> {
  const command = platform() === 'darwin' ? 'open' : platform() === 'win32' ? 'cmd' : 'xdg-open'
  const args = platform() === 'win32' ? ['/c', 'start', '', path] : [path]
  spawn(command, args, { detached: true, stdio: 'ignore' }).unref()
}
