<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/sessionStore'

const router = useRouter()
const session = useSessionStore()
const username = ref('')
const password = ref('')
const errorMessage = ref<string | null>(null)
const loading = ref(false)

async function submit(): Promise<void> {
  errorMessage.value = null
  loading.value = true
  try {
    await session.login(username.value, password.value)
    await router.push('/dashboard')
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-shell">
    <form class="auth-panel" @submit.prevent="submit">
      <p class="eyebrow">技能中心</p>
      <h1>账户登录</h1>
      <label>账户<input v-model="username" autocomplete="username" required /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required /></label>
      <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
      <button :disabled="loading" type="submit">{{ loading ? '登录中' : '登录' }}</button>
    </form>
  </main>
</template>
