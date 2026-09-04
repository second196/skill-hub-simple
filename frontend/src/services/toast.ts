import { reactive } from 'vue'

export type ToastTone = 'success' | 'error' | 'warning' | 'info'

export interface ToastMessage {
  id: number
  title: string
  description?: string
  tone: ToastTone
}

const messages = reactive<ToastMessage[]>([])
let sequence = 0

export function useToasts(): { messages: ToastMessage[]; dismiss: (id: number) => void } {
  return { messages, dismiss }
}

export function showToast(title: string, options: { description?: string; tone?: ToastTone; duration?: number } = {}): number {
  const id = ++sequence
  messages.push({ id, title, description: options.description, tone: options.tone ?? 'info' })
  window.setTimeout(() => dismiss(id), options.duration ?? 4000)
  return id
}

export function dismiss(id: number): void {
  const index = messages.findIndex((item) => item.id === id)
  if (index >= 0) messages.splice(index, 1)
}
