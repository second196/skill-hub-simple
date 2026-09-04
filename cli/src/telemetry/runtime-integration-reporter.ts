import { randomUUID } from 'node:crypto'
import type { RuntimeIntegrationResult, RuntimeKey, TelemetryAction } from '../adapters/types.js'
import {
  SkillHubClient,
  type RuntimeIntegrationEventRequest,
  type RuntimeIntegrationRegistrationRequest
} from '../clients/skillhub-client.js'
import { CliError } from '../shared/errors.js'
import { CredentialsStore } from '../stores/credentials-store.js'
import {
  RuntimeIntegrationStore,
  type RuntimeIntegrationLocalState
} from '../stores/runtime-integration-store.js'
import {
  IntegrationEventSpool,
  type RuntimeIntegrationEvent
} from './integration-event-spool.js'

export interface RuntimeIntegrationReportOptions {
  action: Exclude<TelemetryAction, 'status'>
  scopeId?: number
  serviceUrl: string
  adapterVersion: string
  result: RuntimeIntegrationResult
}

export interface RuntimeIntegrationReportOutcome {
  status: 'REPORTED' | 'PENDING'
  sentEvents: number
  pendingEvents: number
  message: string
  errorCode?: string
}

interface RuntimeIntegrationReporterDependencies {
  fetchImpl?: typeof fetch
  eventIdFactory?: () => string
  now?: () => Date
}

/** 将本地运行时接入结果登记到 SkillHub，并负责离线事件的按序补报。 */
export class RuntimeIntegrationReporter {
  private readonly store: RuntimeIntegrationStore
  private readonly spool: IntegrationEventSpool
  private readonly credentials: CredentialsStore
  private readonly fetchImpl: typeof fetch
  private readonly eventIdFactory: () => string
  private readonly now: () => Date

  constructor(stateHome: string, dependencies: RuntimeIntegrationReporterDependencies = {}) {
    this.store = new RuntimeIntegrationStore(stateHome)
    this.spool = new IntegrationEventSpool(stateHome)
    this.credentials = new CredentialsStore(stateHome)
    this.fetchImpl = dependencies.fetchImpl ?? fetch
    this.eventIdFactory = dependencies.eventIdFactory ?? randomUUID
    this.now = dependencies.now ?? (() => new Date())
  }

  async report(options: RuntimeIntegrationReportOptions): Promise<RuntimeIntegrationReportOutcome> {
    const previous = await this.store.get(options.result.runtimeKey)
    const scopeChanged = previous?.scopeId !== undefined && options.scopeId !== undefined
      && previous.scopeId !== options.scopeId
    const state: RuntimeIntegrationLocalState = {
      runtimeKey: options.result.runtimeKey,
      runtimeVersion: options.result.runtimeVersion,
      targetKey: options.result.targetKey,
      scopeId: options.scopeId ?? previous?.scopeId,
      adapterVersion: options.adapterVersion,
      configurationDigest: options.result.configurationDigest,
      installationState: options.result.installationState,
      healthStatus: options.result.healthStatus,
      lastEventSequence: (previous?.lastEventSequence ?? 0) + 1,
      integrationId: scopeChanged ? undefined : previous?.integrationId,
      pendingRegistration: scopeChanged || previous?.integrationId === undefined,
      managedFiles: previous?.managedFiles ?? [],
      updatedAt: this.now().toISOString()
    }
    await this.store.set(state)
    await this.spool.enqueue(createEvent(options.action, state, this.eventIdFactory(), this.now()))

    if (state.scopeId === undefined) {
      return this.pending('SCOPE_REQUIRED', '尚未指定归属范围，接入结果已保留待上报')
    }
    const token = await this.credentials.getToken(options.serviceUrl)
    if (token === undefined || token.trim().length === 0) {
      return this.pending('NOT_LOGGED_IN', '当前服务尚未登录，接入结果已保留待上报')
    }

    const client = new SkillHubClient(options.serviceUrl, token, this.fetchImpl)
    try {
      const registered = await client.registerRuntimeIntegration(
        registration(state), `${this.eventIdFactory()}-register`)
      await this.store.set({
        ...state,
        integrationId: registered.integrationId,
        pendingRegistration: false,
        updatedAt: this.now().toISOString()
      })
      let lastError: unknown
      const flushed = await this.spool.flush(async (event) => {
        try {
          const integrationId = await this.resolveIntegrationId(event, client)
          await client.reportRuntimeIntegrationEvent(integrationId, eventPayload(event), event.eventId)
        } catch (error: unknown) {
          lastError = error
          throw error
        }
      })
      if (flushed.pending > 0) {
        return {
          status: 'PENDING',
          sentEvents: flushed.sent,
          pendingEvents: flushed.pending,
          message: '部分接入事件尚未上报，已保留在本地队列',
          errorCode: errorCode(lastError)
        }
      }
      return {
        status: 'REPORTED',
        sentEvents: flushed.sent,
        pendingEvents: 0,
        message: '运行时接入状态已上报'
      }
    } catch (error: unknown) {
      return this.pending(errorCode(error), pendingMessage(error))
    }
  }

