<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppShell from './components/AppShell.vue'

const route = useRoute()
const isLogin = computed(() => route.path === '/login')
</script>

<template>
  <AppShell v-if="!isLogin"><RouterView /></AppShell>
  <RouterView v-else />
</template>
