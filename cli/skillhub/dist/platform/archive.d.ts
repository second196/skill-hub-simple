import { type PackageFile, type PackageLimitOverrides, type PackageLimits } from '../shared/types.js';
export declare function createArchive(files: PackageFile[], overrides?: PackageLimitOverrides): Uint8Array;
export declare function readArchive(archive: Uint8Array, overrides?: PackageLimitOverrides): PackageFile[];
export declare function resolvePackageLimits(overrides?: PackageLimitOverrides): PackageLimits;