  private async resolveIntegrationId(
    event: RuntimeIntegrationEvent,
    client: SkillHubClient
  ): Promise<string> {
    if (event.runtimeKey === undefined) {
      if (event.integrationId === undefined) throw new Error('missing integration id')
      return event.integrationId
    }
    const state = await this.store.get(event.runtimeKey)
    if (state === undefined || state.scopeId === undefined) throw new Error('missing runtime state')
    if (state.integrationId !== undefined) return state.integrationId
    const registered = await client.registerRuntimeIntegration(registration(state), `${event.eventId}-register`)
    await this.store.set({
      ...state,
      integrationId: registered.integrationId,
      pendingRegistration: false,
      updatedAt: this.now().toISOString()
    })
    return registered.integrationId
  }

  private async pending(errorCodeValue: string | undefined, message: string): Promise<RuntimeIntegrationReportOutcome> {
    return {
      status: 'PENDING',
      sentEvents: 0,
      pendingEvents: await this.spool.pendingCount(),
      message,
      errorCode: errorCodeValue
    }
  }
}

function registration(state: RuntimeIntegrationLocalState): RuntimeIntegrationRegistrationRequest {
  if (state.scopeId === undefined) throw new Error('missing scope')
  return {
    scopeId: state.scopeId,
    runtimeKey: state.runtimeKey,
    runtimeVersion: state.runtimeVersion,
    targetKey: state.targetKey,
    adapterVersion: state.adapterVersion,
    configurationDigest: state.configurationDigest,
    installationState: state.installationState,
    healthStatus: state.healthStatus
  }
}

function createEvent(
  action: Exclude<TelemetryAction, 'status'>,
  state: RuntimeIntegrationLocalState,
  eventId: string,
  occurredAt: Date
): RuntimeIntegrationEvent {
  const result = eventResult(state.installationState)
  return {
    eventId,
    runtimeKey: state.runtimeKey,
    integrationId: state.integrationId,
    eventSequence: state.lastEventSequence,
    eventType: action === 'install' ? 'INSTALL_COMPLETED' : 'REPAIR_COMPLETED',
    stage: 'VERIFYING',
    result,
    installationState: state.installationState,
    healthStatus: state.healthStatus,
    occurredAt: occurredAt.toISOString(),
    errorReason: result === 'SUCCEEDED' ? undefined : '本地运行时接入未达到健康状态'
  }
}

function eventResult(
  state: RuntimeIntegrationLocalState['installationState']
): RuntimeIntegrationEvent['result'] {
  if (state === 'ACTIVE' || state === 'RESTORED') return 'SUCCEEDED'
  if (state === 'ACTION_REQUIRED') return 'ACTION_REQUIRED'
  if (state === 'REQUIRES_MANUAL') return 'REQUIRES_MANUAL'
  return 'FAILED'
}

function eventPayload(event: RuntimeIntegrationEvent): RuntimeIntegrationEventRequest {
  return {
    eventId: event.eventId,
    eventSequence: event.eventSequence,
    eventType: event.eventType,
    stage: event.stage,
    result: event.result,
    installationState: event.installationState,
    healthStatus: event.healthStatus,
    occurredAt: event.occurredAt,
    failureStage: event.failureStage,
    errorCode: event.errorCode,
    errorReason: event.errorReason
  }
}

function errorCode(error: unknown): string | undefined {
  return error instanceof CliError ? error.code : undefined
}

function pendingMessage(error: unknown): string {
  if (error instanceof CliError && error.code === 'AUTHENTICATION_FAILED') {
    return '服务端拒绝访问凭证，接入结果已保留待上报'
  }
  return '暂时无法连接 SkillHub，接入结果已保留待上报'
}
