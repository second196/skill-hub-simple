import { cp, lstat, mkdir, symlink } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'

export interface AgentSkillTarget {
  agent: string
  path: string
}

const HOME = homedir()

interface AgentDefinition {
  agent: string
  skillPath: string
  /**
   * Existing paths that indicate the Agent is installed. The skill directory
   * itself is always a marker; other markers are only used for known Agent
   * configuration directories.
   */
  markers: string[]
}

const AGENT_DEFINITIONS: AgentDefinition[] = [
  {
    agent: 'codex',
    skillPath: join(HOME, '.agents', 'skills'),
    markers: [join(HOME, '.agents', 'skills'), join(HOME, '.agents')]
  },
  {
    agent: 'codex',
    skillPath: join(HOME, '.codex', 'skills'),
    markers: [join(HOME, '.codex', 'skills'), join(HOME, '.codex')]
  },
  {
    agent: 'claude-code',
    skillPath: join(HOME, '.claude', 'skills'),
    markers: [join(HOME, '.claude', 'skills'), join(HOME, '.claude')]
  },
  {
    agent: 'cursor',
    skillPath: join(HOME, '.cursor', 'skills'),
    markers: [join(HOME, '.cursor', 'skills'), join(HOME, '.cursor')]
  },
  {
    agent: 'qoder',
    skillPath: join(HOME, '.qoder', 'skills'),
    markers: [join(HOME, '.qoder', 'skills'), join(HOME, '.qoder')]
  },
  {
    agent: 'workbuddy',
    skillPath: join(HOME, '.workbuddy', 'skills'),
    markers: [join(HOME, '.workbuddy', 'skills'), join(HOME, '.workbuddy')]
  },
  {
    agent: 'gemini-cli',
    skillPath: join(HOME, '.gemini', 'skills'),
    markers: [join(HOME, '.gemini', 'skills'), join(HOME, '.gemini')]
  },
  {
    agent: 'github-copilot',
    skillPath: join(HOME, '.copilot', 'skills'),
    markers: [join(HOME, '.copilot', 'skills'), join(HOME, '.copilot')]
  },
  {
    agent: 'windsurf',
    skillPath: join(HOME, '.codeium', 'windsurf', 'skills'),
    markers: [join(HOME, '.codeium', 'windsurf', 'skills'), join(HOME, '.codeium', 'windsurf')]
  }
]

export function userSkillStoreRoot(): string {
  if (process.platform === 'win32') {
    return join(process.env.LOCALAPPDATA || join(HOME, 'AppData', 'Local'), 'SkillHub', 'skills')
  }
  if (process.platform === 'darwin') {
    return join(HOME, 'Library', 'Application Support', 'SkillHub', 'skills')
  }
  return join(process.env.XDG_DATA_HOME || join(HOME, '.local', 'share'), 'skillhub', 'skills')
}

export async function installedAgentTargets(): Promise<AgentSkillTarget[]> {
  const existing: AgentSkillTarget[] = []
  const codexSkillPaths = AGENT_DEFINITIONS
    .filter((definition) => definition.agent === 'codex')
    .map((definition) => definition.skillPath)

  // Codex has two conventions in the wild. Prefer the shared ~/.agents path
  // when it exists and only fall back to ~/.codex when that is the active one.
  const codexPath = (await directoryExists(codexSkillPaths[0]))
    ? codexSkillPaths[0]
    : (await directoryExists(codexSkillPaths[1]) ? codexSkillPaths[1] : undefined)

  if (codexPath) existing.push({ agent: 'codex', path: codexPath })

  for (const definition of AGENT_DEFINITIONS.filter((item) => item.agent !== 'codex')) {
    if (await anyPathExists(definition.markers)) {
      existing.push({ agent: definition.agent, path: definition.skillPath })
    }
  }

  return dedupeTargets(existing)
}

export async function exposeSkillToAgent(
  source: string,
  target: AgentSkillTarget,
  slug: string
): Promise<'linked' | 'copied' | 'skipped'> {
  const destination = join(target.path, slug)
  await mkdir(target.path, { recursive: true })
  try {
    const existing = await lstat(destination)
    if (existing.isSymbolicLink()) {
      const { unlink } = await import('node:fs/promises')
      await unlink(destination)
    } else if (existing.isDirectory()) {
      await cp(source, destination, { recursive: true, force: true })
      return 'copied'
    } else {
      return 'skipped'
    }
  } catch (error: unknown) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') return 'skipped'
  }

  try {
    await symlink(source, destination, process.platform === 'win32' ? 'junction' : 'dir')
    return 'linked'
  } catch {
    try {
      await cp(source, destination, { recursive: true, force: true })
      return 'copied'
    } catch {
      return 'skipped'
    }
  }
}

async function directoryExists(path: string): Promise<boolean> {
  try {
    const stat = await lstat(path)
    return stat.isDirectory()
  } catch {
    return false
  }
}

async function anyPathExists(paths: string[]): Promise<boolean> {
  for (const path of paths) {
    if (await directoryExists(path)) return true
  }
  return false
}

function dedupeTargets(targets: AgentSkillTarget[]): AgentSkillTarget[] {
  const seen = new Set<string>()
  return targets.filter((target) => {
    if (seen.has(target.path)) return false
    seen.add(target.path)
    return true
  })
}
