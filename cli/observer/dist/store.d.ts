import type { ClientName, ObservationEvent, SpoolJob, UploadState } from './types.js';
export declare const UPLOAD_CONTRACT_VERSION = 2;
export declare function ensureStore(): Promise<void>;
export declare function loadClientId(): Promise<string>;
export declare function hostMeta(): {
    hostname: string;
    os: string;
};
export declare function fingerprint(event: ObservationEvent): string;
export declare function appendEvents(events: ObservationEvent[]): Promise<number>;
export declare function mergeEvents(events: ObservationEvent[]): Promise<number>;
export declare function snapshotEvents(events: ObservationEvent[]): Promise<void>;
export declare function readEvents(): Promise<ObservationEvent[]>;
export declare function readSessionEvents(clientName: ClientName | string, sessionId: string): Promise<ObservationEvent[]>;
export declare function enqueueSession(input: {
    clientName: ClientName;
    sessionId: string;
    sourcePath?: string;
    snapshotEvents?: ObservationEvent[];
    ended: boolean;
    sourceMtimeMs?: number;
    contentHash?: string;
    sourceHash?: string;
}): Promise<SpoolJob>;
export declare function listSpoolJobs(): Promise<SpoolJob[]>;
export declare function readSpoolJob(safeId: string): Promise<SpoolJob | undefined>;
export declare function writeSpoolJob(job: SpoolJob): Promise<void>;
export declare function deleteSpoolJob(safeId: string): Promise<void>;
export declare function loadState(): Promise<UploadState>;
export declare function saveState(state: UploadState): Promise<void>;
export declare function ackSession(job: SpoolJob, uploadedAt?: string): Promise<{
    deleted: boolean;
    reason?: string;
}>;
export declare function hashEvents(events: ObservationEvent[]): string;
export declare function writeJson(path: string, value: unknown): Promise<void>;
export declare function readJson<T>(path: string): Promise<T | undefined>;
