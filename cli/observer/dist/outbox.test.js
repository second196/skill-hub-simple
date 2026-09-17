import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { sessionMatches } from './scan.js';
import { ingestSessionKey, sessionSafeId } from './identity.js';
import { ackSession, enqueueSession, hashEvents, listSpoolJobs, loadState, readSpoolJob } from './store.js';
import { atomicWriteFile } from './atomic.js';
import { drainQueue, hashFile } from './drain.js';
import { resolveServiceUrl } from './config.js';
import { installBootTasks, setScheduledRunner } from './schedule.js';
test('matches exact session ids and paths without substring false positives', () => {
    assert.equal(sessionMatches('abc', 'abc', '/tmp/abc.jsonl', 'codex'), true);
    assert.equal(sessionMatches('abc', 'abc-2', '/tmp/abc-2.jsonl', 'codex'), false);
    assert.equal(sessionMatches('abc', 'x', '/tmp/folder-abc/session.jsonl', 'codex'), false);
    assert.equal(sessionMatches('/abs/path/abc.jsonl', 'other', '/abs/path/abc.jsonl', 'codex'), true);
    assert.equal(sessionMatches('parent/subagents/child', 'child', '/tmp/parent/subagents/child.jsonl', 'claude-code'), true);
    assert.equal(sessionMatches('parent', 'parent/subagents/child', '/tmp/parent/subagents/child.jsonl', 'claude-code'), false);
    assert.equal(sessionMatches('child', 'parent/subagents/child', '/tmp/parent/subagents/child.jsonl', 'claude-code'), false);
    assert.equal(sessionMatches('parent/subagents/child', 'parent/subagents/child', 'C:\\tmp\\parent\\subagents\\child.jsonl', 'claude-code'), true);
});
test('builds stable filesystem and ingest keys', () => {
    const a = sessionSafeId('claude-code', 'parent/subagents/child');
    const b = sessionSafeId('codex', 'parent/subagents/child');
    assert.notEqual(a, b);
    assert.match(a, /^[0-9a-f]{64}$/);
    assert.equal(ingestSessionKey('codex', 'short'), 'codex:short');
    const longId = 'x'.repeat(300);
    const hashed = ingestSessionKey('claude-code', longId);
    assert.ok(hashed.startsWith('claude-code:sha256:'));
    assert.ok(Buffer.byteLength(hashed) <= 256);
});
test('concurrent enqueue keeps both spool jobs and empty events are not acked', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-store-'));
    const previous = {
        observability: process.env.SKILLHUB_OBSERVABILITY_DIR,
        claude: process.env.SKILLHUB_CLAUDE_PROJECTS,
        codex: process.env.SKILLHUB_CODEX_SESSIONS
    };
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    process.env.SKILLHUB_CLAUDE_PROJECTS = join(root, 'claude');
    process.env.SKILLHUB_CODEX_SESSIONS = join(root, 'codex');
    const originalFetch = globalThis.fetch;
    try {
        await mkdir(join(root, 'claude'), { recursive: true });
        await mkdir(join(root, 'codex'), { recursive: true });
        const eventA = sampleEvent('codex', 'sess-a');
        const eventB = sampleEvent('codex', 'sess-b');
        await Promise.all([
            enqueueSession({ clientName: 'codex', sessionId: 'sess-a', snapshotEvents: [eventA], ended: true }),
            enqueueSession({ clientName: 'codex', sessionId: 'sess-b', snapshotEvents: [eventB], ended: true })
        ]);
        const jobs = await listSpoolJobs();
        assert.equal(jobs.length, 2);
        assert.deepEqual(new Set(jobs.map((job) => job.sessionId)), new Set(['sess-a', 'sess-b']));
        const empty = await enqueueSession({ clientName: 'codex', sessionId: 'empty-session', ended: true });
        assert.equal(empty.eventCount, 0);
        globalThis.fetch = (async () => {
            throw new Error('HTTP should not run for empty events');
        });
        const result = await drainQueue({ serviceUrl: 'http://127.0.0.1:9' });
        assert.match(result, /failed=/);
        const leftover = await readSpoolJob(empty.safeId);
        assert.ok(leftover, 'empty job must remain in spool');
        assert.ok(leftover?.lastError);
        const currentA = await readSpoolJob(jobs[0].safeId);
        assert.ok(currentA, 'drain failure must keep spool');
        await ackSession(currentA);
        assert.equal(await readSpoolJob(jobs[0].safeId), undefined);
        const state = await loadState();
        assert.ok(state.sessions[`codex:${jobs[0].sessionId}`]);
        assert.ok(await readSpoolJob(jobs[1].safeId));
    }
    finally {
        globalThis.fetch = originalFetch;
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous.observability);
        restoreEnv('SKILLHUB_CLAUDE_PROJECTS', previous.claude);
        restoreEnv('SKILLHUB_CODEX_SESSIONS', previous.codex);
        await rm(root, { recursive: true, force: true });
    }
});
test('ack stores source hash so unchanged files are not requeued', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-ack-'));
    const previous = process.env.SKILLHUB_OBSERVABILITY_DIR;
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    try {
        const sourcePath = join(root, 'session.jsonl');
        await writeFile(sourcePath, '{"type":"user"}\n', 'utf8');
        const event = sampleEvent('codex', 'ack-session');
        const sourceHash = await hashFile(sourcePath);
        const job = await enqueueSession({
            clientName: 'codex',
            sessionId: 'ack-session',
            sourcePath,
            snapshotEvents: [event],
            ended: true,
            sourceHash,
            contentHash: hashEvents([event])
        });
        await ackSession(job);
        assert.equal(await readSpoolJob(job.safeId), undefined);
        const state = await loadState();
        const ack = state.sessions['codex:ack-session'];
        assert.equal(ack?.sourceHash, sourceHash);
        assert.equal(ack?.contentHash, hashEvents([event]));
        assert.notEqual(ack?.sourceHash, ack?.contentHash);
    }
    finally {
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous);
        await rm(root, { recursive: true, force: true });
    }
});
test('atomic write replaces existing files on Windows-style targets', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-atomic-'));
    try {
        const target = join(root, 'state.json');
        await writeFile(target, '{"old":true}\n', 'utf8');
        await atomicWriteFile(target, '{"new":true}\n');
        assert.equal(await readFile(target, 'utf8'), '{"new":true}\n');
        await atomicWriteFile(target, '{"again":true}\n');
        assert.equal(await readFile(target, 'utf8'), '{"again":true}\n');
    }
    finally {
        await rm(root, { recursive: true, force: true });
    }
});
test('install boot tasks is idempotent when scheduler spawn is mocked', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-boot-'));
    const previous = process.env.SKILLHUB_OBSERVABILITY_DIR;
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    const calls = [];
    setScheduledRunner(async (command, args) => {
        calls.push([command, ...args]);
        return '';
    });
    try {
        await mkdir(join(root, 'bin'), { recursive: true });
        const first = await installBootTasks();
        const second = await installBootTasks();
        assert.match(first, /Windows|LaunchAgent|systemd|crontab/);
        assert.equal(first.split('\n')[0], second.split('\n')[0]);
        assert.ok(calls.length >= 2);
        if (process.platform === 'win32') {
            const cmd = await readFile(join(root, 'bin', 'drain.cmd'), 'utf8');
            assert.match(cmd, /drain --reconcile/);
            const vbs = await readFile(join(root, 'bin', 'drain-hidden.vbs'), 'utf8');
            assert.match(vbs, /Wscript\.Shell/i);
            assert.match(vbs, /drain --reconcile/);
            assert.match(vbs, /\.Run cmd, 0, False/);
            assert.ok(calls.every((item) => item[0] === 'schtasks'));
            assert.ok(calls.some((item) => item.includes('/F')));
            assert.ok(calls.some((item) => item.includes('SkillHub Observer Drain OnLogon')));
            assert.ok(calls.some((item) => item.includes('SkillHub Observer Drain Interval')));
            // Scheduled tasks must use the hidden VBS launcher, not the console .cmd
            const trValues = calls
                .map((item) => {
                const idx = item.indexOf('/TR');
                return idx >= 0 ? item[idx + 1] || '' : '';
            })
                .filter(Boolean);
            assert.ok(trValues.length >= 2);
            assert.ok(trValues.every((tr) => tr.includes('drain-hidden.vbs')));
            assert.ok(trValues.every((tr) => tr.includes('wscript.exe')));
        }
    }
    finally {
        setScheduledRunner();
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous);
        await rm(root, { recursive: true, force: true });
    }
});
test('missing service url fails instead of silently defaulting', () => {
    delete process.env.SKILLHUB_SERVICE_URL;
    assert.throws(() => resolveServiceUrl(''), /config set/);
    assert.equal(resolveServiceUrl('http://example.test/'), 'http://example.test');
    process.env.SKILLHUB_SERVICE_URL = 'http://env-override.test:9090/';
    assert.equal(resolveServiceUrl('http://ignored.test'), 'http://env-override.test:9090');
    delete process.env.SKILLHUB_SERVICE_URL;
});
test('config set host port rebuilds service url', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-config-'));
    const previous = process.env.SKILLHUB_OBSERVABILITY_DIR;
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    try {
        const { buildServiceUrl, loadConfig, parseServiceUrl, saveConfig } = await import('./config.js');
        assert.equal(buildServiceUrl({ host: '10.0.0.8', port: 9090 }), 'http://10.0.0.8:9090');
        const parsed = parseServiceUrl('https://api.example.com:8443/');
        assert.deepEqual(parsed, { protocol: 'https', host: 'api.example.com', port: 8443 });
        await saveConfig({ serviceUrl: 'http://192.168.1.20:8080' });
        let config = await loadConfig();
        assert.equal(config.serviceUrl, 'http://192.168.1.20:8080');
        assert.equal(config.host, '192.168.1.20');
        assert.equal(config.port, 8080);
        await saveConfig({ host: '10.1.2.3', port: 18080 });
        config = await loadConfig();
        assert.equal(config.host, '10.1.2.3');
        assert.equal(config.port, 18080);
        assert.equal(config.serviceUrl, 'http://10.1.2.3:18080');
    }
    finally {
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous);
        await rm(root, { recursive: true, force: true });
    }
});
test('old ack does not delete a newer spool version written during upload', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-ack-race-'));
    const previous = process.env.SKILLHUB_OBSERVABILITY_DIR;
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    try {
        const eventV1 = sampleEvent('codex', 'race-session');
        const jobV1 = await enqueueSession({
            clientName: 'codex',
            sessionId: 'race-session',
            snapshotEvents: [eventV1],
            ended: false
        });
        assert.equal(jobV1.version, 1);
        const eventV2 = {
            ...sampleEvent('codex', 'race-session'),
            event_id: 'race-session-event-2',
            step_id: 'race-session-step-2',
            seq: 2,
            payload: { text: 'newer stop event' }
        };
        const jobV2 = await enqueueSession({
            clientName: 'codex',
            sessionId: 'race-session',
            snapshotEvents: [eventV2],
            ended: true
        });
        assert.equal(jobV2.version, 2);
        const result = await ackSession(jobV1);
        assert.equal(result.deleted, false);
        assert.match(result.reason || '', /version changed/);
        const leftover = await readSpoolJob(jobV1.safeId);
        assert.ok(leftover, 'newer spool must remain after stale ack');
        assert.equal(leftover?.version, 2);
        const result2 = await ackSession(jobV2);
        assert.equal(result2.deleted, true);
        assert.equal(await readSpoolJob(jobV2.safeId), undefined);
        const state = await loadState();
        assert.equal(state.sessions['codex:race-session']?.eventCount, 2);
    }
    finally {
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous);
        await rm(root, { recursive: true, force: true });
    }
});
test('network failure keeps spool and sets backoff', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-net-'));
    const previous = {
        observability: process.env.SKILLHUB_OBSERVABILITY_DIR,
        claude: process.env.SKILLHUB_CLAUDE_PROJECTS,
        codex: process.env.SKILLHUB_CODEX_SESSIONS
    };
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    process.env.SKILLHUB_CLAUDE_PROJECTS = join(root, 'claude');
    process.env.SKILLHUB_CODEX_SESSIONS = join(root, 'codex');
    const originalFetch = globalThis.fetch;
    try {
        await mkdir(join(root, 'claude'), { recursive: true });
        await mkdir(join(root, 'codex'), { recursive: true });
        const event = sampleEvent('codex', 'offline-session');
        const job = await enqueueSession({
            clientName: 'codex',
            sessionId: 'offline-session',
            snapshotEvents: [event],
            ended: true
        });
        globalThis.fetch = (async () => {
            throw new Error('network down');
        });
        const result = await drainQueue({ serviceUrl: 'http://127.0.0.1:9' });
        assert.match(result, /failed=/);
        const leftover = await readSpoolJob(job.safeId);
        assert.ok(leftover, 'spool must remain after network failure');
        assert.ok(leftover?.lastError);
        assert.ok(Date.parse(leftover?.nextAttemptAt || '') > Date.now());
        assert.equal((leftover?.attempts || 0) >= 1, true);
    }
    finally {
        globalThis.fetch = originalFetch;
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous.observability);
        restoreEnv('SKILLHUB_CLAUDE_PROJECTS', previous.claude);
        restoreEnv('SKILLHUB_CODEX_SESSIONS', previous.codex);
        await rm(root, { recursive: true, force: true });
    }
});
test('missing source without snapshot is not acked and keeps pointer', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-missing-'));
    const previous = {
        observability: process.env.SKILLHUB_OBSERVABILITY_DIR,
        claude: process.env.SKILLHUB_CLAUDE_PROJECTS,
        codex: process.env.SKILLHUB_CODEX_SESSIONS
    };
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    process.env.SKILLHUB_CLAUDE_PROJECTS = join(root, 'claude');
    process.env.SKILLHUB_CODEX_SESSIONS = join(root, 'codex-sessions');
    const originalFetch = globalThis.fetch;
    try {
        await mkdir(join(root, 'claude'), { recursive: true });
        await mkdir(join(root, 'codex-sessions'), { recursive: true });
        const missingPath = join(root, 'gone.jsonl');
        const job = await enqueueSession({
            clientName: 'codex',
            sessionId: 'missing-source-session',
            sourcePath: missingPath,
            ended: true
        });
        globalThis.fetch = (async () => {
            throw new Error('HTTP should not run when no events');
        });
        await drainQueue({ serviceUrl: 'http://127.0.0.1:9' });
        const leftover = await readSpoolJob(job.safeId);
        assert.ok(leftover, 'pointer spool must remain when source is missing');
        assert.ok(leftover?.lastError);
        const state = await loadState();
        assert.equal(state.sessions['codex:missing-source-session'], undefined);
    }
    finally {
        globalThis.fetch = originalFetch;
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous.observability);
        restoreEnv('SKILLHUB_CLAUDE_PROJECTS', previous.claude);
        restoreEnv('SKILLHUB_CODEX_SESSIONS', previous.codex);
        await rm(root, { recursive: true, force: true });
    }
});
test('reconcile does not requeue unchanged acknowledged sources', async () => {
    const root = await mkdtemp(join(tmpdir(), 'skillhub-reconcile-'));
    const previous = {
        observability: process.env.SKILLHUB_OBSERVABILITY_DIR,
        claude: process.env.SKILLHUB_CLAUDE_PROJECTS,
        codex: process.env.SKILLHUB_CODEX_SESSIONS
    };
    process.env.SKILLHUB_OBSERVABILITY_DIR = root;
    process.env.SKILLHUB_CLAUDE_PROJECTS = join(root, 'claude');
    process.env.SKILLHUB_CODEX_SESSIONS = join(root, 'codex-sessions');
    const originalFetch = globalThis.fetch;
    try {
        await mkdir(join(root, 'claude'), { recursive: true });
        await mkdir(join(root, 'codex-sessions'), { recursive: true });
        const sourcePath = join(root, 'codex-sessions', 'stable-session.jsonl');
        await writeFile(sourcePath, `${JSON.stringify({
            type: 'session_meta',
            timestamp: '2026-09-16T00:00:00.000Z',
            payload: { id: 'stable-session' }
        })}\n${JSON.stringify({
            type: 'response_item',
            timestamp: '2026-09-16T00:00:01.000Z',
            payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'hello' }] }
        })}\n`, 'utf8');
        const sourceHash = await hashFile(sourcePath);
        const event = sampleEvent('codex', 'stable-session');
        const job = await enqueueSession({
            clientName: 'codex',
            sessionId: 'stable-session',
            sourcePath,
            snapshotEvents: [event],
            ended: true,
            sourceMtimeMs: (await stat(sourcePath)).mtimeMs,
            sourceHash,
            contentHash: hashEvents([event])
        });
        await ackSession(job);
        globalThis.fetch = (async () => {
            throw new Error('HTTP should not run for unchanged reconcile');
        });
        const result = await drainQueue({ serviceUrl: 'http://127.0.0.1:9', reconcile: true });
        assert.match(result, /no pending sessions|spool is empty|uploaded=0/);
        assert.equal((await listSpoolJobs()).length, 0);
    }
    finally {
        globalThis.fetch = originalFetch;
        restoreEnv('SKILLHUB_OBSERVABILITY_DIR', previous.observability);
        restoreEnv('SKILLHUB_CLAUDE_PROJECTS', previous.claude);
        restoreEnv('SKILLHUB_CODEX_SESSIONS', previous.codex);
        await rm(root, { recursive: true, force: true });
    }
});
function sampleEvent(clientName, sessionId) {
    return {
        v: 1,
        event_id: `${sessionId}-event`,
        client_id: 'client',
        client_name: clientName,
        session_id: sessionId,
        turn_index: 0,
        step_id: `${sessionId}-step`,
        seq: 1,
        type: 'user',
        ts: '2026-09-16T00:00:00.000Z',
        source: 'scan',
        payload: { text: sessionId }
    };
}
function restoreEnv(name, value) {
    if (value === undefined)
        delete process.env[name];
    else
        process.env[name] = value;
}
