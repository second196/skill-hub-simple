import type { ClientName, ObservationEvent } from './types.js';
export interface TimelineTurn {
    turnIndex: number;
    startedAt: string;
    userText: string;
    steps: ObservationEvent[];
}
export interface TimelineSession {
    sessionId: string;
    clientId: string;
    clientName: ClientName | string;
    startedAt: string;
    endedAt: string;
    turns: TimelineTurn[];
}
export interface SkillStat {
    slug: string;
    name: string;
    callCount: number;
    sessionCount: number;
    clientCount: number;
    lastUsedAt: string;
    tokenTotal: number;
    tokenInput: number;
    tokenCacheRead: number;
    tokenCacheWrite: number;
    tokenOutput: number;
    tokenRequests: number;
    turnsWithTokens: number;
}
export declare function canonicalEvents(events: ObservationEvent[]): ObservationEvent[];
export declare function buildTimeline(events: ObservationEvent[]): TimelineSession[];
export declare function skillStats(events: ObservationEvent[]): SkillStat[];
export declare function trendCounts(events: ObservationEvent[], days?: number): Array<{
    day: string;
    count: number;
}>;
export declare function firstUserText(steps: ObservationEvent[]): string;
export declare function normalizeSkillKey(value: string | undefined): string;
