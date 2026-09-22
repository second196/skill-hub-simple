import { randomUUID } from 'node:crypto';
import { fetchPlatformSkills, platformIndex, resolvePlatformSlug } from './platform.js';
import { discoverProjectSkillRoots, listInstalledSkills, normalizeVersionLabel, resolveSkillVersion } from './catalog.js';
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
function asText(value) {
    return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}
export async function ingestEvents(serviceUrl, events) {
    const sessions = buildTimeline(events);
    if (!sessions.length) {
        return { message: '没有可上传的会话观测数据', uploadedSessions: 0, skippedSessions: 0, skippedByPlatform: 0 };
    }
    let platform = [];
    let platformAvailable = false;
    try {
        platform = await fetchPlatformSkills(serviceUrl);
        platformAvailable = true;
    }
    catch (error) {
        await logObserver(`platform catalog unavailable: ${error instanceof Error ? error.message : String(error)}`);
    }
    const installed = await loadInstalledSkillsForEvents(events);
    const annotated = annotateSessions(sessions, platform, installed);
    // A session qualifies through at least one platform skill, but then uploads in full.
    const platformCatalog = platformIndex(platform);
    const uploadable = platformAvailable
        ? annotated.filter((session) => sessionInvokesPlatformSkill(session, platform, platformCatalog))
        : annotated;
    const skippedByPlatform = annotated.length - uploadable.length;
    if (!uploadable.length && skippedByPlatform > 0) {
        const message = `没有可上传的会话：${skippedByPlatform} 个会话未调用平台已知 Skill`;
        await logObserver(message);
        return { message, uploadedSessions: 0, skippedSessions: skippedByPlatform, skippedByPlatform };
    }
    const result = await ingestSessions(serviceUrl, uploadable);
    return {
        ...result,
        skippedSessions: result.skippedSessions + skippedByPlatform,
        skippedByPlatform
    };
}
export function sessionInvokesPlatformSkill(session, platform, index = platformIndex(platform)) {
    return session.turns.some((turn) => turn.steps.some((step) => step.type === 'skill' &&
        Boolean(step.skill_slug) &&
        index.slugs.has(step.skill_slug) &&
        Boolean(skillStepVersionLabel(step) &&
            index.versionsBySlug.get(step.skill_slug)?.has(skillStepVersionLabel(step)))));
}
export async function ingestSessions(serviceUrl, sessions) {
    if (!sessions.length) {
        return { message: '没有可上传的会话观测数据', uploadedSessions: 0, skippedSessions: 0, skippedByPlatform: 0 };
    }
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
        const stepCount = session.turns.reduce((sum, turn) => sum + turn.steps.length, 0);
        if (!stepCount) {
            skippedSessions += 1;
            await logObserver(`session skipped (no steps): ${session.clientName}/${session.sessionId}`);
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
        return {
            message: skippedSessions > 0
                ? `没有可上传的会话：${skippedSessions} 个会话没有任何步骤`
                : '没有可上传的会话观测数据',
            uploadedSessions: 0,
            skippedSessions,
            skippedByPlatform: 0
        };
    }
    return {
        message: `已上传 ${sessionCount} 个会话（完整原文，含全部 skill 步，不含摘要）${skippedSessions ? `，跳过 ${skippedSessions} 个无步骤会话` : ''}\n${summaries.join('\n')}`,
        uploadedSessions: sessionCount,
        skippedSessions,
        skippedByPlatform: 0
    };
}
export function annotateSessions(sessions, platform, installedSkills = []) {
    const index = platformIndex(platform);
    return sessions.map((session) => ({
        ...session,
        turns: session.turns.map((turn) => ({
            ...turn,
            steps: annotateSteps(turn.steps, index, installedSkills)
        }))
    }));
}
/** Backfill skill_version_label from installed catalog (SKILL.md or package.json). */
export function backfillSkillVersionLabel(step, skills) {
    if (step.type !== 'skill' || skillStepVersionLabel(step))
        return step;
    const resolved = resolveSkillVersion(skills, {
        slug: step.skill_slug,
        path: payloadPath(step)
    });
    if (!resolved.versionLabel)
        return step;
    return {
        ...step,
        skill_version_label: resolved.versionLabel,
        payload: {
            ...step.payload,
            skill_version_label: resolved.versionLabel
        }
    };
}
function annotateSteps(steps, index, installedSkills) {
    let currentSlug;
    let currentName;
    const result = [];
    for (const step of steps) {
        const stepName = step.skill_name || payloadName(step);
        const resolved = resolvePlatformSlug(index, step.skill_slug, stepName, undefined, payloadPath(step));
        let slug;
        let skillName;
        if (step.type === 'user' || step.type === 'assistant') {
            slug = undefined;
            skillName = undefined;
        }
        else if (resolved) {
            // Platform mapping (exact or composite parent rollup).
            slug = resolved;
            skillName = resolved === step.skill_slug
                ? (step.skill_name || resolved)
                : resolved;
            if (step.type === 'skill' || step.skill_slug || step.skill_name) {
                currentSlug = slug;
                currentName = skillName;
            }
        }
        else if (step.type === 'skill' || step.skill_slug || step.skill_name) {
            // Local-only skill: keep observed identity for full-session upload.
            slug = step.skill_slug;
            skillName = step.skill_name || step.skill_slug;
            if (step.type === 'skill') {
                currentSlug = slug;
                currentName = skillName;
            }
        }
        else {
            // tool / document without its own skill identity: inherit the active skill.
            slug = currentSlug;
            skillName = currentName;
        }
        const versioned = backfillSkillVersionLabel({
            ...step,
            skill_slug: slug,
            skill_name: skillName
        }, installedSkills);
        result.push({
            ...versioned,
            payload: sanitizePayload(versioned.payload)
        });
    }
    return result;
}
async function loadInstalledSkillsForEvents(events) {
    try {
        const seeds = eventSeedPaths(events);
        return await listInstalledSkills(await discoverProjectSkillRoots(seeds));
    }
    catch (error) {
        await logObserver(`installed skill catalog unavailable: ${error instanceof Error ? error.message : String(error)}`);
        return [];
    }
}
function eventSeedPaths(events) {
    const seeds = new Set([process.cwd()]);
    for (const event of events) {
        if (event.cwd)
            seeds.add(event.cwd);
        const path = payloadPath(event);
        if (path)
            seeds.add(path);
    }
    return [...seeds];
}
function payloadPath(step) {
    const direct = step.payload.path ?? step.payload.sourcePath ?? step.payload.file ?? step.payload.file_path;
    if (typeof direct === 'string' && direct.trim())
        return direct.trim();
    const nested = step.payload.args;
    if (nested && typeof nested === 'object') {
        const record = nested;
        const nestedPath = record.path ?? record.file ?? record.sourcePath ?? record.file_path ?? record.filePath;
        if (typeof nestedPath === 'string' && nestedPath.trim())
            return nestedPath.trim();
    }
    return undefined;
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
