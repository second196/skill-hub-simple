<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { addNamespaceMember, fetchAdminAccounts, fetchAdminLabels, fetchAdminMembers, fetchAdminNamespaces, removeAdminLabel, removeNamespaceMember, updateAccountStatus, type AdminAccount, type Namespace, type NamespaceMember, type VersionTag } from '../../modules/admin/adminApi'
import ModalDialog from '../../components/ui/ModalDialog.vue'
import PageTabs, { type PageTab } from '../../components/ui/PageTabs.vue'
import AuditPage from '../asset-governance/AuditPage.vue'

const route = useRoute()
const router = useRouter()
const tabs: PageTab[] = [
  { key: 'users', label: '用户管理' },
  { key: 'namespaces', label: '命名空间管理' },
  { key: 'labels', label: '标签管理' },
  { key: 'audits', label: '审计日志' }
]
const activeTab = computed(() => {
  const requested = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  return tabs.some((tab) => tab.key === requested) ? requested as string : 'users'
})

function selectTab(tab: string): void {
  void router.replace({ query: { tab } })
}

const accounts = ref<AdminAccount[]>([])
const namespaces = ref<Namespace[]>([])
const labels = ref<VersionTag[]>([])
const selectedNamespace = ref('')
const members = ref<NamespaceMember[]>([])
const memberUsername = ref('')
const memberRole = ref('MEMBER')
const loading = ref(true)
const error = ref<string | null>(null)
const removeTarget = ref<{ type: 'label' | 'member'; label?: VersionTag; member?: NamespaceMember } | null>(null)
const removeBusy = ref(false)
const removeTitle = computed(() => removeTarget.value?.type === 'label' ? '确认移除标签' : '确认移除成员')
const removeDescription = computed(() => {
  const target = removeTarget.value
  if (!target) return ''
  return target.type === 'label'
    ? `移除标签“${target.label?.tagName || ''}”后将无法在当前版本上使用。`
    : `移除成员“${target.member?.username || ''}”后将失去当前命名空间的协作权限。`
})
const removeConfirmText = computed(() => removeTarget.value?.type === 'label' ? '移除标签' : '移除成员')

async function load(): Promise<void> {
  loading.value = true; error.value = null
  try { [accounts.value, namespaces.value, labels.value] = await Promise.all([fetchAdminAccounts(), fetchAdminNamespaces(), fetchAdminLabels()]) }
  catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '管理数据加载失败' }
  finally { loading.value = false }
}

async function toggle(account: AdminAccount): Promise<void> {
  try { await updateAccountStatus(account.username, !account.enabled); account.enabled = !account.enabled }
  catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '账户状态更新失败' }
}

async function removeLabel(label: VersionTag): Promise<void> {
  removeTarget.value = { type: 'label', label }
}

async function removeMember(member: NamespaceMember): Promise<void> {
  removeTarget.value = { type: 'member', member }
}

async function confirmRemove(): Promise<void> {
  if (!removeTarget.value) return
  removeBusy.value = true
  try {
    if (removeTarget.value.type === 'label' && removeTarget.value.label) {
      await removeAdminLabel(removeTarget.value.label.id)
      labels.value = labels.value.filter(item => item.id !== removeTarget.value?.label?.id)
    } else if (removeTarget.value.member) {
      await removeNamespaceMember(removeTarget.value.member.principalId)
      await loadMembers()
    }
    removeTarget.value = null
  }
  catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '标签移除失败' }
  finally { removeBusy.value = false }
}

async function loadMembers(): Promise<void> {
  if (!selectedNamespace.value) { members.value = []; return }
  try { members.value = await fetchAdminMembers(selectedNamespace.value) }
  catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '成员加载失败' }
}

async function addMember(): Promise<void> {
  if (!selectedNamespace.value || !memberUsername.value.trim()) return
  try { await addNamespaceMember(selectedNamespace.value, memberUsername.value.trim(), memberRole.value); memberUsername.value = ''; await loadMembers() }
  catch (reason: unknown) { error.value = reason instanceof Error ? reason.message : '成员添加失败' }
}

