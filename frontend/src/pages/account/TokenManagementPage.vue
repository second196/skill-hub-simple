<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { createToken, fetchTokens, revokeToken, updateTokenExpiration } from '../../modules/token/api/tokenApi'
import type { ApiTokenCreateResponse, ApiTokenSummary, TokenScope } from '../../modules/token/types/token'

type ExpirationMode = 'never' | '7d' | '30d' | '90d' | 'custom'

const tokens = ref<ApiTokenSummary[]>([])
const loading = ref(true)
const submitting = ref(false)
const actionSubmitting = ref(false)
const errorMessage = ref<string | null>(null)
const successMessage = ref<string | null>(null)

const createDialogOpen = ref(false)
const oneTimeToken = ref<ApiTokenCreateResponse | null>(null)
const createError = ref<string | null>(null)
const name = ref('')
const expirationMode = ref<ExpirationMode>('never')
const customExpiresAt = ref('')
const selectedScopes = ref<TokenScope[]>(['skill:read'])

const expirationDialogOpen = ref(false)
const editingToken = ref<ApiTokenSummary | null>(null)
const expirationError = ref<string | null>(null)
const expirationEditMode = ref<ExpirationMode>('never')
const expirationDraft = ref('')

const deleteTarget = ref<ApiTokenSummary | null>(null)

const scopeOptions: Array<{ value: TokenScope; label: string; description: string }> = [
  { value: 'skill:read', label: '资产读取', description: '读取资产、版本、文件和制品' },
  { value: 'skill:publish', label: '资产发布', description: '提交 Skill 压缩包导入请求' },
  { value: 'token:manage', label: '令牌管理', description: '管理当前账户的访问凭证' }
]

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    tokens.value = await fetchTokens()
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : 'Token 列表加载失败'
  } finally {
    loading.value = false
  }
}

function openCreateDialog(): void {
  createError.value = null
  oneTimeToken.value = null
  successMessage.value = null
  name.value = ''
  expirationMode.value = 'never'
  customExpiresAt.value = ''
  selectedScopes.value = ['skill:read']
  createDialogOpen.value = true
}

function closeCreateDialog(): void {
  createDialogOpen.value = false
  oneTimeToken.value = null
  createError.value = null
}

async function submit(): Promise<void> {
  const normalizedName = name.value.trim()
  if (!normalizedName) {
    createError.value = '请输入 Token 名称'
    return
  }
  if (normalizedName.length > 64) {
    createError.value = 'Token 名称最多 64 个字符'
    return
  }
  if (tokens.value.some((token) => token.status !== 'REVOKED' && token.name.trim().toLocaleLowerCase() === normalizedName.toLocaleLowerCase())) {
    createError.value = '你已经有同名 Token'
    return
  }
  if (selectedScopes.value.length === 0) {
    createError.value = '请至少选择一个访问作用域'
    return
  }
  const expiresAt = resolveExpiration(expirationMode.value, customExpiresAt.value)
  if (expirationMode.value === 'custom' && !expiresAt) {
    createError.value = '请选择自定义过期时间'
    return
  }

  submitting.value = true
  createError.value = null
  try {
    oneTimeToken.value = await createToken({
      name: normalizedName,
      scopes: selectedScopes.value,
      expiresAt
    })
    await load()
  } catch (error: unknown) {
    createError.value = error instanceof Error ? error.message : 'Token 创建失败'
  } finally {
    submitting.value = false
  }
}

function toggleScope(scope: TokenScope): void {
  selectedScopes.value = selectedScopes.value.includes(scope)
    ? selectedScopes.value.filter((item) => item !== scope)
    : [...selectedScopes.value, scope]
}

function startExpirationEdit(token: ApiTokenSummary): void {
  editingToken.value = token
  expirationEditMode.value = token.expiresAt ? 'custom' : 'never'
  expirationDraft.value = toLocalInput(token.expiresAt)
  expirationError.value = null
  expirationDialogOpen.value = true
}

function closeExpirationDialog(): void {
  expirationDialogOpen.value = false
  editingToken.value = null
  expirationError.value = null
  expirationEditMode.value = 'never'
  expirationDraft.value = ''
}

async function saveExpiration(): Promise<void> {
  if (!editingToken.value) return
  const expiresAt = resolveExpiration(expirationEditMode.value, expirationDraft.value)
  if (expirationEditMode.value === 'custom' && !expiresAt) {
    expirationError.value = '请选择过期时间'
    return
  }
  actionSubmitting.value = true
  expirationError.value = null
  try {
    const updated = await updateTokenExpiration(editingToken.value.id, expiresAt ?? null)
    const index = tokens.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) tokens.value[index] = updated
    successMessage.value = 'Token 过期时间已更新'
    closeExpirationDialog()
  } catch (error: unknown) {
    expirationError.value = error instanceof Error ? error.message : '更新过期时间失败'
  } finally {
    actionSubmitting.value = false
  }
}

