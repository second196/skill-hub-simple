export declare function normalizePackagePath(value: string, maxLength: number): string;
/**
 * Package-level exclusion: credentials, VCS metadata, dependency dirs, junk.
 * Used when reading directories and when rejecting sensitive ZIP entries.
 */
export declare function shouldExcludePackagePath(value: string): boolean;
/**
 * versionDigest exclusion: business-file fingerprint only.
 * Excludes .git, node_modules, .DS_Store, *.tmp and other non-business paths.
 * Currently identical to package exclusion so digest covers uploaded content.
 */
export declare function shouldExcludeFromVersionDigest(value: string): boolean;
