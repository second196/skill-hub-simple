import { lstat, readdir, readlink, rm } from 'node:fs/promises'
import { join, resolve, sep } from 'node:path'
import { installedAgentTargets, userSkillStoreRoot, type AgentSkillTarget } from '../platform/agent-paths.js'

export interface UninstallSkillOptions {
  slugs?: string[]
  all?: boolean
  target?: string
  json: boolean
}

interface RemovedSkill {
  slug: string
  storePath: string
  agentLinks: Array<{ agent: string; path: string; mode: 'removed' | 'missing' | 'skipped' }>
}

export async function uninstallCommand(options: UninstallSkillOptions): Promise<string> {
  if (!options.all && !(options.slugs?.length)) {
    throw new Error('请指定 <slug...> 或 --all')
  }
  if (options.all && options.slugs?.length) {
    throw new Error('--all 与具体 slug 不能同时使用')
  }

  const storeRoot = options.target ? resolve(options.target) : userSkillStoreRoot()
  const slugs = options.all ? await listStoreSlugs(storeRoot) : (options.slugs || [])
  if (options.all && !slugs.length) {
    const linked = options.target ? [] : await removeLinkedOnlySkills(storeRoot)
    return options.json
      ? JSON.stringify({ ok: true, all: true, skills: [], linkedOnly: linked })
      : `用户技能库为空${linked.length ? `，已清理指向 SkillHub 的 Agent 链接 ${linked.length} 个` : ''}`
  }
  if (!slugs.length) {
    throw new Error('未找到可卸载的技能')
  }

  const targets = options.target ? [] : await installedAgentTargets()
  const removed: RemovedSkill[] = []
  for (const slug of slugs) {
    removed.push(await removeSkill(storeRoot, slug, targets))
  }

  return options.json
    ? JSON.stringify({ ok: true, all: Boolean(options.all), skills: removed })
    : removed.map((item) => formatRemoved(item)).join('\n')
}

async function listStoreSlugs(storeRoot: string): Promise<string[]> {
  try {
    const entries = await readdir(storeRoot, { withFileTypes: true })
    return entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort()
  } catch {
    return []
  }
}

async function removeSkill(storeRoot: string, slug: string, targets: AgentSkillTarget[]): Promise<RemovedSkill> {
  const storePath = join(storeRoot, slug)
  let hadStore = false
  try {
    await lstat(storePath)
    hadStore = true
  } catch {
    hadStore = false
  }
  await rm(storePath, { recursive: true, force: true })
  const agentLinks: RemovedSkill['agentLinks'] = []
  for (const target of targets) {
    agentLinks.push(await removeAgentSkill(target.path, slug, storeRoot, hadStore))
  }
  return { slug, storePath, agentLinks }
}

async function removeAgentSkill(
  agentSkillsDir: string,
  slug: string,
  storeRoot: string,
  hadStore: boolean
): Promise<RemovedSkill['agentLinks'][number]> {
  const destination = join(agentSkillsDir, slug)
  let mode: 'removed' | 'missing' | 'skipped' = 'missing'
  try {
    const stat = await lstat(destination)
    if (stat.isSymbolicLink()) {
      const link = await readlink(destination)
      const resolved = resolve(destination, link)
      if (isInsideStore(resolved, storeRoot, slug)) {
        await rm(destination, { force: true })
        mode = 'removed'
      } else {
        mode = 'skipped'
      }
    } else if (stat.isDirectory() && hadStore) {
      await rm(destination, { recursive: true, force: true })
      mode = 'removed'
    } else {
      mode = 'skipped'
    }
  } catch {
    mode = 'missing'
  }
  return { agent: agentSkillsDir, path: destination, mode }
}

async function removeLinkedOnlySkills(storeRoot: string): Promise<string[]> {
  const targets = await installedAgentTargets()
  const removed: string[] = []
  for (const target of targets) {
    let entries: string[] = []
    try {
      entries = await readdir(target.path)
    } catch {
      continue
    }
    for (const slug of entries) {
      const destination = join(target.path, slug)
      try {
        const stat = await lstat(destination)
        if (!stat.isSymbolicLink()) continue
        const link = await readlink(destination)
        const resolved = resolve(destination, link)
        if (isInsideStore(resolved, storeRoot, slug)) {
          await rm(destination, { force: true })
          removed.push(slug)
        }
      } catch {
        // ignore broken entries
      }
    }
  }
  return [...new Set(removed)]
}

function isInsideStore(resolvedLink: string, storeRoot: string, slug: string): boolean {
  const root = resolve(storeRoot)
  const target = resolve(join(storeRoot, slug))
  return resolvedLink === target || resolvedLink.startsWith(root.endsWith(sep) ? root : root + sep)
}

function formatRemoved(item: RemovedSkill): string {
  const links = item.agentLinks.filter((link) => link.mode === 'removed')
  return [
    `已卸载技能：${item.slug}`,
    `目录：${item.storePath}`,
    links.length ? `已移除 Agent 入口：${links.map((link) => link.path).join('、')}` : '未发现需要移除的 Agent 入口'
  ].join('\n')
}
