/**
 * Unit tests for skillhub-cli version gate (SemVer, digest, bump check).
 * Run: npm test  (build + node scripts/version-gate.test.mjs)
 */
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'

import {
  compareSemver,
  isInvalidVersionLabel,
  latestFormalVersion,
  normalizeVersionLabel,
  parseSemver,
  stripVersionPrefix
} from '../dist/services/semver.js'
import { computeVersionDigest, sha256Hex } from '../dist/services/version-digest.js'
import { assertVersionBumpRequired } from '../dist/services/version-gate.js'
import { prepareSkillPackage } from '../dist/services/skill-package-service.js'
import { VERSION_GATE_ERROR_CODES } from '../dist/shared/constants.js'
import { PackageValidationError } from '../dist/shared/errors.js'

const encoder = new TextEncoder()
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

function skillMd({ name = 'Demo Skill', description = 'demo', version } = {}) {
  const versionLine = version === undefined ? '' : `version: ${version}\n`
  return `---\nname: ${name}\ndescription: ${description}\n${versionLine}---\n\n# ${name}\n`
}

async function writeSkillDir(root, { files = {}, version = '1.0.0', name = 'Demo Skill' } = {}) {
  await mkdir(root, { recursive: true })
  await writeFile(join(root, 'SKILL.md'), skillMd({ name, version }), 'utf8')
  for (const [rel, content] of Object.entries(files)) {
    const target = join(root, rel)
    await mkdir(join(target, '..'), { recursive: true })
    await writeFile(target, content, 'utf8')
  }
}

// --- SemVer ---

await test('stripVersionPrefix removes leading v/V', () => {
  assert.equal(stripVersionPrefix('v1.2.3'), '1.2.3')
  assert.equal(stripVersionPrefix('V2.0.0-rc.1'), '2.0.0-rc.1')
  assert.equal(stripVersionPrefix('1.0.0'), '1.0.0')
})

await test('isInvalidVersionLabel rejects empty/latest/invalid', () => {
  assert.equal(isInvalidVersionLabel(undefined), true)
  assert.equal(isInvalidVersionLabel(null), true)
  assert.equal(isInvalidVersionLabel(''), true)
  assert.equal(isInvalidVersionLabel('   '), true)
  assert.equal(isInvalidVersionLabel('latest'), true)
  assert.equal(isInvalidVersionLabel('Latest'), true)
  assert.equal(isInvalidVersionLabel('1.0'), true)
  assert.equal(isInvalidVersionLabel('abc'), true)
  assert.equal(isInvalidVersionLabel('01.0.0'), true)
  assert.equal(isInvalidVersionLabel('1.0.0'), false)
  assert.equal(isInvalidVersionLabel('v1.0.0'), false)
  assert.equal(isInvalidVersionLabel('1.2.3-beta.1+build.5'), false)
})

await test('normalizeVersionLabel strips v and rejects invalid', () => {
  assert.equal(normalizeVersionLabel('v1.2.3'), '1.2.3')
  assert.equal(normalizeVersionLabel('latest'), null)
  assert.equal(normalizeVersionLabel(''), null)
})

await test('compareSemver follows precedence', () => {
  assert.ok(compareSemver('1.0.0', '1.0.0') === 0)
  assert.ok(compareSemver('1.0.1', '1.0.0') > 0)
  assert.ok(compareSemver('1.1.0', '1.0.9') > 0)
  assert.ok(compareSemver('2.0.0', '1.9.9') > 0)
  assert.ok(compareSemver('v1.0.0', '1.0.0') === 0)
  // formal > prerelease of same core
  assert.ok(compareSemver('1.0.0', '1.0.0-rc.1') > 0)
  assert.ok(compareSemver('1.0.0-alpha', '1.0.0-beta') < 0)
  assert.ok(compareSemver('1.0.0-alpha.1', '1.0.0-alpha.2') < 0)
})

await test('latestFormalVersion ignores prereleases and invalid labels', () => {
  assert.equal(latestFormalVersion(['1.0.0', '2.0.0-rc.1', 'latest', '1.5.0']), '1.5.0')
  assert.equal(latestFormalVersion(['0.1.0', 'v0.2.0']), '0.2.0')
  assert.equal(latestFormalVersion(['1.0.0-rc.1']), null)
})

// --- versionDigest ---

