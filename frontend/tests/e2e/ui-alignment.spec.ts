import { expect, test, type Page, type Route } from '@playwright/test'

const digest = 'a'.repeat(64)
const oldDigest = 'e'.repeat(64)

async function fulfillJson(route: Route, json: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(json) })
}

async function mockApi(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname

    if (path === '/api/v1/session/current') {
      await fulfillJson(route, { username: 'admin' })
      return
    }
    if (path === '/api/v1/admin/accounts') {
      await fulfillJson(route, [])
      return
    }
    if (path === '/api/v1/assets') {
      await fulfillJson(route, {
        page: 1,
        pageSize: 100,
        items: [{
          assetId: 7,
          assetKey: 'document-review',
          name: '文档评审',
          description: '检查企业文档结构和发布规范',
          status: 'ACTIVE',
          versionLabel: '2.1.0',
          versionDigest: digest,
          lifecycleState: 'PUBLISHED',
          metadataStatus: 'COMPLETE'
        }]
      })
      return
    }
    if (path === '/api/v1/installations') {
      await fulfillJson(route, { items: [], page: 1, pageSize: 100 })
      return
    }
    if (path === '/api/v1/reviews') {
      await fulfillJson(route, [{
        id: 12,
        versionDigest: digest,
        assetId: 7,
        scopeId: 1,
        assetName: '知识库整理',
        versionLabel: '1.3.0',
        applicantId: 'user',
        status: 'PENDING',
        submittedAt: '2026-09-03T08:30:00Z'
      }])
      return
    }
    if (path === '/api/v1/skills/namespaces') {
      await fulfillJson(route, [{
        id: 1,
        namespaceKey: 'kmsoft',
        displayName: '金现代软件',
        ownerScopeId: 1,
        status: 'ACTIVE'
      }])
      return
    }
    if (path === '/api/v1/skills/search') {
      await fulfillJson(route, {
        page: 1,
        pageSize: 12,
        items: [{
          assetId: 7,
          namespaceKey: 'kmsoft',
          assetKey: 'document-review',
          name: '文档评审',
          description: '检查企业文档结构和发布规范',
          status: 'ACTIVE',
          versionLabel: '2.1.0',
          versionDigest: digest,
          lifecycleState: 'PUBLISHED',
          metadataStatus: 'COMPLETE'
        }]
      })
      return
    }
    if (path === '/api/v1/skills/kmsoft/document-review') {
      await fulfillJson(route, {
        assetId: 7,
        ownerScopeId: 1,
        assetKey: 'document-review',
        name: '文档评审',
        description: '检查企业文档结构和发布规范',
        status: 'ACTIVE',
        versionLabel: '2.1.0',
        versionDigest: digest,
        lifecycleState: 'PUBLISHED',
        metadataStatus: 'COMPLETE'
      })
      return
    }
    if (path === '/api/v1/assets/7/versions') {
      await fulfillJson(route, [
        { id: 9, assetId: 7, artifactId: 19, versionLabel: '2.1.0', versionDigest: digest, sourceType: 'FILE', sourceLocator: 'document-review.zip', lifecycleState: 'PUBLISHED', metadataStatus: 'COMPLETE' },
        { id: 8, assetId: 7, artifactId: 18, versionLabel: '2.0.0', versionDigest: oldDigest, sourceType: 'FILE', sourceLocator: 'document-review.zip', lifecycleState: 'OFFLINE', metadataStatus: 'COMPLETE' }
      ])
      return
    }
    if (/^\/api\/v1\/assets\/versions\/[a-f0-9]{64}\/files$/.test(path)) {
      await fulfillJson(route, [
        { path: 'SKILL.md', required: true, readStatus: 'READABLE', contentDigest: 'c'.repeat(64) },
        { path: 'references/policy.md', required: false, readStatus: 'READABLE', contentDigest: 'd'.repeat(64) }
      ])
      return
    }
    if (/^\/api\/v1\/assets\/versions\/[a-f0-9]{64}\/file$/.test(path)) {
      const content = request.url().includes(oldDigest)
        ? '---\nname: document-review\n---\n# 文档评审\n\n旧版检查规则。'
        : '---\nname: document-review\n---\n# 文档评审\n\n新版检查规则与发布规范。'
      await route.fulfill({ status: 200, contentType: 'text/plain; charset=utf-8', body: content })
      return
    }
    if (path === '/api/v1/skills/kmsoft/document-review/versions/compare') {
      await fulfillJson(route, {
        fromDigest: oldDigest,
        toDigest: digest,
        fromVersion: '2.0.0',
        toVersion: '2.1.0',
        files: [{ path: 'SKILL.md', changeType: 'MODIFIED', fromDigest: 'f'.repeat(64), toDigest: 'c'.repeat(64) }]
      })
      return
    }
    if (path === '/api/v1/assets/imports/package/validate') {
      await fulfillJson(route, {
        valid: true,
        name: '文档评审',
        description: '检查企业文档结构和发布规范',
        versionLabel: '2.1.0',
        artifactDigest: 'b'.repeat(64),
        versionDigest: digest,
        expandedSizeBytes: 2048,
        manifest: [
          { path: 'SKILL.md', sizeBytes: 1024, contentDigest: 'c'.repeat(64) },
          { path: 'README.md', sizeBytes: 1024, contentDigest: 'd'.repeat(64) }
        ]
      })
      return
    }
    if (path === '/api/v1/assets/imports/package') {
      await fulfillJson(route, { requestId: 'browser-check', status: 'SUCCEEDED', assetId: 7, versionDigest: digest })
      return
    }
    if (path === '/api/v1/reviews/12/approve') {
      await fulfillJson(route, {
        id: 12,
        versionDigest: digest,
        assetId: 7,
        scopeId: 1,
        assetName: '知识库整理',
        versionLabel: '1.3.0',
        applicantId: 'user',
        reviewerId: 'admin',
        status: 'APPROVED',
        reviewComment: '内容与发布规范一致'
      })
      return
    }
    if (path === '/api/v1/tokens' && request.method() === 'GET') {
      await fulfillJson(route, [])
      return
    }
    if (path === '/api/v1/tokens' && request.method() === 'POST') {
      await fulfillJson(route, {
        id: 21,
        name: '持续集成发布',
        token: 'skh_once_only_example',
        tokenPrefix: 'skh_once',
        scopes: ['skill:read', 'skill:publish'],
        createdAt: '2026-09-04T02:00:00Z'
      })
      return
    }
    if (path === '/api/v1/session/logout') {
      await route.fulfill({ status: 204 })
      return
    }

    await fulfillJson(route, { message: `浏览器验证未配置接口：${request.method()} ${path}` }, 501)
  })
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}

