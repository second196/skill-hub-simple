import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: () => import('../pages/dashboard/DashboardPage.vue'), meta: { requiresAuth: true } },
    { path: '/login', component: () => import('../pages/LoginPage.vue') },
    { path: '/search', component: () => import('../pages/discovery/SkillSearchPage.vue'), meta: { requiresAuth: true } },
    { path: '/dashboard/skills', component: () => import('../pages/dashboard/MySkillsPage.vue'), meta: { requiresAuth: true } },
    { path: '/dashboard/publish', component: () => import('../pages/dashboard/PublishPage.vue'), meta: { requiresAuth: true } },
    { path: '/dashboard/tokens', redirect: '/account/tokens', meta: { requiresAuth: true } },
    { path: '/dashboard/governance', component: () => import('../pages/dashboard/GovernanceCenterPage.vue'), meta: { requiresAuth: true } },
    { path: '/governance', component: () => import('../pages/dashboard/GovernanceCenterPage.vue'), meta: { requiresAuth: true } },
    { path: '/dashboard/namespaces', component: () => import('../pages/dashboard/MyNamespacesPage.vue'), meta: { requiresAuth: true } },
    { path: '/dashboard/namespaces/:slug/members', component: () => import('../pages/dashboard/NamespaceMembersPage.vue'), meta: { requiresAuth: true } },
    { path: '/dashboard/namespaces/:slug/reviews', component: () => import('../pages/review/ReviewWorkbenchPage.vue'), meta: { requiresAuth: true } },
    { path: '/space/:namespace/:slug/compare', component: () => import('../pages/skill/SkillVersionComparePage.vue'), meta: { requiresAuth: true } },
    { path: '/space/:namespace', component: () => import('../pages/namespace/NamespaceSpacePage.vue'), meta: { requiresAuth: true } },
    { path: '/space/:namespace/:slug', component: () => import('../pages/skill/SkillDetailPage.vue'), meta: { requiresAuth: true } },
    { path: '/space/:namespace/:slug/versions/compare', component: () => import('../pages/skill/SkillVersionComparePage.vue'), meta: { requiresAuth: true } },
    { path: '/reviews', component: () => import('../pages/review/ReviewWorkbenchPage.vue'), meta: { requiresAuth: true } },
    { path: '/dashboard/reviews', component: () => import('../pages/review/ReviewWorkbenchPage.vue'), meta: { requiresAuth: true } },
    { path: '/admin', component: () => import('../pages/admin/AdminConsolePage.vue'), meta: { requiresAuth: true } },
    { path: '/admin/accounts', component: () => import('../pages/admin/AdminConsolePage.vue'), meta: { requiresAuth: true } },
    { path: '/admin/namespaces', component: () => import('../pages/admin/AdminNamespacesPage.vue'), meta: { requiresAuth: true } },
    { path: '/admin/labels', component: () => import('../pages/admin/AdminLabelsPage.vue'), meta: { requiresAuth: true } },
    { path: '/admin/users', component: () => import('../pages/admin/AdminSectionPage.vue'), meta: { requiresAuth: true } },
    { path: '/admin/audit-log', component: () => import('../pages/asset-governance/AuditPage.vue'), meta: { requiresAuth: true } },
    { path: '/assets', component: () => import('../pages/asset-governance/AssetCatalogPage.vue'), meta: { requiresAuth: true } },
    { path: '/assets/import', component: () => import('../pages/asset-governance/AssetImportPage.vue'), meta: { requiresAuth: true } },
    { path: '/assets/:assetId', component: () => import('../pages/asset-governance/AssetDetailPage.vue'), meta: { requiresAuth: true } },
    { path: '/assets/:assetId/versions/:versionDigest', component: () => import('../pages/asset-governance/VersionDetailPage.vue'), meta: { requiresAuth: true } },
    { path: '/account/tokens', component: () => import('../pages/account/TokenManagementPage.vue'), meta: { requiresAuth: true } },
    { path: '/settings', component: () => import('../pages/settings/SettingsPage.vue'), meta: { requiresAuth: true } },
    { path: '/settings/security', component: () => import('../pages/settings/SettingsPage.vue'), meta: { requiresAuth: true } },
    { path: '/settings/profile', component: () => import('../pages/settings/SettingsPage.vue'), meta: { requiresAuth: true } },
    { path: '/settings/notifications', component: () => import('../pages/settings/SettingsPage.vue'), meta: { requiresAuth: true } },
    { path: '/installations', component: () => import('../pages/installation-recovery/InstallationCatalogPage.vue'), meta: { requiresAuth: true } },
    { path: '/installations/:instanceId', component: () => import('../pages/installation-recovery/InstallationDetailPage.vue'), meta: { requiresAuth: true } },
    { path: '/releases/decisions/:decisionId', component: () => import('../pages/asset-governance/ReleaseDecisionPage.vue'), meta: { requiresAuth: true } },
    { path: '/governance/policies', component: () => import('../pages/asset-governance/PolicyPage.vue'), meta: { requiresAuth: true } },
    { path: '/governance/audits', component: () => import('../pages/asset-governance/AuditPage.vue'), meta: { requiresAuth: true } }
  ]
})

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) {
    return true
  }
  const response = await fetch('/api/v1/session/current', { credentials: 'include' })
  if (response.ok) {
    return true
  }
  return '/login'
})

export default router
