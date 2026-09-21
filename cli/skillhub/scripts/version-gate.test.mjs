/**
 * Unit tests for skillhub-cli version gate (SemVer, package-local version, bump/exists).
 * Run: npm test  (build + node scripts/version-gate.test.mjs)
 */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import {
  compareSemver,
  isInvalidVersionLabel,
  latestFormalVersion,
  normalizeVersionLabel,
  parseSemver,
  stripVersionPrefix
} from '../dist/services/semver.js'
import { assertVersionBumpRequired } from '../dist/services/version-gate.js'
import { prepareSkillPackage } from '../dist/services/skill-package-service.js'
import { VERSION_GATE_ERROR_CODES } from '../dist/shared/constants.js'
import { PackageValidationError } from '../dist/shared/errors.js'

let passed = 0
let failed = 0

async function test(name, fn) {
  try {
    await fn()
    passed += 1
    console.log(`ok - ${name}`)
  } catch (error) {
    failed += 1
    console.error(`not ok - ${name}`)
    console.error(error)
  }
}

function expectPackageError(fn, code) {
  try {
    fn()
  } catch (error) {
    assert.ok(error instanceof PackageValidationError, `expected PackageValidationError, got ${error}`)
    assert.equal(error.code, code)
    return error
  }
  assert.fail(`expected ${code} to be thrown`)
}

async function expectPackageErrorAsync(fn, code) {
  try {
    await fn()
  } catch (error) {
    assert.ok(error instanceof PackageValidationError, `expected PackageValidationError, got ${error}`)
    assert.equal(error.code, code)
    return error
  }
  assert.fail(`expected ${code} to be thrown`)
}

function skillMd({ name = 'Demo Skill', description = 'demo', version, category } = {}) {
  const versionLine = version === undefined ? '' : `version: ${version}\n`
  const categoryLine = category === undefined ? '' : `category: ${category}\n`
  return `---\nname: ${name}\ndescription: ${description}\n${versionLine}${categoryLine}---\n\n# ${name}\n`
}

async function writeSkillDir(root, { files = {}, version, name = 'Demo Skill', category } = {}) {
  await mkdir(root, { recursive: true })
  await writeFile(join(root, 'SKILL.md'), skillMd({ name, version, category }), 'utf8')
  for (const [rel, content] of Object.entries(files)) {
    const target = join(root, rel)
    await mkdir(join(target, '..'), { recursive: true })
    await writeFile(target, content, 'utf8')
  }
}

// --- SemVer ---

await test('stripVersionPrefix removes leading v/V', () => {
  assert.equal(stripVersionPrefix('v1.2.3'), '1.2.3')
  assert.equal(stripVersionPrefix('V2.0.0'), '2.0.0')
})

await test('normalizeVersionLabel / parseSemver basics', () => {
  assert.equal(normalizeVersionLabel('v1.0.0'), '1.0.0')
  assert.equal(normalizeVersionLabel('latest'), null)
  assert.ok(parseSemver('1.0.0'))
  assert.ok(isInvalidVersionLabel(undefined))
  assert.equal(latestFormalVersion(['1.0.0', '2.0.0-rc.1', '1.2.0']), '1.2.0')
})

await test('compareSemver orders core versions', () => {
  assert.ok(compareSemver('1.2.3', '1.2.2') > 0)
  assert.ok(compareSemver('1.2.3', '1.10.0') < 0)
  assert.equal(compareSemver('1.2.3', '1.2.3'), 0)
})

// --- prepareSkillPackage: version from package content only ---

