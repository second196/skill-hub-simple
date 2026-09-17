export declare function drainQueue(options?: {
    serviceUrl?: string;
    reconcile?: boolean;
}): Promise<string>;
export declare function hashFile(path: string): Promise<string>;
export declare function spawnDrain(args?: string[]): void;
