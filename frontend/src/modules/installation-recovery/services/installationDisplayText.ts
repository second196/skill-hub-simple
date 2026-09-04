const labels: Record<string, string> = {
  REQUESTED: '已提交', VALIDATING: '校验中', DOWNLOADING: '下载中', INSTALLING_SKILL: '安装技能中',
  INSTALLING_TRACKER: '安装运行跟踪器中', CONFIGURING: '配置中', VERIFYING: '健康确认中', SWITCHING: '切换中',
  SUCCEEDED: '已完成', READY: '就绪', INSTALLING: '安装中', NOT_INSTALLED: '未安装', INCOMPLETE: '安装不完整',
  FAILED: '失败', UNAVAILABLE: '不可用', ROLLING_BACK: '回退中', ROLLED_BACK: '已回退', REQUIRES_MANUAL: '需要人工处理',
  REVOKE_REQUESTED: '撤回已提交', REVOKING: '撤回中', REVOKED: '已撤回', PARTIAL_REVOKED: '部分撤回',
  DETECTED: '已检测', PLANNED: '计划已生成', SNAPSHOTTED: '已创建快照', APPLYING: '正在应用',
  ACTIVE: '已启用', ACTION_REQUIRED: '需要人工操作', RESTORED: '已恢复', DISABLED: '已停用',
  HEALTHY: '健康', DEGRADED: '降级', UNHEALTHY: '不健康', UNKNOWN: '未知'
}

export function installationLabel(value: string | undefined): string {
  return value ? labels[value] ?? '未识别状态' : '未知状态'
}

export function operationLabel(value: string | undefined): string {
  const values: Record<string, string> = { INSTALL: '安装', SWITCH: '切换版本', ROLLBACK: '回退', REVOKE: '紧急撤回' }
  return value ? values[value] ?? '其他操作' : '未知操作'
}

export function digestLabel(value: string | undefined): string {
  return value ? `${value.substring(0, 12)}…` : '未记录版本'
}

export function runtimeLabel(value: string): string {
  const runtimes: Record<string, string> = {
    'codex-cli': 'Codex 命令行',
    vscode: 'Visual Studio Code',
    cursor: 'Cursor',
    windsurf: 'Windsurf',
    'claude-code-otlp': 'Claude Code 遥测接入'
  }
  return runtimes[value] ?? '未识别的运行时'
}

export function runtimeIntegrationStateLabel(value: string | undefined): string {
  return value ? labels[value] ?? '未识别状态' : '未知状态'
}

export function runtimeIntegrationTone(
  installationState: string,
  healthStatus: string
): 'healthy' | 'warning' | 'danger' | 'neutral' {
  if (['FAILED', 'REQUIRES_MANUAL', 'DISABLED'].includes(installationState) || healthStatus === 'UNHEALTHY') {
    return 'danger'
  }
  if (installationState === 'ACTION_REQUIRED' || healthStatus === 'DEGRADED'
    || ['PLANNED', 'SNAPSHOTTED', 'APPLYING', 'VERIFYING', 'ROLLING_BACK'].includes(installationState)) {
    return 'warning'
  }
  return installationState === 'ACTIVE' && healthStatus === 'HEALTHY' ? 'healthy' : 'neutral'
}
