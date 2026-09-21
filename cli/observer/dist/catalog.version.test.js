import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { computeSkillPackageDigest, normalizeVersionDigest, normalizeVersionLabel, parseSkillFile, resolveSkillVersion } from './catalog.js';
import { toIngestStep } from './ingest.js';
import { buildTimeline } from './timeline.js';
test('parseSkillFile reads version label and digest from frontmatter', () => {
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
    assert.equal(skill.versionDigest, 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789');
});
test('parseSkillFile computes SKILL.md digest when frontmatter digest is missing', () => {
    const content = '---\nname: bare-skill\nversion: 2.0.0\n---\n# bare\n';
    const skill = parseSkillFile('/tmp/bare/SKILL.md', content);
    assert.ok(skill);
    assert.equal(skill.versionLabel, '2.0.0');
    const expected = createHash('sha256')
        .update(`SKILL.md\n${createHash('sha256').update(Buffer.from(content, 'utf8')).digest('hex')}\n`)
        .digest('hex');
    assert.equal(skill.versionDigest, expected);
    assert.equal(skill.versionDigest, computeSkillPackageDigest([{ path: 'SKILL.md', content }]));
});
test('computeSkillPackageDigest sorts posix paths and hashes each file line', () => {
    const digest = computeSkillPackageDigest([
        { path: 'scripts\\run.js', content: 'b' },
        { path: 'SKILL.md', content: 'a' }
    ]);
    const expected = createHash('sha256')
        .update(`SKILL.md\n${createHash('sha256').update('a').digest('hex')}\n` +
        `scripts/run.js\n${createHash('sha256').update('b').digest('hex')}\n`)
        .digest('hex');
    assert.equal(digest, expected);
});
test('normalize helpers reject non-semver and non-hex values', () => {
    assert.equal(normalizeVersionLabel('v1.0.0'), '1.0.0');
    assert.equal(normalizeVersionLabel('1.0'), undefined);
    assert.equal(normalizeVersionLabel('latest'), undefined);
    assert.equal(normalizeVersionDigest('DEADBEEF'), undefined);
    assert.equal(normalizeVersionDigest('a'.repeat(64)), 'a'.repeat(64));
});
test('skill events keep version fields and ingest forwards observed version', () => {
    const skillPath = '/home/u/.claude/skills/demo-skill/SKILL.md';
    const versionLabel = '3.1.4';
    const versionDigest = 'f'.repeat(64);
    const event = {
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
        skill_version_label: versionLabel,
        skill_version_digest: versionDigest,
        source: 'scan',
        payload: {
            name: 'demo-skill',
            outcome: 'ok',
            skill_version_label: versionLabel,
            skill_version_digest: versionDigest,
            path: skillPath
        }
    };
    const sessions = buildTimeline([event]);
    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].title, 'Demo session');
    const step = sessions[0].turns[0].steps[0];
    assert.equal(step.skill_version_label, versionLabel);
    assert.equal(step.skill_version_digest, versionDigest);
    const ingestStep = toIngestStep(step);
    assert.equal(ingestStep.skillSlug, 'demo-skill');
    assert.equal(ingestStep.skillVersionLabel, versionLabel);
    assert.equal(ingestStep.skillVersionDigest, versionDigest);
    assert.equal(ingestStep.skillVersionSource, 'observed');
    const payload = ingestStep.payload;
    assert.equal(payload.skillVersionLabel, versionLabel);
    assert.equal(payload.skillVersionDigest, versionDigest);
    assert.equal(payload.skillVersionSource, 'observed');
});
test('resolveSkillVersion matches by path then slug', () => {
    const skills = [
        {
            slug: 'demo-skill',
            name: 'demo-skill',
            path: '/x/demo-skill/SKILL.md',
            versionLabel: '0.1.0',
            versionDigest: '1'.repeat(64)
        }
    ];
    const byPath = resolveSkillVersion(skills, { slug: 'other', path: '/x/demo-skill/SKILL.md' });
    assert.equal(byPath.versionLabel, '0.1.0');
    const bySlug = resolveSkillVersion(skills, { slug: 'demo-skill' });
    assert.equal(bySlug.versionDigest, '1'.repeat(64));
    const missing = resolveSkillVersion(skills, { slug: 'unknown' });
    assert.equal(missing.versionLabel, undefined);
});