test.beforeEach(async ({ page }) => {
  await mockApi(page)
})

test('桌面端应用壳、业务导航和账户菜单可用', async ({ page, isMobile }, testInfo) => {
  test.skip(isMobile, '桌面端用例')
  await page.goto('/dashboard')

  await expect(page.getByRole('heading', { name: '工作概览' })).toBeVisible()
  const sidebar = page.getByRole('complementary', { name: '主导航' })
  await expect(sidebar).toContainText('我的技能')
  await expect(sidebar).toContainText('访问凭证')
  await expect(sidebar).not.toContainText('推广管理')
  await expect(sidebar).not.toContainText('举报管理')
  await expect(sidebar).not.toContainText('收藏')

  await page.getByRole('button', { name: /admin/ }).click()
  await expect(page.getByRole('menu')).toContainText('个人设置')
  await expect(page.getByRole('menu')).toContainText('安全设置')
  await expect(page.getByRole('menu')).toContainText('访问凭证')
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: testInfo.outputPath('dashboard-desktop.png'), fullPage: true })
})

test('发布页完成服务端预检、确认弹窗和发布反馈', async ({ page, isMobile }, testInfo) => {
  test.skip(isMobile, '桌面端用例')
  await page.goto('/dashboard/publish')

  await page.locator('input[type="file"][accept]').setInputFiles({
    name: 'document-review.zip',
    mimeType: 'application/zip',
    buffer: Buffer.from('browser-contract-fixture')
  })
  await expect(page.getByText('发布前检查通过')).toBeVisible()
  await page.getByRole('button', { name: /发布信息/ }).click()
  await expect(page.getByLabel('技能名称')).toHaveValue('文档评审')
  await expect(page.getByText('SKILL.md', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '确认发布' }).click()
  const dialog = page.getByRole('dialog', { name: '确认发布技能' })
  await expect(dialog).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('publish-dialog-desktop.png'), fullPage: true })
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()

  await page.getByRole('button', { name: '确认发布' }).click()
  await dialog.getByRole('button', { name: '继续发布' }).click()
  await expect(page.getByRole('heading', { name: '已提交发布' })).toBeVisible()
  await expect(page.getByText('成功', { exact: true })).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('移动端侧栏按需展开且页面无横向溢出', async ({ page, isMobile }, testInfo) => {
  test.skip(!isMobile, '移动端用例')
  await page.goto('/search')

  await expect(page.getByRole('heading', { name: '搜索技能' })).toBeVisible()
  const sidebar = page.getByRole('complementary', { name: '主导航' })
  const closedBox = await sidebar.boundingBox()
  expect(closedBox?.x).toBeLessThan(0)

  await page.getByRole('button', { name: '打开导航' }).click()
  await expect.poll(async () => (await sidebar.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0)
  await expect(sidebar).toContainText('治理中心')
  await page.screenshot({ path: testInfo.outputPath('search-mobile-navigation.png'), fullPage: true })

  await page.getByRole('button', { name: '关闭导航' }).click()
  await expect.poll(async () => (await sidebar.boundingBox())?.x ?? 0).toBeLessThan(0)
  await expectNoHorizontalOverflow(page)
})

test('技能详情支持文件预览和逐行版本比较', async ({ page, isMobile }, testInfo) => {
  test.skip(isMobile, '桌面端用例')
  await page.goto('/space/kmsoft/document-review')

  await expect(page.locator('.skill-detail-title h1')).toHaveText('文档评审')
  await expect(page.getByText('新版检查规则与发布规范。', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: /文件/ }).click()
  await page.getByRole('treeitem', { name: /policy.md/ }).click()
  const fileDialog = page.getByRole('dialog', { name: 'references/policy.md' })
  await expect(fileDialog).toContainText('新版检查规则与发布规范。')
  await fileDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.getByRole('tab', { name: /版本/ }).click()
  await page.getByRole('link', { name: '开始比较' }).click()
  await expect(page.getByRole('heading', { name: '版本比较' })).toBeVisible()
  await expect(page.getByRole('table', { name: '逐行文件差异' })).toContainText('新版检查规则与发布规范。')
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: testInfo.outputPath('version-compare-desktop.png'), fullPage: true })
})

