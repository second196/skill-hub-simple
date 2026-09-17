import { closeSync, constants, existsSync, fsyncSync, ftruncateSync, openSync, readFileSync, unlinkSync, writeSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { hostname } from 'node:os';
import { lockDir, lockFilePath } from './paths.js';
const STALE_MS = 10 * 60 * 1000;
export async function tryAcquireLock(name) {
    await mkdir(lockDir(), { recursive: true });
    const path = lockFilePath(name);
    for (let attempt = 0; attempt < 2; attempt += 1) {
        try {
            const fd = openSync(path, constants.O_CREAT | constants.O_EXCL | constants.O_RDWR);
            writeLock(fd, nowRecord());
            return { name, path, fd };
        }
        catch (error) {
            const code = error.code;
            if (code !== 'EEXIST')
                throw error;
            if (attempt === 0 && stealIfStale(path))
                continue;
            return undefined;
        }
    }
    return undefined;
}
export async function acquireLock(name, timeoutMs = 2000) {
    const started = Date.now();
    while (Date.now() - started <= timeoutMs) {
        const lock = await tryAcquireLock(name);
        if (lock)
            return lock;
        await sleep(50);
    }
    throw new Error(`lock busy: ${name}`);
}
export function releaseLock(lock) {
    if (!lock)
        return;
    try {
        closeSync(lock.fd);
    }
    catch {
        // already closed
    }
    try {
        if (existsSync(lock.path))
            unlinkSync(lock.path);
    }
    catch {
        // another process may have stolen it
    }
}
export function heartbeatLock(lock) {
    try {
        writeLock(lock.fd, nowRecord());
    }
    catch {
        // best-effort heartbeat
    }
}
function stealIfStale(path) {
    let record;
    try {
        record = JSON.parse(readFileSync(path, 'utf8'));
    }
    catch {
        try {
            unlinkSync(path);
            return true;
        }
        catch {
            return false;
        }
    }
    const heartbeat = Date.parse(record.heartbeatAt || record.startedAt || '');
    const stale = !Number.isFinite(heartbeat) || Date.now() - heartbeat > STALE_MS;
    if (!stale && processAlive(record.pid))
        return false;
    try {
        unlinkSync(path);
        return true;
    }
    catch {
        return false;
    }
}
function processAlive(pid) {
    if (!Number.isInteger(pid) || pid <= 0)
        return false;
    try {
        process.kill(pid, 0);
        return true;
    }
    catch (error) {
        return error.code === 'EPERM';
    }
}
function nowRecord() {
    const now = new Date().toISOString();
    return {
        pid: process.pid,
        startedAt: now,
        hostname: hostname(),
        heartbeatAt: now
    };
}
function writeLock(fd, record) {
    const encoded = Buffer.from(`${JSON.stringify(record)}\n`);
    writeSync(fd, encoded, 0, encoded.length, 0);
    ftruncateSync(fd, encoded.length);
    try {
        fsyncSync(fd);
    }
    catch {
        // lock fsync is best-effort
    }
}
function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
