<script setup lang="ts">
import type { InstallationInstance } from '../types/installation'
import { installationLabel } from '../services/installationDisplayText'

defineProps<{ instance: InstallationInstance }>()
</script>

<template>
  <section class="content-section" aria-labelledby="tracker-status-title">
    <div class="section-heading"><h2 id="tracker-status-title">运行跟踪器状态</h2><span class="status">{{ installationLabel(instance.trackerState) }}</span></div>
    <p class="muted">运行跟踪器必须完成安装并通过健康确认，实例才会进入完整可用状态。</p>
  </section>
</template>
