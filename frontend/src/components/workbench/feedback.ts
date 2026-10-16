/**
 * 工作台轻量提示（toast）与错误文案提取。
 * simple 无 element-plus，用全局 toast 列表由 SkillWorkbenchPage 统一渲染。
 */
import { ref } from 'vue'
import { ObservationApiError } from '../../api/request'

export type WorkbenchToastTone = 'info' | 'success' | 'error'

export interface WorkbenchToast {
  id: number
  message: string
  tone: WorkbenchToastTone
}

export const workbenchToasts = ref<WorkbenchToast[]>([])

let toastSeq = 0

export function notify(message: string, tone: WorkbenchToastTone = 'info'): void {
  const id = ++toastSeq
  workbenchToasts.value = [...workbenchToasts.value, { id, message, tone }]
  window.setTimeout(() => dismissToast(id), 4200)
}

export function dismissToast(id: number): void {
  workbenchToasts.value = workbenchToasts.value.filter((item) => item.id !== id)
}

/** 统一错误文案：优先后端 message + hint，回退通用描述。 */
export function messageOf(err: unknown, fallback = '操作失败'): string {
  if (err instanceof ObservationApiError) {
    return err.hint ? `${err.message}（${err.hint}）` : err.message
  }
  if (err instanceof Error && err.message) return err.message
  return fallback
}
