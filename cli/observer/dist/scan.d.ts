import type { ClientName, ObservationEvent } from './types.js';
interface ScanOptions {
    sessionId?: string;
    sinceMs?: number;
}
export declare function scanAll(options?: ScanOptions): Promise<ObservationEvent[]>;
export declare function scanSessionFile(clientName: ClientName, filePath: string): Promise<ObservationEvent[]>;
export interface SessionSource {
    clientName: ClientName;
    sessionId: string;
    path: string;
    mtimeMs: number;
}
export declare function listSessionSources(options?: ScanOptions): Promise<SessionSource[]>;
export declare function findSessionSource(clientName: ClientName, sessionId: string, hintPath?: string, knownSources?: SessionSource[]): Promise<SessionSource | undefined>;
export declare function sessionMatches(wanted: string, found: string, file: string, clientName: ClientName): boolean;
export {};