test('审核详情要求填写意见并显示二次确认', async ({ page, isMobile }, testInfo) => {
  test.skip(isMobile, '桌面端用例')
  await page.goto('/dashboard/reviews/12')

  await expect(page.getByRole('heading', { name: '知识库整理' })).toBeVisible()
  await page.getByRole('button', { name: '通过审核' }).click()
  const dialog = page.getByRole('dialog', { name: '确认通过审核' })
  await dialog.getByRole('button', { name: '通过审核' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('请填写审核意见')
  await dialog.getByLabel('审核意见').fill('内容与发布规范一致')
  await page.screenshot({ path: testInfo.outputPath('review-dialog-desktop.png'), fullPage: true })
  await dialog.getByRole('button', { name: '通过审核' }).click()
  await expect(page).toHaveURL(/\/dashboard\/reviews$/)
})

test('设置页签和访问凭证一次性展示链路可用', async ({ page, isMobile }, testInfo) => {
  test.skip(isMobile, '桌面端用例')
  await page.goto('/settings')

  await page.getByRole('tab', { name: '通知设置' }).click()
  await expect(page.getByRole('heading', { name: '通知偏好' })).toBeVisible()
  await expect(page.getByRole('checkbox')).toHaveCount(3)
  await expect(page.getByRole('checkbox').first()).toBeDisabled()

  await page.goto('/account/tokens')
  await page.getByRole('button', { name: '创建新 Token' }).click()
  const createDialog = page.getByRole('dialog')
  await expect(createDialog.getByRole('heading', { name: '创建新 Token' })).toBeVisible()
  await createDialog.getByLabel('Token 名称').fill('持续集成发布')
  await createDialog.getByRole('checkbox', { name: /资产发布/ }).check()
  await createDialog.getByRole('button', { name: '创建', exact: true }).click()
  await expect(createDialog.getByRole('heading', { name: 'Token 创建成功' })).toBeVisible()
  await expect(createDialog.getByText('skh_once_only_example')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('token-created-desktop.png'), fullPage: true })
  await expectNoHorizontalOverflow(page)
})
