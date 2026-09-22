import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { enrichSkillVersions, normalizeVersionLabel, parseSkillFile, resolvePackageVersionFromDir, resolveSkillVersion, versionLabelFromPackageContent, withCompositeParents } from './catalog.js';
import { backfillSkillVersionLabel, sessionHasUnversionedSkill, skillStepVersionLabel, toIngestStep } from './ingest.js';
import { buildTimeline } from './timeline.js';
test('parseSkillFile reads version label only (no content digest)', () => {
    const skill = parseSkillFile('/tmp/demo/SKILL.md', [
        '---',
        'name: demo-skill',
        'version: v1.2.3-beta.1',
        'version_digest: ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
        '---',
        '# demo'
    ].join('\n'));
    assert.ok(skill);
    assert.equal(skill.versionLabel, '1.2.3-beta.1');
    assert.equal('versionDigest' in skill, false);
});
test('parseSkillFile omits version when frontmatter has no SemVer version', () => {
    const skill = parseSkillFile('/tmp/bare/SKILL.md', '---\nname: bare-skill\nversion: latest\n---\n# bare\n');
    assert.ok(skill);
    assert.equal(skill.versionLabel, undefined);
});
test('normalizeVersionLabel rejects non-semver values', () => {
    assert.equal(normalizeVersionLabel('v1.0.0'), '1.0.0');
    assert.equal(normalizeVersionLabel('1.0'), undefined);
    assert.equal(normalizeVersionLabel('latest'), undefined);
    assert.equal(normalizeVersionLabel('ad8d764'), undefined);
});
function skillEvent(overrides = {}) {
    return {
        v: 1,
        event_id: 'e1',
        client_id: 'c1',
        client_name: 'claude-code',
        session_id: 's1',
        session_title: 'Demo session',
        turn_index: 1,
        step_id: 'step-1',
        seq: 1,
        type: 'skill',
        ts: '2026-01-01T00:00:00.000Z',
        skill_slug: 'demo-skill',
        skill_name: 'demo-skill',
        source: 'scan',
        payload: { name: 'demo-skill', outcome: 'ok' },
        ...overrides
    };
}
test('toIngestStep forwards version label only and strips digest fields', () => {
    const step = skillEvent({
        skill_version_label: '3.1.4',
        payload: {
            name: 'demo-skill',
            skill_version_label: '3.1.4',
            skill_version_digest: 'f'.repeat(64),
            skillVersionDigest: 'f'.repeat(64)
        }
    });
    const ingestStep = toIngestStep(step);
    assert.equal(ingestStep.skillVersionLabel, '3.1.4');
    assert.equal(ingestStep.skillVersionSource, 'observed');
    assert.equal('skillVersionDigest' in ingestStep, false);
    const payload = ingestStep.payload;
    assert.equal(payload.skillVersionLabel, '3.1.4');
    assert.equal(payload.skillVersionDigest, undefined);
    assert.equal(payload.skill_version_digest, undefined);
});
test('sessionHasUnversionedSkill discards sessions with unversioned skill steps', () => {
    const versioned = buildTimeline([skillEvent({ skill_version_label: '1.0.0' })]);
    assert.equal(sessionHasUnversionedSkill(versioned[0]), false);
    const unversioned = buildTimeline([
        skillEvent({ skill_version_label: undefined, payload: { name: 'demo-skill', skill_version_digest: 'a'.repeat(64) } })
    ]);
    assert.equal(sessionHasUnversionedSkill(unversioned[0]), true);
    const digestOnly = buildTimeline([
        skillEvent({ skill_version_label: undefined, payload: { name: 'demo-skill', skillVersionDigest: 'b'.repeat(64) } })
    ]);
    assert.equal(sessionHasUnversionedSkill(digestOnly[0]), true);
});
test('resolveSkillVersion matches by path then slug and returns label only', () => {
    const skills = [
        {
            slug: 'demo-skill',
            name: 'demo-skill',
            path: '/x/demo-skill/SKILL.md',
            versionLabel: '0.1.0'
        }
    ];
    const byPath = resolveSkillVersion(skills, { slug: 'other', path: '/x/demo-skill/SKILL.md' });
    assert.equal(byPath.versionLabel, '0.1.0');
    const bySlug = resolveSkillVersion(skills, { slug: 'demo-skill' });
    assert.equal(bySlug.versionLabel, '0.1.0');
    assert.equal('versionDigest' in bySlug, false);
    const missing = resolveSkillVersion(skills, { slug: 'unknown' });
    assert.equal(missing.versionLabel, undefined);
});
test('skillStepVersionLabel accepts payload label and rejects digest-looking values', () => {
    assert.equal(skillStepVersionLabel(skillEvent({ payload: { skillVersionLabel: 'v2.0.0' } })), '2.0.0');
    assert.equal(skillStepVersionLabel(skillEvent({ payload: { skillVersionDigest: 'abc' } })), undefined);
});
test('versionLabelFromPackageContent reads SemVer only', () => {
    assert.equal(versionLabelFromPackageContent('{"version":"1.0.1"}'), '1.0.1');
    assert.equal(versionLabelFromPackageContent('{"version":"v2.0.0"}'), '2.0.0');
    assert.equal(versionLabelFromPackageContent('{"version":"latest"}'), undefined);
    assert.equal(versionLabelFromPackageContent('not-json'), undefined);
    assert.equal(versionLabelFromPackageContent('﻿{"version":"1.0.1"}'), '1.0.1');
});
test('composite package inherits package.json version for child and parent', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-pkg-'));
    try {
        const childDir = join(root, 'skills', 'sop-setup');
        await mkdir(childDir, { recursive: true });
        await writeFile(join(root, 'package.json'), JSON.stringify({
            name: 'using-product-development',
            version: '1.0.1',
            category: '研发'
        }), 'utf8');
        await writeFile(join(childDir, 'SKILL.md'), [
            '---',
            'name: sop-setup',
            'description: init docs skeleton',
            '---',
            '# sop-setup'
        ].join('\n'), 'utf8');
        assert.equal(await resolvePackageVersionFromDir(childDir), '1.0.1');
        assert.equal(await resolvePackageVersionFromDir(root), '1.0.1');
        const child = parseSkillFile(join(childDir, 'SKILL.md'), await readFileUtf8(join(childDir, 'SKILL.md')));
        assert.ok(child);
        assert.equal(child.versionLabel, undefined);
        const withParents = withCompositeParents([child]);
        const parent = withParents.find((item) => item.slug === 'using-product-development');
        assert.ok(parent);
        const enriched = await enrichSkillVersions(withParents);
        const enrichedChild = enriched.find((item) => item.slug === 'sop-setup');
        const enrichedParent = enriched.find((item) => item.slug === 'using-product-development');
        assert.equal(enrichedChild?.versionLabel, '1.0.1');
        assert.equal(enrichedParent?.versionLabel, '1.0.1');
    }
    finally {
        await rm(root, { recursive: true, force: true });
    }
});
test('own SKILL.md version wins over package.json', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-pkg-own-'));
    try {
        const childDir = join(root, 'skills', 'sop-design');
        await mkdir(childDir, { recursive: true });
        await writeFile(join(root, 'package.json'), '{"version":"1.0.1"}', 'utf8');
        await writeFile(join(childDir, 'SKILL.md'), [
            '---',
            'name: sop-design',
            'version: 1.2.0',
            '---',
            '# sop-design'
        ].join('\n'), 'utf8');
        const child = parseSkillFile(join(childDir, 'SKILL.md'), await readFileUtf8(join(childDir, 'SKILL.md')));
        assert.ok(child);
        assert.equal(child.versionLabel, '1.2.0');
        const enriched = await enrichSkillVersions([child]);
        assert.equal(enriched[0].versionLabel, '1.2.0');
    }
    finally {
        await rm(root, { recursive: true, force: true });
    }
});
test('nearest package root wins even when version is missing', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-pkg-near-'));
    try {
        const inner = join(root, 'inner');
        const childDir = join(inner, 'skills', 'sop-review');
        await mkdir(childDir, { recursive: true });
        await writeFile(join(root, 'package.json'), '{"version":"9.9.9"}', 'utf8');
        await writeFile(join(inner, 'package.json'), '{"name":"inner"}', 'utf8');
        await writeFile(join(childDir, 'SKILL.md'), '---\nname: sop-review\n---\n# x\n', 'utf8');
        assert.equal(await resolvePackageVersionFromDir(childDir), undefined);
    }
    finally {
        await rm(root, { recursive: true, force: true });
    }
});
test('backfillSkillVersionLabel fills from catalog and keeps existing label', () => {
    const skills = [
        { slug: 'using-product-development', name: 'using-product-development', path: '/x/using-product-development', versionLabel: '1.0.1' },
        { slug: 'sop-setup', name: 'sop-setup', path: '/x/using-product-development/skills/sop-setup/SKILL.md', versionLabel: '1.0.1' }
    ];
    const missing = skillEvent({ skill_slug: 'sop-setup', skill_name: 'sop-setup', skill_version_label: undefined });
    const filled = backfillSkillVersionLabel(missing, skills);
    assert.equal(filled.skill_version_label, '1.0.1');
    assert.equal(sessionHasUnversionedSkill(buildTimeline([filled])[0]), false);
    const kept = skillEvent({ skill_slug: 'sop-setup', skill_version_label: '2.0.0' });
    assert.equal(backfillSkillVersionLabel(kept, skills).skill_version_label, '2.0.0');
});
async function readFileUtf8(path) {
    const { readFile } = await import('node:fs/promises');
    return readFile(path, 'utf8');
}
