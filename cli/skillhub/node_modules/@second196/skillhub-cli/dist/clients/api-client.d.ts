export declare function serviceUrl(value: string): string;
export declare function apiRequest<T>(base: string, path: string, init?: RequestInit): Promise<T>;
export declare function download(base: string, path: string): Promise<Uint8Array>;