await test('versionDigest matches documented algorithm', () => {
  const files = [
    { path: 'scripts/run.js', content: encoder.encode('console.log(1)\n') },
    { path: 'SKILL.md', content: encoder.encode(skillMd({ version: '1.0.0' })) }
  ]
  const lines = [...files]
    .sort((a, b) => Buffer.compare(Buffer.from(a.path, 'utf8'), Buffer.from(b.path, 'utf8')))
    .map((f) => `${f.path}\n${sha256Hex(f.content)}\n`)
  const expected = sha256Hex(encoder.encode(lines.join('')))
  assert.equal(computeVersionDigest(files), expected)
})

await test('versionDigest is not sha256(version label)', () => {
  const files = [{ path: 'SKILL.md', content: encoder.encode(skillMd({ version: '1.2.3' })) }]
  const digest = computeVersionDigest(files)
  assert.notEqual(digest, sha256Hex('1.2.3'))
  assert.notEqual(digest, sha256Hex('v1.2.3'))
})

await test('versionDigest excludes node_modules, .DS_Store, *.tmp, .git', () => {
  const base = [{ path: 'SKILL.md', content: encoder.encode(skillMd({ version: '1.0.0' })) }]
  const withJunk = [
    ...base,
    { path: 'node_modules/pkg/index.js', content: encoder.encode('x') },
    { path: '.DS_Store', content: encoder.encode('junk') },
    { path: 'notes.tmp', content: encoder.encode('tmp') },
    { path: '.git/config', content: encoder.encode('git') }
  ]
  assert.equal(computeVersionDigest(withJunk), computeVersionDigest(base))
})

await test('versionDigest changes when business content changes', () => {
  const a = [
    { path: 'SKILL.md', content: encoder.encode(skillMd({ version: '1.0.0' })) },
    { path: 'scripts/a.js', content: encoder.encode('a') }
  ]
  const b = [
    { path: 'SKILL.md', content: encoder.encode(skillMd({ version: '1.0.0' })) },
    { path: 'scripts/a.js', content: encoder.encode('b') }
  ]
  assert.notEqual(computeVersionDigest(a), computeVersionDigest(b))
})

await test('versionDigest is order-independent after path sort', () => {
  const filesA = [
    { path: 'b.txt', content: encoder.encode('b') },
    { path: 'a.txt', content: encoder.encode('a') }
  ]
  const filesB = [...filesA].reverse()
  assert.equal(computeVersionDigest(filesA), computeVersionDigest(filesB))
})

// --- prepareSkillPackage: SemVer required ---

const tempRoot = await mkdtemp(join(tmpdir(), 'skillhub-version-gate-'))

await test('prepareSkillPackage rejects missing version with VERSION_SEMVER_REQUIRED', async () => {
  const dir = join(tempRoot, 'missing-version')
  await mkdir(dir, { recursive: true })
  await writeFile(join(dir, 'SKILL.md'), skillMd({ version: undefined }), 'utf8')
  await expectPackageErrorAsync(() => prepareSkillPackage(dir), VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED)
})

await test('prepareSkillPackage rejects empty / latest / invalid version', async () => {
  for (const version of ['', 'latest', '1.0', 'not-a-version']) {
    const dir = join(tempRoot, `bad-version-${Math.random().toString(16).slice(2)}`)
    await mkdir(dir, { recursive: true })
    await writeFile(join(dir, 'SKILL.md'), skillMd({ version }), 'utf8')
    await expectPackageErrorAsync(() => prepareSkillPackage(dir), VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED)
  }
})

await test('prepareSkillPackage accepts SemVer and strips leading v', async () => {
  const dir = join(tempRoot, 'ok-version')
  await writeSkillDir(dir, { version: 'v2.3.4', files: { 'scripts/x.js': 'x' } })
  const prepared = await prepareSkillPackage(dir)
  assert.equal(prepared.metadata.version, '2.3.4')
  assert.equal(prepared.versionDigest.length, 64)
  assert.notEqual(prepared.versionDigest, sha256Hex('2.3.4'))
})

await test('prepareSkillPackage versionDigest stable for same content', async () => {
  const dirA = join(tempRoot, 'digest-a')
  const dirB = join(tempRoot, 'digest-b')
  await writeSkillDir(dirA, { version: '1.0.0', files: { 'assets/note.txt': 'hello' } })
  await writeSkillDir(dirB, { version: '1.0.0', files: { 'assets/note.txt': 'hello' } })
  const a = await prepareSkillPackage(dirA)
  const b = await prepareSkillPackage(dirB)
  assert.equal(a.versionDigest, b.versionDigest)
  assert.equal(a.metadata.version, '1.0.0')
})

