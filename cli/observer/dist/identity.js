import { createHash } from 'node:crypto';
export function sessionSafeId(clientName, sessionId) {
    return createHash('sha256').update(`${clientName}\0${sessionId}`).digest('hex');
}
export function ingestSessionKey(clientName, sessionId) {
    const raw = `${clientName}:${sessionId}`;
    if (Buffer.byteLength(raw) <= 256)
        return raw;
    return `${clientName}:sha256:${createHash('sha256').update(sessionId).digest('hex')}`;
}
export function sessionStateKey(clientName, sessionId) {
    return `${clientName}:${sessionId}`;
}
