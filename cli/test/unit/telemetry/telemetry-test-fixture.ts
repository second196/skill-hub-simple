import type { CanonicalRuntimeEvent } from '../../../src/telemetry/model/canonical-runtime-event.js'

export function canonicalEvent(eventId = 'event-1'): CanonicalRuntimeEvent {
  return {
    schemaVersion: '1.0', eventId, eventType: 'SKILL_INVOCATION', occurredAt: '2026-09-03T08:00:00.000Z',
    scopeId: 1, runtimeKey: 'codex-cli', runtimeVersion: '0.151.0', trackerVersion: '0.1.0',
    sessionId: 'session-1', traceId: 'a'.repeat(32), spanId: eventId.padEnd(16, 'b').slice(0, 16),
    sequence: 1, skillName: 'demo-skill', invocationId: `invocation-${eventId}`,
    status: 'SUCCEEDED', durationMs: 25, attributes: {}, missingFields: ['versionDigest'], privacyActions: []
  }
}
