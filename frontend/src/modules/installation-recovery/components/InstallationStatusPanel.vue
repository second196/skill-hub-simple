<script setup lang="ts">
import type { InstallationInstance } from '../types/installation'
import { installationLabel } from '../services/installationDisplayText'

defineProps<{ instance: InstallationInstance }>()
</script>

<template>
  <section class="content-section" aria-labelledby="installation-status-title">
    <div class="section-heading"><h2 id="installation-status-title">安装状态</h2><span class="status">{{ installationLabel(instance.overallState) }}</span></div>
    <div class="status-grid">
      <div><span class="muted">技能</span><strong>{{ installationLabel(instance.skillState) }}</strong></div>
      <div><span class="muted">运行跟踪器</span><strong>{{ installationLabel(instance.trackerState) }}</strong></div>
      <div><span class="muted">最近健康</span><strong>{{ installationLabel(instance.healthStatus) }}</strong></div>
      <div><span class="muted">当前版本</span><code>{{ instance.currentVersionDigest ?? '未启用' }}</code></div>
    </div>
    <p v-if="instance.lastErrorReason" class="state-message error">{{ instance.lastErrorReason }}</p>
  </section>
</template>
