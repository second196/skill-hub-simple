/**
 * 工作台 Skill 读取辅助（api/ 已交付文件不可改，此处为新文件内的局部封装，
 * 只覆盖工作台需要的技能列表 / 详情 / 文件预览端点）。
 */
import { request } from '../../api/request'

/** 技能列表行（/api/skills?status=ACTIVE）。 */
export interface WorkbenchSkillRow {
  id: number
  slug: string
  name: string
  description: string
  category: string
  status: string
  version_label: string
  download_count?: number
}

/** 技能版本行（详情内嵌）。 */
export interface WorkbenchVersionRow {
  version_label: string
  created_at: string
}

/** 技能详情（含最新版本 id 与版本列表）。 */
export interface WorkbenchSkillDetail extends WorkbenchSkillRow {
  version_id: number
  versions?: WorkbenchVersionRow[]
}

/** 技能文件行。 */
export interface WorkbenchSkillFile {
  path: string
  content_type?: string
  size_bytes?: number
}

/** 活跃技能列表（工作台选择器数据源）。 */
export function listWorkbenchSkills(): Promise<WorkbenchSkillRow[]> {
  const params = new URLSearchParams({ status: 'ACTIVE' })
  return request<WorkbenchSkillRow[]>(`/api/skills?${params}`)
}

/** 技能详情（版本选择器与详情页签数据源）。 */
export function getWorkbenchSkill(slug: string): Promise<WorkbenchSkillDetail> {
  return request<WorkbenchSkillDetail>(`/api/skills/${encodeURIComponent(slug)}`)
}

/** 指定版本的文件列表。 */
export function getWorkbenchSkillFiles(
  slug: string,
  version: string
): Promise<WorkbenchSkillFile[]> {
  const params = new URLSearchParams({ version })
  return request<WorkbenchSkillFile[]>(
    `/api/skills/${encodeURIComponent(slug)}/files?${params}`)
}

/** 读取文件文本（概览预览）。 */
export async function readWorkbenchFileText(
  slug: string,
  version: string,
  path: string
): Promise<string> {
  const params = new URLSearchParams({ version, path })
  const response = await fetch(
    `/api/skills/${encodeURIComponent(slug)}/files/content?${params}`)
  if (!response.ok) throw new Error('文件读取失败')
  return response.text()
}
