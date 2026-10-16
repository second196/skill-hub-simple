/** 候选人审决策共享动作（候选卡与 diff 抽屉共用）。 */
import { decideCandidate } from '../../api/observation'
import type { SkillOptimizeCandidate } from '../../types/skillOptimize'
import { messageOf, notify } from './feedback'

/** 采纳（原生确认后提交）；取消返回 null。 */
export async function adoptCandidate(
  id: number | string
): Promise<SkillOptimizeCandidate | null> {
  if (!window.confirm('确认采纳该候选并生成新版本？')) return null
  try {
    const updated = await decideCandidate(id, { decision: 'adopt' })
    notify(
      updated.resultVersionLabel
        ? `已采纳，生成新版本 ${updated.resultVersionLabel}`
        : '已采纳该候选',
      'success')
    return updated
  } catch (err) {
    notify(messageOf(err, '采纳失败'), 'error')
    return null
  }
}

/** 不采纳（可选原因）；取消返回 null。 */
export async function rejectCandidate(
  id: number | string,
  reason?: string
): Promise<SkillOptimizeCandidate | null> {
  try {
    const updated = await decideCandidate(id, {
      decision: 'reject',
      reason: reason?.trim() ? reason.trim() : undefined
    })
    notify('已记录：不采纳该候选', 'success')
    return updated
  } catch (err) {
    notify(messageOf(err, '操作失败'), 'error')
    return null
  }
}