function requestDelete(token: ApiTokenSummary): void {
  deleteTarget.value = token
}

function closeDeleteDialog(): void {
  deleteTarget.value = null
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value) return
  actionSubmitting.value = true
  errorMessage.value = null
  try {
    await revokeToken(deleteTarget.value.id)
    tokens.value = tokens.value.filter((item) => item.id !== deleteTarget.value?.id)
    successMessage.value = 'Token 已删除'
    closeDeleteDialog()
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '删除 Token 失败'
  } finally {
    actionSubmitting.value = false
  }
}

async function copyToken(): Promise<void> {
  if (!oneTimeToken.value) return
  try {
    await navigator.clipboard.writeText(oneTimeToken.value.token)
    successMessage.value = 'Token 已复制到剪贴板'
  } catch (_) {
    createError.value = '复制 Token 失败，请手动复制'
  }
}

function scopeLabel(scope: TokenScope): string {
  return scope === 'skill:read' ? '资产读取' : scope === 'skill:publish' ? '资产发布' : '令牌管理'
}

function statusLabel(status: ApiTokenSummary['status']): string {
  return status === 'ACTIVE' ? '有效' : status === 'EXPIRED' ? '已过期' : '已删除'
}

function statusClass(status: ApiTokenSummary['status']): string {
  return status === 'ACTIVE' ? 'published' : status === 'EXPIRED' ? 'pending_approval' : 'blocked'
}

