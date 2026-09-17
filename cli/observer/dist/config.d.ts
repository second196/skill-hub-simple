import type { ObserverConfig } from './types.js';
export declare function defaultServiceUrl(): string;
export declare function loadConfig(): Promise<ObserverConfig>;
export declare function saveConfig(input: Partial<ObserverConfig>): Promise<ObserverConfig>;
export declare function normalizeServiceUrl(value: string | undefined): string;
export declare function resolveServiceUrl(value: string | undefined): string;
export declare function buildServiceUrl(input: {
    protocol?: string;
    host?: string;
    port?: number;
}): string;
export declare function parseServiceUrl(value: string): {
    protocol: string;
    host: string;
    port: number;
} | undefined;
export declare function describeConfig(config: ObserverConfig, path?: string): string;
