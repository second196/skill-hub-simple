import { createReadStream } from 'node:fs';
import { access, mkdir, readdir, stat } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline';
import { createHash, randomUUID } from 'node:crypto';
import { hostname, platform } from 'node:os';
import { atomicWriteFile } from './atomic.js';
import { sessionSafeId, sessionStateKey } from './identity.js';
import { acquireLock, releaseLock } from './lock.js';
import { sanitizePayload } from './payload.js';
import { truncateText } from './documents.js';
import { binDir, clientPath, eventsPath, lockDir, logsDir, observabilityDir, sessionFilePath, sessionsDir, spoolDir, spoolJobPath, spoolTmpDir, statePath } from './paths.js';
const MAX_FIELD_CHARS = 256 * 1024;
export async function ensureStore() {
    await mkdir(observabilityDir(), { recursive: true });
    await mkdir(sessionsDir(), { recursive: true });
    await mkdir(spoolDir(), { recursive: true });
    await mkdir(spoolTmpDir(), { recursive: true });
    await mkdir(lockDir(), { recursive: true });
    await mkdir(logsDir(), { recursive: true });
    await mkdir(binDir(), { recursive: true });
}
export async function loadClientId() {
    await ensureStore();
    try {
        const parsed = await readJson(clientPath());
        if (parsed?.clientId)
            return parsed.clientId;
    }
    catch {
        // create below
    }
    const record = { clientId: randomUUID(), createdAt: new Date().toISOString() };
    await writeJson(clientPath(), record);
    return record.clientId;
}
export function hostMeta() {
    return { hostname: hostname(), os: platform() };
}
export function fingerprint(event) {
    return event.step_id;
}
export async function appendEvents(events) {
    if (!events.length)
        return 0;
    const grouped = groupEvents(events);
    for (const group of grouped.values()) {
        await withSessionLock(group.clientName, group.sessionId, async () => {
            const existing = await readSessionEvents(group.clientName, group.sessionId);
            await writeSessionEvents(group.clientName, group.sessionId, [...existing, ...group.events]);
        });
    }
    return events.length;
}
export async function mergeEvents(events) {
    if (!events.length)
        return 0;
    await snapshotEvents(events);
    return events.length;
}
export async function snapshotEvents(events) {
    const grouped = groupEvents(events);
    for (const group of grouped.values()) {
        await withSessionLock(group.clientName, group.sessionId, async () => {
            const existing = await readSessionEvents(group.clientName, group.sessionId);
            await writeSessionEvents(group.clientName, group.sessionId, [...existing, ...group.events]);
        });
    }
}
export async function readEvents() {
    const latest = new Map();
    for await (const event of streamLegacyEvents())
        putEvent(latest, event);
    for (const snapshot of await listSessionSnapshots()) {
        for (const event of await readSessionFile(snapshot))
            putEvent(latest, event);
    }
    return [...latest.values()].sort(compareEvents);
}
export async function readSessionEvents(clientName, sessionId) {
    return readSessionFile(sessionFilePath(String(clientName), sessionSafeId(clientName, sessionId)));
}
export async function enqueueSession(input) {
    if (!input.sessionId || input.sessionId === 'unknown') {
        throw new Error('sessionId is required to enqueue');
    }
    await ensureStore();
    return withSessionLock(input.clientName, input.sessionId, async () => {
        const safeId = sessionSafeId(input.clientName, input.sessionId);
        let eventCount = 0;
        let contentHash = input.contentHash;
        if (input.snapshotEvents?.length) {
            const existing = await readSessionEvents(input.clientName, input.sessionId);
            const merged = mergeEventList([...existing, ...input.snapshotEvents]);
            await writeSessionEvents(input.clientName, input.sessionId, merged);
            eventCount = merged.length;
            contentHash = contentHash || hashEvents(merged);
        }
        else {
            const existing = await readSessionEvents(input.clientName, input.sessionId);
            eventCount = existing.length;
            contentHash = contentHash || (existing.length ? hashEvents(existing) : undefined);
        }
        const now = new Date().toISOString();
        const previous = await readSpoolJob(safeId);
        const job = {
            safeId,
            clientName: input.clientName,
            sessionId: input.sessionId,
            sourcePath: input.sourcePath || previous?.sourcePath,
            snapshotPath: sessionFilePath(input.clientName, safeId),
            contentHash,
            sourceHash: input.sourceHash || previous?.sourceHash,
            eventCount,
            sourceMtimeMs: input.sourceMtimeMs ?? previous?.sourceMtimeMs,
            ended: Boolean(input.ended || previous?.ended),
            version: (previous?.version || 0) + 1,
            attempts: previous?.attempts || 0,
            nextAttemptAt: now,
            lastError: undefined,
            createdAt: previous?.createdAt || now,
            updatedAt: now
        };
        await writeSpoolJob(job);
        return job;
    });
}
export async function listSpoolJobs() {
    await ensureStore();
    let entries = [];
    try {
        entries = await readdir(spoolDir());
    }
    catch {
        return [];
    }
    const jobs = [];
    for (const entry of entries) {
        if (!entry.endsWith('.json') || entry.startsWith('.'))
            continue;
        const job = await readSpoolJob(entry.replace(/\.json$/, ''));
        if (job)
            jobs.push(job);
    }
    return jobs.sort((a, b) => a.updatedAt.localeCompare(b.updatedAt));
}
export async function readSpoolJob(safeId) {
    return readJson(spoolJobPath(safeId));
}
export async function writeSpoolJob(job) {
    await writeJson(spoolJobPath(job.safeId), job);
}
export async function deleteSpoolJob(safeId) {
    const { rm } = await import('node:fs/promises');
    await rm(spoolJobPath(safeId), { force: true });
}
export async function loadState() {
    const parsed = await readJson(statePath());
    if (parsed && parsed.sessions && typeof parsed.sessions === 'object')
        return parsed;
    return { sessions: {} };
}
export async function saveState(state) {
    await writeJson(statePath(), state);
}
export async function ackSession(job, uploadedAt = new Date().toISOString()) {
    return withSessionLock(job.clientName, job.sessionId, async () => {
        const current = await readSpoolJob(job.safeId);
        if (current && current.version !== job.version) {
            return { deleted: false, reason: `spool version changed: ${job.version} -> ${current.version}` };
        }
        const stateLock = await acquireLock('state');
        try {
            const state = await loadState();
            state.sessions[sessionStateKey(job.clientName, job.sessionId)] = {
                safeId: job.safeId,
                sourcePath: job.sourcePath,
                contentHash: job.contentHash || '',
                sourceHash: job.sourceHash,
                eventCount: job.eventCount || 0,
                sourceMtimeMs: job.sourceMtimeMs || 0,
                ended: job.ended,
                uploadedAt
            };
            await saveState(state);
        }
        finally {
            releaseLock(stateLock);
        }
        if (current)
            await deleteSpoolJob(job.safeId);
        return { deleted: true };
    });
}
export function hashEvents(events) {
    const hash = createHash('sha256');
    for (const event of [...events].sort(compareEvents)) {
        hash.update(event.step_id);
        hash.update('\0');
        hash.update(String(payloadSize(event)));
        hash.update('\n');
    }
    return hash.digest('hex');
}
export async function writeJson(path, value) {
    await mkdir(dirname(path), { recursive: true });
    await atomicWriteFile(path, `${JSON.stringify(value, null, 2)}\n`);
}
export async function readJson(path) {
    try {
        const { readFile } = await import('node:fs/promises');
        return JSON.parse(await readFile(path, 'utf8'));
    }
    catch {
        return undefined;
    }
}
async function writeSessionEvents(clientName, sessionId, events) {
    const merged = mergeEventList(events);
    const target = sessionFilePath(String(clientName), sessionSafeId(clientName, sessionId));
    await mkdir(dirname(target), { recursive: true });
    const body = merged.map((event) => JSON.stringify({ ...event, payload: sanitizePayload(event.payload) })).join('\n');
    await atomicWriteFile(target, body ? `${body}\n` : '');
}
async function readSessionFile(path) {
    const latest = new Map();
    for await (const event of streamEventFile(path))
        putEvent(latest, event);
    return [...latest.values()].sort(compareEvents);
}
async function listSessionSnapshots() {
    const files = [];
    async function walk(dir, remaining) {
        if (remaining < 0)
            return;
        let entries;
        try {
            entries = await readdir(dir);
        }
        catch {
            return;
        }
        for (const entry of entries) {
            const full = join(dir, entry);
            try {
                const info = await stat(full);
                if (info.isDirectory()) {
                    await walk(full, remaining - 1);
                    continue;
                }
                if (entry.endsWith('.jsonl'))
                    files.push(full);
            }
            catch {
                // skip
            }
        }
    }
    await walk(sessionsDir(), 3);
    return files;
}
async function* streamLegacyEvents() {
    yield* streamEventFile(eventsPath());
}
async function* streamEventFile(path) {
    try {
        await access(path);
    }
    catch {
        return;
    }
    const handle = createReadStream(path, { encoding: 'utf8' });
    const rl = createInterface({ input: handle, crlfDelay: Infinity });
    try {
        for await (const line of rl) {
            const trimmed = line.trim();
            if (!trimmed)
                continue;
            try {
                const parsed = JSON.parse(trimmed);
                if (!parsed?.step_id)
                    continue;
                yield truncateEvent(parsed);
            }
            catch {
                // skip malformed
            }
        }
    }
    catch (error) {
        const code = error?.code;
        if (code !== 'ENOENT')
            throw error;
    }
    finally {
        rl.close();
        handle.destroy();
    }
}
function groupEvents(events) {
    const grouped = new Map();
    for (const event of events) {
        const clientName = event.client_name;
        const sessionId = event.session_id;
        const key = sessionStateKey(clientName, sessionId);
        const current = grouped.get(key) || { clientName, sessionId, events: [] };
        current.events.push(event);
        grouped.set(key, current);
    }
    return grouped;
}
function mergeEventList(events) {
    const latest = new Map();
    for (const event of events)
        putEvent(latest, event);
    return [...latest.values()].sort(compareEvents);
}
function putEvent(byId, event) {
    const next = truncateEvent(event);
    const previous = byId.get(next.step_id);
    if (!previous || payloadSize(next) >= payloadSize(previous)) {
        byId.set(next.step_id, next);
    }
}
function payloadSize(event) {
    try {
        return JSON.stringify(event.payload).length;
    }
    catch {
        return 0;
    }
}
function truncateEvent(event) {
    return {
        ...event,
        payload: truncatePayload(event.payload)
    };
}
function truncatePayload(value) {
    if (typeof value === 'string')
        return truncateText(value, MAX_FIELD_CHARS);
    if (Array.isArray(value))
        return value.map((item) => truncatePayload(item));
    if (value && typeof value === 'object') {
        const copy = {};
        for (const [key, item] of Object.entries(value)) {
            copy[key] = truncatePayload(item);
        }
        return copy;
    }
    return value;
}
function compareEvents(a, b) {
    const time = a.ts.localeCompare(b.ts);
    if (time)
        return time;
    if (a.session_id !== b.session_id)
        return a.session_id.localeCompare(b.session_id);
    if (a.turn_index !== b.turn_index)
        return a.turn_index - b.turn_index;
    return a.seq - b.seq;
}
async function withSessionLock(clientName, sessionId, fn) {
    const lock = await acquireLock(`session-${sessionSafeId(clientName, sessionId)}`);
    try {
        return await fn();
    }
    finally {
        releaseLock(lock);
    }
}