function namespaceStatusLabel(value: string): string {
  return value === 'ACTIVE' ? '正常' : value === 'INACTIVE' ? '已停用' : '未知状态'
}

function roleLabel(value: string): string {
  const labels: Record<string, string> = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }
  return labels[value] ?? '未知角色'
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header page-header-row"><div><p class="eyebrow">系统治理 / 管理员</p><h1>系统管理</h1><p class="muted">管理账户状态、命名空间和版本标签。</p></div><button class="button-secondary" type="button" :disabled="loading" @click="load">刷新数据</button></header>
    <p v-if="error" class="state-message error" role="alert">{{ error }}</p>
    <PageTabs :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />
    <section v-if="activeTab === 'users'" class="content-section"><div class="section-heading"><div><p class="section-kicker">账户</p><h2>账户状态</h2></div></div><p v-if="loading" class="state-message">正在加载...</p><div v-else class="table-wrap"><table><thead><tr><th>账户</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="account in accounts" :key="account.id"><td><strong>{{ account.username }}</strong></td><td><span class="status" :class="account.enabled ? 'published' : 'blocked'">{{ account.enabled ? '已启用' : '已停用' }}</span></td><td><button class="button-secondary" type="button" @click="toggle(account)">{{ account.enabled ? '停用' : '启用' }}</button></td></tr></tbody></table></div></section>
    <section v-if="activeTab === 'namespaces'" class="content-section"><div class="section-heading"><div><p class="section-kicker">空间</p><h2>命名空间与成员</h2></div></div><div class="namespace-admin-grid"><div class="table-wrap"><table><thead><tr><th>命名空间</th><th>显示名称</th><th>状态</th></tr></thead><tbody><tr v-for="space in namespaces" :key="space.id" :class="{ selected: selectedNamespace === space.namespaceKey }" @click="selectedNamespace = space.namespaceKey; loadMembers()"><td><code>{{ space.namespaceKey }}</code></td><td>{{ space.displayName }}</td><td>{{ namespaceStatusLabel(space.status) }}</td></tr></tbody></table></div><div class="member-panel"><h3>{{ selectedNamespace || '选择命名空间' }}</h3><form v-if="selectedNamespace" class="member-form" @submit.prevent="addMember"><input v-model="memberUsername" required placeholder="账户名" /><select v-model="memberRole"><option value="OWNER">所有者</option><option value="ADMIN">管理员</option><option value="MEMBER">成员</option></select><button type="submit">添加成员</button></form><p v-if="selectedNamespace && members.length === 0" class="state-message">暂无成员。</p><ul v-else class="member-list"><li v-for="member in members" :key="member.principalId"><span>{{ member.username }} · {{ roleLabel(member.roleKey) }}</span><button class="button-danger" type="button" @click="removeMember(member)">移除</button></li></ul></div></div></section>
    <section v-if="activeTab === 'labels'" class="content-section"><div class="section-heading"><div><p class="section-kicker">版本治理</p><h2>版本标签</h2></div></div><p v-if="labels.length === 0" class="state-message">暂无版本标签。</p><div v-else class="table-wrap"><table><thead><tr><th>标签</th><th>资产 ID</th><th>版本摘要</th><th>操作</th></tr></thead><tbody><tr v-for="label in labels" :key="label.id"><td><strong>{{ label.tagName }}</strong></td><td>{{ label.assetId }}</td><td><code>{{ label.versionDigest }}</code></td><td><button class="button-danger" type="button" @click="removeLabel(label)">移除标签</button></td></tr></tbody></table></div></section>
    <AuditPage v-if="activeTab === 'audits'" embedded />
    <ModalDialog :open="Boolean(removeTarget)" :title="removeTitle" :description="removeDescription" :confirm-text="removeConfirmText" danger :busy="removeBusy" @close="removeTarget = null" @confirm="confirmRemove" />
  </main>
</template>
