export declare function normalizePackagePath(value: string, maxLength: number): string;
/**
 * Package-level exclusion: credentials, VCS metadata, dependency dirs, junk.
 * Used when reading directories and when rejecting sensitive ZIP entries.
 */
export declare function shouldExcludePackagePath(value: string): boolean;
