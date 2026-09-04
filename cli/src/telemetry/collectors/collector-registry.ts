import { ClaudeOtlpCollector } from './claude/claude-otlp-collector.js'
import { CollectorRegistry } from './collector.js'
import { CodexHookCollector } from './codex/codex-hook-collector.js'
import { CodexOtelLogCollector } from './codex/codex-otel-log-collector.js'
import { EditorExtensionCollector } from './editor/editor-extension-collector.js'

/** 创建首期固定白名单 Collector 注册表。 */
export function createDefaultCollectorRegistry(): CollectorRegistry {
  return new CollectorRegistry([
    new CodexHookCollector(),
    new CodexOtelLogCollector(),
    new EditorExtensionCollector('vscode'),
    new EditorExtensionCollector('cursor'),
    new EditorExtensionCollector('windsurf'),
    new ClaudeOtlpCollector()
  ])
}
