import { fetchPlatformSkills } from './platform.js';
import { type TimelineSession } from './timeline.js';
import type { ObservationEvent } from './types.js';
export declare function ingestEvents(serviceUrl: string, events: ObservationEvent[]): Promise<string>;
export declare function ingestSessions(serviceUrl: string, sessions: TimelineSession[]): Promise<string>;
export declare function annotateSessions(sessions: TimelineSession[], platform: Awaited<ReturnType<typeof fetchPlatformSkills>>): TimelineSession[];
export declare function postJson(url: string, body: unknown): Promise<Record<string, unknown>>;