await test('prepareSkillPackage composite without package version throws VERSION_SEMVER_REQUIRED', async () => {
  const dir = join(tempRoot, 'composite')
  await mkdir(join(dir, 'child-a'), { recursive: true })
  await mkdir(join(dir, 'child-b'), { recursive: true })
  await writeFile(join(dir, 'child-a', 'SKILL.md'), skillMd({ name: 'Child A', version: '1.0.0' }), 'utf8')
  await writeFile(join(dir, 'child-b', 'SKILL.md'), skillMd({ name: 'Child B', version: '1.0.0' }), 'utf8')
  const error = await expectPackageErrorAsync(() => prepareSkillPackage(dir), VERSION_GATE_ERROR_CODES.VERSION_SEMVER_REQUIRED)
  assert.ok(error.details?.example?.packageJson?.version === '1.0.0')
  assert.ok(typeof error.details?.example?.cli === 'string')
  assert.ok(typeof error.details?.hint === 'string')
  assert.ok(!error.details?.example?.skillMd)
  assert.ok(String(error.details?.hint || '').includes('不要创建根 SKILL.md'))
  assert.ok(String(error.details?.example?.cli || '').includes('--skill-version'))
})

await test('prepareSkillPackage composite with explicit version ensures README.md, does not invent SKILL.md', async () => {
  const dir = join(tempRoot, 'composite-ok')
  await mkdir(join(dir, 'child-a'), { recursive: true })
  await mkdir(join(dir, 'child-b'), { recursive: true })
  await writeFile(join(dir, 'child-a', 'SKILL.md'), skillMd({ name: 'Child A', version: '1.0.0' }), 'utf8')
  await writeFile(join(dir, 'child-b', 'SKILL.md'), skillMd({ name: 'Child B', version: '1.0.0' }), 'utf8')
  const prepared = await prepareSkillPackage(dir, {}, { version: 'v0.1.0', name: 'Composite' })
  assert.equal(prepared.metadata.version, '0.1.0')
  assert.equal(prepared.metadata.name, 'Composite')
  assert.ok(prepared.manifest.some((entry) => entry.path === 'README.md'))
  assert.ok(!prepared.manifest.some((entry) => entry.path === 'SKILL.md'))
  assert.ok(prepared.manifest.some((entry) => entry.path === 'child-a/SKILL.md'))
  assert.ok(prepared.manifest.some((entry) => entry.path === 'child-b/SKILL.md'))
})

await test('prepareSkillPackage composite keeps existing README.md and never adds root SKILL.md', async () => {
  const dir = join(tempRoot, 'composite-readme')
  await mkdir(join(dir, 'child-a'), { recursive: true })
  await writeFile(join(dir, 'child-a', 'SKILL.md'), skillMd({ name: 'Child A', version: '1.0.0' }), 'utf8')
  const originalReadme = '# Custom Composite\n\nExisting readme body.\n'
  await writeFile(join(dir, 'README.md'), originalReadme, 'utf8')
  const prepared = await prepareSkillPackage(dir, {}, { version: '1.2.3' })
  assert.equal(prepared.metadata.version, '1.2.3')
  assert.ok(prepared.manifest.some((entry) => entry.path === 'README.md'))
  assert.ok(!prepared.manifest.some((entry) => entry.path === 'SKILL.md'))
  const { readArchive } = await import('../dist/platform/archive.js')
  const files = readArchive(prepared.archive)
  const readme = files.find((file) => file.path === 'README.md')
  assert.ok(readme)
  assert.equal(new TextDecoder().decode(readme.content), originalReadme)
})

await test('prepareSkillPackage composite without any skill files rejects SKILL_FILE_REQUIRED', async () => {
  const dir = join(tempRoot, 'empty-docs')
  await mkdir(dir, { recursive: true })
  await writeFile(join(dir, 'notes.txt'), 'no skills', 'utf8')
  await expectPackageErrorAsync(() => prepareSkillPackage(dir, {}, { version: '1.0.0' }), 'SKILL_FILE_REQUIRED')
})

// --- version gate decisions ---

const meta = (version) => ({ name: 'Demo Skill', description: 'demo', version })
const digestOf = (label) => sha256Hex(`content-${label}`)

