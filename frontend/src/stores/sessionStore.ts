import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSessionStore = defineStore('session', () => {
  const authenticated = ref(false)
  const username = ref<string | null>(null)

  async function login(loginUsername: string, password: string): Promise<void> {
    await fetch('/api/v1/session/csrf', { credentials: 'include' })
    const response = await fetch('/api/v1/session/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfCookie() },
      body: JSON.stringify({ username: loginUsername, password })
    })
    if (!response.ok) {
      throw new Error('登录失败，请检查账户和密码')
    }
    const session = (await response.json()) as { authenticated: boolean; username?: string }
    authenticated.value = session.authenticated
    username.value = session.username ?? null
  }

  async function logout(): Promise<void> {
    await fetch('/api/v1/session/logout', { method: 'POST', credentials: 'include', headers: { 'X-XSRF-TOKEN': csrfCookie() } })
    authenticated.value = false
    username.value = null
  }

  return { authenticated, username, login, logout }
})

function csrfCookie(): string {
  const token = document.cookie.split('; ').find((value) => value.startsWith('XSRF-TOKEN='))
  return token ? decodeURIComponent(token.substring('XSRF-TOKEN='.length)) : ''
}
