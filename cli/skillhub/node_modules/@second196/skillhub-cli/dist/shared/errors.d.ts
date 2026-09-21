export declare class CliError extends Error {
    readonly code: string;
    readonly exitCode: number;
    readonly details: Record<string, unknown>;
    constructor(message: string, code: string, exitCode: number, details?: Record<string, unknown>);
}
export declare class PackageValidationError extends CliError {
    constructor(message: string, code: string, details?: Record<string, unknown>);
}