await test('single skill reads version and category from frontmatter', async () => {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-pkg-'))
  try {
    await writeSkillDir(root, { version: '1.2.3', category: '研发' })
    const prepared = await prepareSkillPackage(root)
    assert.equal(prepared.metadata.version, '1.2.3')
    assert.equal(prepared.metadata.category, '研发')
    assert.equal(prepared.metadata.name, 'Demo Skill')
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

await test('single skill missing version fails with VERSION_SEMVER_REQUIRED', async () => {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-pkg-'))
  try {
    await writeSkillDir(root, { version: undefined })
    await expectPackageErrorAsync(async () => prepareSkillPackage(root), VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

await test('composite package requires package.json version (no CLI injection)', async () => {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-pkg-'))
  try {
    // Two nested skills + root marker so package is not normalized to a single skill
    await mkdir(join(root, 'child-a'), { recursive: true })
    await mkdir(join(root, 'child-b'), { recursive: true })
    await writeFile(join(root, 'child-a', 'SKILL.md'), skillMd({ name: 'child-a', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'child-b', 'SKILL.md'), skillMd({ name: 'child-b', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'notes.txt'), 'composite root marker', 'utf8')
    await expectPackageErrorAsync(async () => prepareSkillPackage(root), VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED)
    await writeFile(join(root, 'package.json'), JSON.stringify({
      name: 'composite-demo',
      description: '复合包',
      version: '2.0.0',
      category: '工具'
    }), 'utf8')
    const prepared = await prepareSkillPackage(root)
    assert.equal(prepared.metadata.version, '2.0.0')
    assert.equal(prepared.metadata.category, '工具')
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

await test('metadataOverrides does not inject composite version', async () => {
  const root = await mkdtemp(join(tmpdir(), 'skillhub-pkg-'))
  try {
    await mkdir(join(root, 'child-a'), { recursive: true })
    await mkdir(join(root, 'child-b'), { recursive: true })
    await writeFile(join(root, 'child-a', 'SKILL.md'), skillMd({ name: 'child-a', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'child-b', 'SKILL.md'), skillMd({ name: 'child-b', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'notes.txt'), 'composite root marker', 'utf8')
    await expectPackageErrorAsync(
      async () => prepareSkillPackage(root, {}, { name: 'x', description: 'y' }),
      VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

// --- version gate: label-only identity ---

await test('same version_label on platform → VERSION_EXISTS', async () => {
  await expectPackageErrorAsync(
    () => assertVersionBumpRequired({
      serviceUrl: 'http://127.0.0.1:8080',
      metadata: { name: 'Demo', description: 'd', version: '1.0.0' },
      fetchPlatformSkill: async () => ({
        slug: 'demo',
        name: 'Demo',
        versions: [{ versionLabel: '1.0.0' }, { versionLabel: '1.0.1' }]
      })
    }),
    VERSION_GATE_ERROR_CODES.VERSION_EXISTS
  )
})

await test('version not greater than latest formal → VERSION_BUMP_REQUIRED', async () => {
  const error = await expectPackageErrorAsync(
    () => assertVersionBumpRequired({
      serviceUrl: 'http://127.0.0.1:8080',
      metadata: { name: 'Demo', description: 'd', version: '1.0.0' },
      fetchPlatformSkill: async () => ({
        slug: 'demo',
        name: 'Demo',
        versions: [{ versionLabel: '1.0.1' }]
      })
    }),
    VERSION_GATE_ERROR_CODES.VERSION_BUMP_REQUIRED
  )
  assert.equal(error.details.suggestedNextVersion, '1.0.2')
})

await test('new greater version allows upload', async () => {
  await assertVersionBumpRequired({
    serviceUrl: 'http://127.0.0.1:8080',
    metadata: { name: 'Demo', description: 'd', version: '1.0.2' },
    fetchPlatformSkill: async () => ({
      slug: 'demo',
      name: 'Demo',
      versions: [{ versionLabel: '1.0.0' }, { versionLabel: '1.0.1' }]
    })
  })
})

await test('skill not on platform allows first upload', async () => {
  await assertVersionBumpRequired({
    serviceUrl: 'http://127.0.0.1:8080',
    metadata: { name: 'Demo', description: 'd', version: '1.0.0' },
    fetchPlatformSkill: async () => null
  })
})

await test('gate module exports do not include digest codes', async () => {
  assert.equal('VERSION_DIGEST_CONFLICT' in VERSION_GATE_ERROR_CODES, false)
  assert.equal(VERSION_GATE_ERROR_CODES.VERSION_EXISTS, 'VERSION_EXISTS')
})

// --- write-back / verify-source ---

await test('verify-source fails when composite source lacks package.json on disk', async () => {
  const { verifySourcePaths } = await import('../dist/commands/upload.js')
  const root = await mkdtemp(join(tmpdir(), 'skillhub-src-'))
  try {
    await mkdir(join(root, 'child-a'), { recursive: true })
    await mkdir(join(root, 'child-b'), { recursive: true })
    await writeFile(join(root, 'child-a', 'SKILL.md'), skillMd({ name: 'child-a', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'child-b', 'SKILL.md'), skillMd({ name: 'child-b', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'notes.txt'), 'marker', 'utf8')
    const result = await verifySourcePaths([root])
    assert.equal(result.ok, false)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

await test('verify-source passes when composite source has package.json', async () => {
  const { verifySourcePaths } = await import('../dist/commands/upload.js')
  const root = await mkdtemp(join(tmpdir(), 'skillhub-src-'))
  try {
    await mkdir(join(root, 'child-a'), { recursive: true })
    await mkdir(join(root, 'child-b'), { recursive: true })
    await writeFile(join(root, 'child-a', 'SKILL.md'), skillMd({ name: 'child-a', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'child-b', 'SKILL.md'), skillMd({ name: 'child-b', version: '1.0.0' }), 'utf8')
    await writeFile(join(root, 'package.json'), JSON.stringify({
      name: 'demo-composite',
      description: 'demo',
      version: '1.0.0',
      category: '研发'
    }), 'utf8')
    const result = await verifySourcePaths([root])
    assert.equal(result.ok, true)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

await test('prepare --complete-from writes back temp package.json to source and deletes temp', async () => {
  const { prepareCommand, verifySourcePaths } = await import('../dist/commands/upload.js')
  const source = await mkdtemp(join(tmpdir(), 'skillhub-src-'))
  const temp = await mkdtemp(join(tmpdir(), 'skillhub-temp-'))
  try {
    await mkdir(join(source, 'child-a'), { recursive: true })
    await mkdir(join(source, 'child-b'), { recursive: true })
    await writeFile(join(source, 'child-a', 'SKILL.md'), skillMd({ name: 'child-a', version: '1.0.0' }), 'utf8')
    await writeFile(join(source, 'child-b', 'SKILL.md'), skillMd({ name: 'child-b', version: '1.0.0' }), 'utf8')
    await writeFile(join(source, 'notes.txt'), 'marker', 'utf8')

    await mkdir(join(temp, 'child-a'), { recursive: true })
    await mkdir(join(temp, 'child-b'), { recursive: true })
    await writeFile(join(temp, 'child-a', 'SKILL.md'), skillMd({ name: 'child-a', version: '1.0.0' }), 'utf8')
    await writeFile(join(temp, 'child-b', 'SKILL.md'), skillMd({ name: 'child-b', version: '1.0.0' }), 'utf8')
    await writeFile(join(temp, 'notes.txt'), 'marker', 'utf8')
    await writeFile(join(temp, 'package.json'), JSON.stringify({
      name: 'demo-composite',
      description: 'demo',
      version: '1.0.1',
      category: '研发'
    }), 'utf8')

    const before = await verifySourcePaths([source])
    assert.equal(before.ok, false)

    const output = JSON.parse(await prepareCommand({
      inputPath: source,
      completeFrom: temp,
      json: true
    }))
    assert.equal(output.ok, true)
    assert.equal(output.completeFromDeleted, true)

    const { access } = await import('node:fs/promises')
    let tempStillExists = true
    try { await access(temp) } catch { tempStillExists = false }
    assert.equal(tempStillExists, false)

    const after = await verifySourcePaths([source])
    assert.equal(after.ok, true)
    const { readFile } = await import('node:fs/promises')
    const packageJson = JSON.parse(await readFile(join(source, 'package.json'), 'utf8'))
    assert.equal(packageJson.version, '1.0.1')
    assert.equal(packageJson.category, '研发')
  } finally {
    await rm(source, { recursive: true, force: true })
    await rm(temp, { recursive: true, force: true })
  }
})

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
