import type { ClientName } from './types.js';
export declare function sessionSafeId(clientName: ClientName | string, sessionId: string): string;
export declare function ingestSessionKey(clientName: ClientName | string, sessionId: string): string;
export declare function sessionStateKey(clientName: ClientName | string, sessionId: string): string;