await test('gate allows when skill not on platform', async () => {
  await assertVersionBumpRequired({
    serviceUrl: 'http://example.test',
    metadata: meta('1.0.0'),
    versionDigest: digestOf('1.0.0'),
    fetchPlatformSkill: async () => null
  })
})

await test('gate allows when local digest matches a platform version (idempotent)', async () => {
  await assertVersionBumpRequired({
    serviceUrl: 'http://example.test',
    metadata: meta('1.0.1'),
    versionDigest: digestOf('same'),
    fetchPlatformSkill: async () => ({
      slug: 'demo-skill',
      name: 'Demo Skill',
      versions: [
        { versionLabel: '1.0.0', versionDigest: digestOf('same') },
        { versionLabel: '0.9.0', versionDigest: digestOf('old') }
      ]
    })
  })
})

await test('gate throws VERSION_DIGEST_CONFLICT on same label different content', async () => {
  const error = await expectPackageErrorAsync(() => assertVersionBumpRequired({
    serviceUrl: 'http://example.test',
    metadata: meta('1.0.0'),
    versionDigest: digestOf('local-new'),
    fetchPlatformSkill: async () => ({
      slug: 'demo-skill',
      name: 'Demo Skill',
      versions: [{ versionLabel: '1.0.0', versionDigest: digestOf('platform-old') }]
    })
  }), VERSION_GATE_ERROR_CODES.VERSION_DIGEST_CONFLICT)
  assert.ok(error.message.length > 0)
})

await test('gate throws VERSION_BUMP_REQUIRED when content changed but version not greater', async () => {
  const error = await expectPackageErrorAsync(() => assertVersionBumpRequired({
    serviceUrl: 'http://example.test',
    metadata: meta('1.0.0'),
    versionDigest: digestOf('local-changed'),
    fetchPlatformSkill: async () => ({
      slug: 'demo-skill',
      name: 'Demo Skill',
      versions: [
        { versionLabel: '1.1.0', versionDigest: digestOf('platform-latest') },
        { versionLabel: '0.9.0', versionDigest: digestOf('platform-old') }
      ]
    })
  }), VERSION_GATE_ERROR_CODES.VERSION_BUMP_REQUIRED)
  assert.equal(error.message, 'Skill 内容已修改但版本号未升，请先修改 SKILL.md 中的 version 再上传')
})

await test('gate allows when version is new and greater than latest formal', async () => {
  await assertVersionBumpRequired({
    serviceUrl: 'http://example.test',
    metadata: meta('v1.2.0'),
    versionDigest: digestOf('local-newer'),
    fetchPlatformSkill: async () => ({
      slug: 'demo-skill',
      name: 'Demo Skill',
      versions: [
        { versionLabel: '1.1.0', versionDigest: digestOf('platform-latest') },
        { versionLabel: '1.2.0-rc.1', versionDigest: digestOf('platform-rc') }
      ]
    })
  })
})

await test('gate skips check on platform/network error', async () => {
  await assertVersionBumpRequired({
    serviceUrl: 'http://example.test',
    metadata: meta('1.0.0'),
    versionDigest: digestOf('local'),
    fetchPlatformSkill: async () => {
      throw new Error('SERVICE_UNREACHABLE')
    }
  })
})

await test('gate skips check when serviceUrl missing', async () => {
  await assertVersionBumpRequired({
    serviceUrl: '',
    metadata: meta('1.0.0'),
    versionDigest: digestOf('local'),
    fetchPlatformSkill: async () => {
      throw new Error('should not be called')
    }
  })
})

await test('error codes are exported', () => {
  assert.deepEqual(VERSION_GATE_ERROR_CODES, {
    VERSION_SEMVER_REQUIRED: 'VERSION_SEMVER_REQUIRED',
    VERSION_BUMP_REQUIRED: 'VERSION_BUMP_REQUIRED',
    VERSION_DIGEST_CONFLICT: 'VERSION_DIGEST_CONFLICT'
  })
})

await test('manual digest cross-check with createHash', () => {
  const content = encoder.encode('hello')
  const path = 'SKILL.md'
  const line = `${path}\n${createHash('sha256').update(content).digest('hex')}\n`
  const expected = createHash('sha256').update(line, 'utf8').digest('hex')
  assert.equal(computeVersionDigest([{ path, content }]), expected)
})

await rm(tempRoot, { recursive: true, force: true })

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exitCode = 1
