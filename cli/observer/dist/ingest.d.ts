import { fetchPlatformSkills, type PlatformIndex } from './platform.js';
import { type TimelineSession } from './timeline.js';
import type { InstalledSkill, ObservationEvent } from './types.js';
/** SemVer label from step/payload, if any. Digest is intentionally ignored. */
export declare function skillStepVersionLabel(step: ObservationEvent): string | undefined;
export interface IngestResult {
    message: string;
    uploadedSessions: number;
    skippedSessions: number;
    skippedByPlatform: number;
}
export declare function ingestEvents(serviceUrl: string, events: ObservationEvent[]): Promise<IngestResult>;
export declare function sessionInvokesPlatformSkill(session: TimelineSession, platform: Awaited<ReturnType<typeof fetchPlatformSkills>>, index?: PlatformIndex): boolean;
export declare function ingestSessions(serviceUrl: string, sessions: TimelineSession[]): Promise<IngestResult>;
export declare function annotateSessions(sessions: TimelineSession[], platform: Awaited<ReturnType<typeof fetchPlatformSkills>>, installedSkills?: InstalledSkill[]): TimelineSession[];
/** Backfill skill_version_label from installed catalog (SKILL.md or package.json). */
export declare function backfillSkillVersionLabel(step: ObservationEvent, skills: InstalledSkill[]): ObservationEvent;
export declare function toIngestStep(step: ObservationEvent): {
    stepId: string;
    seq: number;
    type: string;
    ts: string;
    skillSlug: string | undefined;
    skillName: string | undefined;
    skillVersionLabel?: string;
    skillVersionSource?: string;
    payload: Record<string, unknown>;
};
export declare function postJson(url: string, body: unknown): Promise<Record<string, unknown>>;
