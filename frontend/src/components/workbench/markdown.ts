/** 工作台 markdown 渲染（对齐 App.vue renderMarkdownContent，拆出复用）。 */
import DOMPurify from 'dompurify'
import { marked } from 'marked'

export function isMarkdownPath(path?: string): boolean {
  return !!path && /\.(?:md|markdown|mdx)$/i.test(path)
}

export function stripFrontmatter(value: string): string {
  const normalized = value.replace(/^\uFEFF/, '')
  if (!normalized.startsWith('---')) return value
  const closing = normalized.search(/\r?\n(?:---|\.\.\.)\r?\n/)
  return closing < 0 ? value : normalized.slice(closing).replace(/^\r?\n(?:---|\.\.\.)\r?\n/, '')
}

export function renderMarkdown(value: string): string {
  return DOMPurify.sanitize(marked.parse(stripFrontmatter(value), { breaks: true }) as string)
}
