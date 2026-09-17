export interface FileLock {
    name: string;
    path: string;
    fd: number;
}
export declare function tryAcquireLock(name: string): Promise<FileLock | undefined>;
export declare function acquireLock(name: string, timeoutMs?: number): Promise<FileLock>;
export declare function releaseLock(lock: FileLock | undefined): void;
export declare function heartbeatLock(lock: FileLock): void;
