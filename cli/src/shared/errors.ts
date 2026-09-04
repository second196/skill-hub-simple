import { EXIT_CODE } from './constants.js'

export class CliError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly exitCode: number,
    readonly details: Record<string, unknown> = {}
  ) {
    super(message)
    this.name = 'CliError'
  }
}

export class PackageValidationError extends CliError {
  constructor(message: string, code: string, details: Record<string, unknown> = {}) {
    super(message, code, EXIT_CODE.validation, details)
    this.name = 'PackageValidationError'
  }
}
