import { describe, expect, it } from 'vitest'
import sidebar from '../../src/components/layout/SideNavigation.vue?raw'
import governance from '../../src/pages/dashboard/GovernanceCenterPage.vue?raw'
import admin from '../../src/pages/admin/AdminConsolePage.vue?raw'
import settings from '../../src/pages/settings/SettingsPage.vue?raw'

describe('企业导航和页签结构', () => {
  it('侧边栏使用收敛后的企业级文本入口', () => {
    expect(sidebar).not.toContain('nav-glyph')
    expect(sidebar).toContain('to="/governance"')
    expect(sidebar).toContain('to="/admin"')
    expect(sidebar).toContain('to="/settings"')
    expect(sidebar).not.toContain('推广管理')
    expect(sidebar).not.toContain('举报管理')
    expect(sidebar).not.toContain('收藏')
  })

  it('聚合页面具备可深链接的页签', () => {
    expect(governance).toContain("key: 'reviews'")
    expect(governance).toContain("key: 'policies'")
    expect(admin).toContain("key: 'namespaces'")
    expect(admin).toContain("key: 'audits'")
    expect(settings).toContain("key: 'notifications'")
    expect(governance).toContain('route.query.tab')
    expect(admin).toContain('route.query.tab')
    expect(settings).toContain('route.query.tab')
  })
})