function formatDate(value?: string, emptyLabel = '-'): string {
  if (!value) return emptyLabel
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function resolveExpiration(mode: ExpirationMode, customValue: string): string | undefined {
  if (mode === 'never') return undefined
  if (mode === 'custom') return toIso(customValue)
  const date = new Date()
  const days = mode === '7d' ? 7 : mode === '30d' ? 30 : 90
  date.setDate(date.getDate() + days)
  date.setSeconds(0, 0)
  return date.toISOString()
}

function toIso(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined
}

function toLocalInput(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  const pad = (item: number) => String(item).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

onMounted(load)
</script>

<template>
  <main class="page-shell token-page">
    <header class="page-header token-page-header">
      <RouterLink class="back-link" to="/dashboard">返回工作台</RouterLink>
      <div class="token-page-title-row">
        <div>
          <h1>Token 管理</h1>
          <p class="page-subtitle">管理 CLI 和 API 使用的访问凭证</p>
        </div>
      </div>
    </header>

    <p v-if="errorMessage" class="state-message error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="state-message success-message" role="status">{{ successMessage }}</p>

    <section class="content-section token-list-panel" aria-labelledby="token-list-title">
      <div class="token-list-toolbar">
        <h2 id="token-list-title">API Tokens</h2>
        <button type="button" @click="openCreateDialog">创建新 Token</button>
      </div>
      <p class="token-copy-hint">出于安全原因，Token 明文只在创建成功时可复制一次，后续无法再次查看或复制。若仍需使用，请重新创建新的 Token。</p>

      <p v-if="loading" class="token-empty-state">加载中...</p>
      <div v-else-if="tokens.length === 0" class="token-empty-state">
        <p>还没有创建任何 Token</p>
        <p class="muted">点击上方按钮创建第一个 Token</p>
      </div>
      <div v-else class="table-wrap token-table-wrap">
        <table class="token-table">
          <thead>
            <tr><th>名称</th><th>Token 前缀</th><th>创建时间</th><th>最后使用</th><th>过期时间</th><th class="token-actions-head">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="token in tokens" :key="token.id">
              <td class="token-name-cell">
                <strong>{{ token.name }}</strong>
                <small class="description"><span class="status" :class="statusClass(token.status)">{{ statusLabel(token.status) }}</span> · {{ token.scopes.map(scopeLabel).join('、') }}</small>
              </td>
              <td><code class="token-prefix">{{ token.tokenPrefix }}...</code></td>
              <td>{{ formatDate(token.createdAt) }}</td>
              <td>{{ formatDate(token.lastUsedAt) }}</td>
              <td>{{ formatDate(token.expiresAt, '永不过期') }}</td>
              <td class="token-actions-cell">
                <div class="token-actions">
                  <button v-if="token.status === 'ACTIVE'" class="button-secondary" type="button" @click="startExpirationEdit(token)">修改过期时间</button>
                  <button v-if="token.status === 'ACTIVE'" class="button-danger" type="button" @click="requestDelete(token)">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="createDialogOpen" class="token-modal-backdrop" role="presentation" @click.self="closeCreateDialog">
      <section class="token-modal" role="dialog" aria-modal="true" aria-labelledby="create-token-title">
        <template v-if="!oneTimeToken">
          <header class="token-modal-header">
            <h2 id="create-token-title">创建新 Token</h2>
            <p>创建一个新的 API Token 用于 API 访问</p>
          </header>
          <form class="token-modal-form" @submit.prevent="submit">
            <label for="token-name">Token 名称</label>
            <input id="token-name" v-model="name" maxlength="64" placeholder="例如：持续集成发布" autocomplete="off" required />
            <div class="token-input-meta"><span class="error-text">{{ createError && createError.includes('名称') ? createError : '' }}</span><span>{{ name.trim().length }}/64</span></div>

            <label for="token-expiration">过期时间</label>
            <select id="token-expiration" v-model="expirationMode">
              <option value="never">永不过期</option>
              <option value="7d">7 天后过期</option>
              <option value="30d">30 天后过期</option>
              <option value="90d">90 天后过期</option>
              <option value="custom">自定义时间</option>
            </select>
            <input v-if="expirationMode === 'custom'" v-model="customExpiresAt" type="datetime-local" :min="toLocalInput(new Date().toISOString())" />
            <p class="form-hint">默认永不过期，也可以选择一个自动过期时间。</p>

            <fieldset class="token-scope-fieldset">
              <legend>访问作用域</legend>
              <label v-for="option in scopeOptions" :key="option.value" class="token-scope-option">
                <input type="checkbox" :checked="selectedScopes.includes(option.value)" @change="toggleScope(option.value)" />
                <span><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
              </label>
            </fieldset>
            <p v-if="createError && !createError.includes('名称')" class="error-text">{{ createError }}</p>
            <footer class="token-modal-footer">
              <button class="button-secondary" type="button" @click="closeCreateDialog">取消</button>
              <button type="submit" :disabled="submitting">{{ submitting ? '创建中...' : '创建' }}</button>
            </footer>
          </form>
        </template>
        <template v-else>
          <header class="token-modal-header token-success-header">
            <h2>Token 创建成功</h2>
            <p>请立即复制并保存此 Token。它只会在创建成功时展示和复制这一回，后续若仍需使用，请重新创建。</p>
          </header>
          <div class="token-created-detail">
            <label>Token</label>
            <div class="token-plain-value">{{ oneTimeToken.token }}</div>
            <label>名称</label>
            <div>{{ oneTimeToken.name }}</div>
            <label>过期时间</label>
            <div>{{ formatDate(oneTimeToken.expiresAt, '永不过期') }}</div>
          </div>
          <p v-if="createError" class="error-text">{{ createError }}</p>
          <footer class="token-modal-footer token-modal-footer-center">
            <button type="button" @click="copyToken">复制 Token</button>
            <button class="button-secondary" type="button" @click="closeCreateDialog">关闭</button>
          </footer>
        </template>
      </section>
    </div>

    <div v-if="expirationDialogOpen" class="token-modal-backdrop" role="presentation" @click.self="closeExpirationDialog">
      <section class="token-modal token-small-modal" role="dialog" aria-modal="true" aria-labelledby="expiration-title">
        <header class="token-modal-header">
          <h2 id="expiration-title">修改 Token 过期时间</h2>
          <p>为 Token “{{ editingToken?.name }}” 设置新的过期时间。</p>
        </header>
        <div class="token-modal-form">
          <label for="expiration-value">过期时间</label>
          <select id="expiration-value" v-model="expirationEditMode">
            <option value="never">永不过期</option>
            <option value="7d">7 天后过期</option>
            <option value="30d">30 天后过期</option>
            <option value="90d">90 天后过期</option>
            <option value="custom">自定义时间</option>
          </select>
          <input v-if="expirationEditMode === 'custom'" v-model="expirationDraft" type="datetime-local" :min="toLocalInput(new Date().toISOString())" />
          <p class="form-hint">默认永不过期，也可以选择一个自动过期时间。</p>
          <p v-if="expirationError" class="error-text">{{ expirationError }}</p>
        </div>
        <footer class="token-modal-footer">
          <button class="button-secondary" type="button" @click="closeExpirationDialog">取消</button>
          <button type="button" :disabled="actionSubmitting" @click="saveExpiration">{{ actionSubmitting ? '保存中...' : '保存过期时间' }}</button>
        </footer>
      </section>
    </div>

    <div v-if="deleteTarget" class="token-modal-backdrop" role="presentation" @click.self="closeDeleteDialog">
      <section class="token-modal token-small-modal" role="dialog" aria-modal="true" aria-labelledby="delete-token-title">
        <header class="token-modal-header">
          <h2 id="delete-token-title">删除 Token</h2>
          <p>确定要删除 Token “{{ deleteTarget.name }}”吗？此操作无法撤销。</p>
        </header>
        <footer class="token-modal-footer">
          <button class="button-secondary" type="button" @click="closeDeleteDialog">取消</button>
          <button class="button-danger-filled" type="button" :disabled="actionSubmitting" @click="confirmDelete">{{ actionSubmitting ? '删除中...' : '删除' }}</button>
        </footer>
      </section>
    </div>
  </main>
</template>
