<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  option: echarts.EChartsOption
  /** 可选最小高度；不传则 100% 撑满父容器 */
  height?: number
}>()

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value, undefined, { renderer: 'canvas' })
  chart.setOption(props.option, true)
  chart.resize()
}

function onResize() {
  chart?.resize()
}

onMounted(() => {
  render()
  if (typeof ResizeObserver !== 'undefined' && el.value) {
    observer = new ResizeObserver(() => onResize())
    observer.observe(el.value)
    const parent = el.value.parentElement
    if (parent) observer.observe(parent)
  }
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})

watch(
  () => props.option,
  () => render(),
  { deep: true }
)

watch(
  () => props.height,
  () => onResize()
)
</script>

<template>
  <div
    ref="el"
    class="echart-host"
    :style="height ? { height: `${height}px`, width: '100%' } : { height: '100%', width: '100%', minHeight: '240px' }"
    role="img"
  />
</template>
