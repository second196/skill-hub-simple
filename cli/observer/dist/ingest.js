import { randomUUID } from 'node:crypto';
import { fetchPlatformSkills, platformIndex, resolvePlatformSlug } from './platform.js';
import { normalizeVersionLabel } from './catalog.js';
import { hostMeta, loadClientId } from './store.js';
import { buildTimeline } from './timeline.js';
import { sanitizePayload } from './payload.js';
import { ingestSessionKey } from './identity.js';
import { logObserver } from './log.js';
const MAX_BATCH_BYTES = 8 * 1024 * 1024;
/** SemVer label from step/payload, if any. Digest is intentionally ignored. */
export function skillStepVersionLabel(step) {
    const payload = (step.payload || {});
    const raw = step.skill_version_label
        || asText(payload.skill_version_label)
        || asText(payload.skillVersionLabel)
        || asText(payload.versionLabel);
    return normalizeVersionLabel(raw);
}
/** True when any skill step in the session lacks a SemVer version label. */
export function sessionHasUnversionedSkill(session) {
    for (const turn of session.turns) {
        for (const step of turn.steps) {
            if (step.type !== 'skill')
                continue;
            if (!skillStepVersionLabel(step))
                return true;
        }
    }
    return false;
}
function asText(value) {
    return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}
export async function ingestEvents(serviceUrl, events) {
    const sessions = buildTimeline(events);
    if (!sessions.length)
        return '没有可上传的会话观测数据';
    let platform = [];
    try {
        platform = await fetchPlatformSkills(serviceUrl);
    }
    catch (error) {
        await logObserver(`platform catalog unavailable: ${error instanceof Error ? error.message : String(error)}`);
    }
    return ingestSessions(serviceUrl, annotateSessions(sessions, platform));
}
export async function ingestSessions(serviceUrl, sessions) {
    if (!sessions.length)
        return '没有可上传的会话观测数据';
    const clientId = await loadClientId();
    const meta = hostMeta();
    const base = serviceUrl.replace(/\/+$/, '');
    const summaries = [];
    let batch = [];
    let batchBytes = 0;
    let sessionCount = 0;
    let skippedSessions = 0;
    const flush = async () => {
        if (!batch.length)
            return;
        const body = {
            client: { clientId, hostname: meta.hostname, os: meta.os },
            batchId: randomUUID(),
            generatedAt: new Date().toISOString(),
            sessions: batch
        };
        const result = await postJson(`${base}/api/observations/ingest`, body);
        summaries.push(`batch ${result.batchId || body.batchId}: sessions=${result.sessionCount ?? batch.length} turns=${result.turnCount ?? '-'} steps=${result.stepCount ?? '-'} skipped=${result.skippedStepCount ?? 0}`);
        batch = [];
        batchBytes = 0;
    };
    for (const session of sessions) {
        // Contract: sessions containing any skill without SemVer version are not uploaded.
        if (sessionHasUnversionedSkill(session)) {
            skippedSessions += 1;
            await logObserver(`session skipped (unversioned skill): ${session.clientName}/${session.sessionId}`);
            continue;
        }
        const payload = toIngestSession(session);
        const encoded = Buffer.byteLength(JSON.stringify(payload));
        if (batch.length && batchBytes + encoded > MAX_BATCH_BYTES) {
            await flush();
        }
        batch.push(payload);
        batchBytes += encoded;
        sessionCount += 1;
    }
    await flush();
    if (!sessionCount) {
        return skippedSessions > 0
            ? `没有可上传的会话：${skippedSessions} 个会话因 skill 缺少版本号被丢弃（观测仅上传带 SemVer 版本的 skill 数据）`
            : '没有可上传的会话观测数据';
    }
    return `已上传 ${sessionCount} 个会话（完整原文，不含摘要）${skippedSessions ? `，跳过 ${skippedSessions} 个无版本 skill 会话` : ''}\n${summaries.join('\n')}`;
}
export function annotateSessions(sessions, platform) {
    const index = platformIndex(platform);
    return sessions.map((session) => ({
        ...session,
        turns: session.turns.map((turn) => ({
            ...turn,
            steps: annotateSteps(turn.steps, index)
        }))
    }));
}
function annotateSteps(steps, index) {
    let currentSlug;
    const result = [];
    for (const step of steps) {
        const stepName = step.skill_name || payloadName(step);
        const resolved = resolvePlatformSlug(index, step.skill_slug, stepName);
        let slug;
        if (step.type === 'user' || step.type === 'assistant') {
            slug = undefined;
        }
        else {
            slug = resolved;
            if (step.type === 'skill') {
                if (resolved)
                    currentSlug = resolved;
            }
            else if (!slug) {
                slug = currentSlug;
            }
            if (resolved)
                currentSlug = resolved;
        }
        const skillName = slug && index.slugs.has(slug)
            ? (slug === step.skill_slug ? step.skill_name || slug : slug)
            : undefined;
        result.push({
            ...step,
            skill_slug: slug,
            skill_name: skillName,
            payload: sanitizePayload(step.payload)
        });
    }
    return result;
}
function toIngestSession(session) {
    return {
        sessionId: ingestSessionKey(session.clientName, session.sessionId),
        clientName: session.clientName,
        title: session.title || undefined,
        sessionTitle: session.title || undefined,
        startedAt: session.startedAt || undefined,
        endedAt: session.endedAt || undefined,
        turns: session.turns.map((turn) => ({
            turnIndex: turn.turnIndex,
            startedAt: turn.startedAt || undefined,
            userText: turn.userText,
            steps: turn.steps.map((step) => toIngestStep(step))
        }))
    };
}
export function toIngestStep(step) {
    const payload = sanitizePayload(step.payload || {});
    delete payload.skill_version_digest;
    delete payload.skillVersionDigest;
    delete payload.versionDigest;
    delete payload.version_digest;
    const versionFields = {};
    if (step.type === 'skill') {
        const versionLabel = skillStepVersionLabel(step);
        if (versionLabel) {
            versionFields.skillVersionLabel = versionLabel;
            versionFields.skillVersionSource = 'observed';
            payload.skillVersionLabel = versionLabel;
            payload.skillVersionSource = 'observed';
        }
    }
    delete payload.skill_version_label;
    delete payload.skillVersionLabel;
    if (typeof versionFields.skillVersionLabel === 'string') {
        payload.skillVersionLabel = versionFields.skillVersionLabel;
    }
    return {
        stepId: step.step_id,
        seq: step.seq,
        type: step.type,
        ts: step.ts,
        skillSlug: step.skill_slug,
        skillName: step.skill_name,
        ...versionFields,
        payload
    };
}
export async function postJson(url, body) {
    const response = await fetch(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(60_000)
    });
    const text = await response.text();
    let parsed = {};
    try {
        parsed = text ? JSON.parse(text) : {};
    }
    catch {
        parsed = { message: text };
    }
    if (!response.ok) {
        throw new Error(String(parsed.message || `上传失败：${response.status}`));
    }
    return parsed;
}
function payloadName(step) {
    const name = step.payload.name;
    return typeof name === 'string' ? name : undefined;
}
