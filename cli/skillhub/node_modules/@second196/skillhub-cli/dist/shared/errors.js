import { EXIT_CODE } from './constants.js';
export class CliError extends Error {
    code;
    exitCode;
    details;
    constructor(message, code, exitCode, details = {}) {
        super(message);
        this.code = code;
        this.exitCode = exitCode;
        this.details = details;
        this.name = 'CliError';
    }
}
export class PackageValidationError extends CliError {
    constructor(message, code, details = {}) {
        super(message, code, EXIT_CODE.validation, details);
        this.name = 'PackageValidationError';
    }
}
