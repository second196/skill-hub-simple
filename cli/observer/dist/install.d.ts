interface HookItem {
    type?: string;
    command?: string;
    commandWindows?: string;
    [key: string]: unknown;
}
interface HookEntry {
    matcher?: string;
    hooks?: HookItem[];
}
export interface HookSpec {
    command: string;
    matcher?: string;
}
export declare function isOurObserverHook(command: string): boolean;
export declare function rewriteEventHooks(entries: HookEntry[] | undefined, specs: HookSpec[]): HookEntry[];
export declare function installHooks(options?: {
    serviceUrl?: string;
}): Promise<string>;
export {};
