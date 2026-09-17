import { createReadStream } from 'node:fs';
import { readdir, stat } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { homedir } from 'node:os';
import { createInterface } from 'node:readline';
import { createHash, randomUUID } from 'node:crypto';
import { discoverProjectSkillRoots, listInstalledSkills, matchSlashSkillCommands, matchSkillUsage, parentSlugsFor, slugify } from './catalog.js';
import { extractPaths, isDocumentPath, readDocument } from './documents.js';
import { sanitizePayload, sanitizeText } from './payload.js';
import { loadClientId } from './store.js';
export async function scanAll(options = {}) {
    const clientId = await loadClientId();
    const sources = await listSessionSources(options);
    return scanSources(clientId, sources, options);
}
export async function scanSessionFile(clientName, filePath) {
    const clientId = await loadClientId();
    const info = await safeStat(filePath);
    if (!info)
        return [];
    const source = {
        clientName,
        sessionId: clientName === 'codex' ? await peekCodexSessionId(filePath) || basename(filePath, '.jsonl') : await claudeSessionIdFromFile(filePath),
        path: filePath,
        mtimeMs: info.mtimeMs
    };
    return scanSources(clientId, [source], {});
}
async function scanSources(clientId, sources, options) {
    const claudeFiles = sources.filter((item) => item.clientName === 'claude-code').map((item) => item.path);
    const codexFiles = sources.filter((item) => item.clientName === 'codex').map((item) => item.path);
    const skills = await listInstalledSkills(await discoverProjectSkillRoots([...claudeFiles, ...codexFiles]));
    const claude = await scanClaude(clientId, skills, claudeFiles, options);
    const codex = await scanCodex(clientId, skills, codexFiles, options);
    return [...claude, ...codex];
}
export async function listSessionSources(options = {}) {
    const claudeFiles = await listFiles(claudeProjectsRoot(), 6, options);
    const codexFiles = await listFiles(codexSessionsRoot(), 6, options);
    const sources = [];
    for (const file of claudeFiles) {
        const info = await safeStat(file);
        if (!info)
            continue;
        if (options.sessionId && !pathLikelyMatches(options.sessionId, file, 'claude-code'))
            continue;
        const sessionId = await claudeSessionIdFromFile(file);
        if (options.sessionId && !sessionMatches(options.sessionId, sessionId, file, 'claude-code'))
            continue;
        sources.push({ clientName: 'claude-code', sessionId, path: file, mtimeMs: info.mtimeMs });
    }
    for (const file of codexFiles) {
        const info = await safeStat(file);
        if (!info)
            continue;
        if (options.sessionId && !pathLikelyMatches(options.sessionId, file, 'codex')) {
            // Codex id often only exists inside session_meta; still need a cheap peek.
            const peeked = await peekCodexSessionId(file);
            if (!sessionMatches(options.sessionId, peeked, file, 'codex'))
                continue;
            sources.push({ clientName: 'codex', sessionId: peeked, path: file, mtimeMs: info.mtimeMs });
            continue;
        }
        const sessionId = await codexSessionIdFromFile(file);
        if (options.sessionId && !sessionMatches(options.sessionId, sessionId, file, 'codex'))
            continue;
        sources.push({ clientName: 'codex', sessionId, path: file, mtimeMs: info.mtimeMs });
    }
    return sources;
}
export async function findSessionSource(clientName, sessionId, hintPath, knownSources) {
    if (hintPath) {
        const info = await safeStat(hintPath);
        if (info) {
            const foundId = clientName === 'codex' ? await peekCodexSessionId(hintPath) : await claudeSessionIdFromFile(hintPath);
            if (sessionMatches(sessionId, foundId, hintPath, clientName)) {
                return { clientName, sessionId: foundId || sessionId, path: hintPath, mtimeMs: info.mtimeMs };
            }
        }
    }
    if (knownSources) {
        return knownSources.find((item) => item.clientName === clientName && sessionMatches(sessionId, item.sessionId, item.path, clientName));
    }
    const sources = await listSessionSources({ sessionId });
    return sources.find((item) => item.clientName === clientName && sessionMatches(sessionId, item.sessionId, item.path, clientName));
}
function pathLikelyMatches(wanted, file, clientName) {
    if (!wanted)
        return true;
    if (sessionMatches(wanted, '', file, clientName))
        return true;
    if (sessionMatches(wanted, basename(file, '.jsonl'), file, clientName))
        return true;
    return false;
}
async function scanClaude(clientId, skills, files, _options) {
    const events = [];
    for (const file of files) {
        const entries = await readJsonl(file);
        const sessionId = claudeSessionId(file, entries);
        let turnIndex = 0;
        let seq = 0;
        let currentSlug;
        let currentName;
        const pending = new Map();
        for (const entry of entries) {
            const ts = String(entry.timestamp || entry.ts || new Date().toISOString());
            const message = asRecord(entry.message);
            const role = String(message.role || entry.type || '');
            const content = message.content ?? entry.content;
            if (isUserTurn(role, content)) {
                turnIndex += 1;
                seq = 0;
                currentSlug = undefined;
                currentName = undefined;
                seq += 1;
                const userText = extractText(content);
                events.push(makeEvent({
                    clientId,
                    clientName: 'claude-code',
                    sessionId,
                    turnIndex,
                    seq,
                    type: 'user',
                    ts,
                    payload: { text: userText }
                }));
                const textEvents = skillEventsFromUserText({
                    clientId,
                    clientName: 'claude-code',
                    sessionId,
                    turnIndex,
                    startSeq: seq,
                    ts,
                    text: userText
                });
                seq += textEvents.length;
                for (const event of textEvents) {
                    if (event.type === 'skill') {
                        currentSlug = event.skill_slug;
                        currentName = event.skill_name;
                    }
                    events.push(event);
                }
                continue;
            }
            if (role === 'assistant') {
                const text = assistantText(content);
                if (text) {
                    seq += 1;
                    events.push(makeEvent({
                        clientId,
                        clientName: 'claude-code',
                        sessionId,
                        turnIndex: Math.max(turnIndex, 1),
                        seq,
                        type: 'assistant',
                        ts,
                        skillSlug: currentSlug,
                        skillName: currentName,
                        payload: { text }
                    }));
                }
            }
            const blocks = Array.isArray(content) ? content : [];
            for (const block of blocks) {
                const item = asRecord(block);
                if (item.type !== 'tool_use')
                    continue;
                seq += 1;
                const toolName = String(item.name || '');
                const input = item.input ?? {};
                const callId = String(item.id || seq);
                const explicitSkill = toolName === 'Skill'
                    ? skillUsageFromExplicit(String(asRecord(input).skill || asRecord(input).name || ''))
                    : undefined;
                const usage = explicitSkill || matchSkillUsage(skills, input);
                if (usage) {
                    currentName = usage.name;
                    currentSlug = usage.slug;
                    const matchedEvents = skillUsageEvents({
                        clientId,
                        clientName: 'claude-code',
                        sessionId,
                        turnIndex: Math.max(turnIndex, 1),
                        startSeq: seq,
                        ts,
                        usage,
                        args: input,
                        outcome: 'ok'
                    });
                    seq += matchedEvents.length - 1;
                    for (const event of matchedEvents) {
                        pending.set(callId, event);
                        events.push(event);
                    }
                    continue;
                }
                const paths = extractPaths(input);
                const documentPath = paths.find(isDocumentPath);
                if (documentPath) {
                    const contentText = await readDocument(documentPath);
                    const event = makeEvent({
                        clientId,
                        clientName: 'claude-code',
                        sessionId,
                        turnIndex: Math.max(turnIndex, 1),
                        seq,
                        type: 'document',
                        ts,
                        skillSlug: currentSlug,
                        skillName: currentName,
                        payload: { path: documentPath, content: contentText || '' }
                    });
                    pending.set(callId, event);
                    events.push(event);
                    continue;
                }
                const event = makeEvent({
                    clientId,
                    clientName: 'claude-code',
                    sessionId,
                    turnIndex: Math.max(turnIndex, 1),
                    seq,
                    type: 'tool',
                    ts,
                    skillSlug: currentSlug,
                    skillName: currentName,
                    payload: { name: toolName, args: input, result: '' }
                });
                pending.set(callId, event);
                events.push(event);
            }
            if (role === 'user' && Array.isArray(content)) {
                for (const block of content) {
                    const item = asRecord(block);
                    if (item.type !== 'tool_result')
                        continue;
                    const event = pending.get(String(item.tool_use_id || ''));
                    if (!event)
                        continue;
                    const result = extractText(item.content ?? item);
                    if (event.type === 'tool')
                        event.payload.result = result;
                    if (event.type === 'document' && (!event.payload.content || String(event.payload.content).length < result.length)) {
                        event.payload.content = result;
                    }
                    if (event.type === 'skill') {
                        event.payload.outcome = item.is_error ? 'error' : 'ok';
                        event.payload.result = result;
                    }
                }
            }
        }
    }
    return events;
}
async function scanCodex(clientId, skills, files, _options) {
    const events = [];
    for (const file of files) {
        const entries = await readJsonl(file);
        let sessionId = '';
        for (const entry of entries) {
            if (entry.type === 'session_meta') {
                sessionId = String(asRecord(entry.payload).id || '');
                break;
            }
        }
        if (!sessionId)
            sessionId = basename(file, '.jsonl');
        let turnIndex = 0;
        let seq = 0;
        let currentSlug;
        let currentName;
        const pending = new Map();
        for (const entry of entries) {
            const payload = asRecord(entry.payload);
            const ts = String(entry.timestamp || new Date().toISOString());
            if (entry.type === 'response_item' && payload.type === 'message' && payload.role === 'user') {
                const text = extractText(payload.content);
                if (!text.trim())
                    continue;
                turnIndex += 1;
                seq = 1;
                currentSlug = undefined;
                currentName = undefined;
                events.push(makeEvent({
                    clientId,
                    clientName: 'codex',
                    sessionId,
                    turnIndex,
                    seq,
                    type: 'user',
                    ts,
                    payload: { text }
                }));
                const textEvents = skillEventsFromUserText({
                    clientId,
                    clientName: 'codex',
                    sessionId,
                    turnIndex,
                    startSeq: seq,
                    ts,
                    text
                });
                seq += textEvents.length;
                for (const event of textEvents) {
                    if (event.type === 'skill') {
                        currentSlug = event.skill_slug;
                        currentName = event.skill_name;
                    }
                    events.push(event);
                }
                continue;
            }
            if (entry.type === 'response_item' && payload.type === 'message' && payload.role === 'assistant') {
                const text = assistantText(payload.content);
                if (text) {
                    seq += 1;
                    events.push(makeEvent({
                        clientId,
                        clientName: 'codex',
                        sessionId,
                        turnIndex: Math.max(turnIndex, 1),
                        seq,
                        type: 'assistant',
                        ts,
                        skillSlug: currentSlug,
                        skillName: currentName,
                        payload: { text }
                    }));
                }
                continue;
            }
            if (entry.type !== 'response_item')
                continue;
            if (payload.type === 'function_call' || payload.type === 'custom_tool_call') {
                seq += 1;
                const name = String(payload.name || '');
                const rawArgs = payload.type === 'function_call' ? payload.arguments : payload.input;
                const args = parseMaybeJson(rawArgs);
                const callId = String(payload.call_id || seq);
                const usage = matchSkillUsage(skills, args) || matchSkillUsage(skills, rawArgs);
                if (usage) {
                    currentSlug = usage.slug;
                    currentName = usage.name;
                    const matchedEvents = skillUsageEvents({
                        clientId,
                        clientName: 'codex',
                        sessionId,
                        turnIndex: Math.max(turnIndex, 1),
                        startSeq: seq,
                        ts,
                        usage,
                        args,
                        outcome: 'ok',
                        toolName: name
                    });
                    seq += matchedEvents.length - 1;
                    for (const event of matchedEvents) {
                        pending.set(callId, event);
                        events.push(event);
                    }
                    continue;
                }
                const paths = extractPaths(args);
                const documentPath = paths.find(isDocumentPath);
                if (documentPath) {
                    const contentText = await readDocument(documentPath);
                    const event = makeEvent({
                        clientId,
                        clientName: 'codex',
                        sessionId,
                        turnIndex: Math.max(turnIndex, 1),
                        seq,
                        type: 'document',
                        ts,
                        skillSlug: currentSlug,
                        skillName: currentName,
                        payload: { path: documentPath, content: contentText || '' }
                    });
                    pending.set(callId, event);
                    events.push(event);
                    continue;
                }
                const event = makeEvent({
                    clientId,
                    clientName: 'codex',
                    sessionId,
                    turnIndex: Math.max(turnIndex, 1),
                    seq,
                    type: 'tool',
                    ts,
                    skillSlug: currentSlug,
                    skillName: currentName,
                    payload: { name, args, result: '' }
                });
                pending.set(callId, event);
                events.push(event);
                continue;
            }
            if (payload.type === 'function_call_output' || payload.type === 'custom_tool_call_output') {
                const event = pending.get(String(payload.call_id || ''));
                if (!event)
                    continue;
                const result = typeof payload.output === 'string' ? sanitizeText(payload.output) : extractText(payload.output ?? payload);
                if (event.type === 'tool')
                    event.payload.result = result;
                if (event.type === 'document' && (!event.payload.content || String(event.payload.content).length < result.length)) {
                    event.payload.content = result;
                }
                if (event.type === 'skill') {
                    event.payload.outcome = /error/i.test(result.slice(0, 80)) ? 'error' : 'ok';
                    event.payload.result = result;
                }
            }
        }
    }
    return events;
}
function skillUsageFromExplicit(name) {
    if (!name)
        return undefined;
    const slug = slugify(name);
    if (!slug || slug === 'skill')
        return undefined;
    return { slug, name: name || slug, parents: parentsForExplicit(slug), match: 'call' };
}
function parentsForExplicit(slug) {
    return parentSlugsFor(slug);
}
function skillUsageEvents(input) {
    const events = [];
    let seq = input.startSeq;
    events.push(makeEvent({
        clientId: input.clientId,
        clientName: input.clientName,
        sessionId: input.sessionId,
        turnIndex: input.turnIndex,
        seq,
        type: 'skill',
        ts: input.ts,
        skillSlug: input.usage.slug,
        skillName: input.usage.name,
        payload: {
            name: input.usage.name,
            args: input.args,
            outcome: input.outcome,
            match: input.usage.match,
            tool: input.toolName,
            duration_ms: undefined
        }
    }));
    for (const parent of input.usage.parents) {
        seq += 1;
        events.push(makeEvent({
            clientId: input.clientId,
            clientName: input.clientName,
            sessionId: input.sessionId,
            turnIndex: input.turnIndex,
            seq,
            type: 'skill',
            ts: input.ts,
            skillSlug: parent,
            skillName: parent,
            payload: {
                name: parent,
                args: input.args,
                outcome: input.outcome,
                match: input.usage.match,
                rollup: true,
                child_slug: input.usage.slug,
                child_name: input.usage.name,
                duration_ms: undefined
            }
        }));
    }
    return events;
}
function skillEventsFromUserText(input) {
    const usages = matchSlashSkillCommands(input.text);
    if (!usages.length)
        return [];
    const events = [];
    let seq = input.startSeq;
    for (const usage of usages) {
        seq += 1;
        events.push(makeEvent({
            clientId: input.clientId,
            clientName: input.clientName,
            sessionId: input.sessionId,
            turnIndex: input.turnIndex,
            seq,
            type: 'skill',
            ts: input.ts,
            skillSlug: usage.slug,
            skillName: usage.name,
            payload: {
                name: usage.name,
                args: { source: 'user_text', text: input.text.slice(0, 400) },
                outcome: 'ok',
                match: usage.match,
                from_user_text: true
            }
        }));
    }
    return events;
}
function makeEvent(input) {
    const stable = [
        input.clientName,
        input.sessionId,
        String(input.turnIndex),
        input.type,
        input.skillSlug || '',
        input.type === 'document' ? String(input.payload.path || '') : '',
        input.type === 'tool' ? String(input.payload.name || '') : '',
        input.type === 'assistant' ? String(input.payload.text || '').slice(0, 80) : '',
        String(input.seq)
    ].join('|');
    const stepId = createHash('sha256').update(stable).digest('hex').slice(0, 24);
    return {
        v: 1,
        event_id: randomUUID(),
        client_id: input.clientId,
        client_name: input.clientName,
        session_id: input.sessionId,
        turn_index: input.turnIndex,
        step_id: stepId,
        seq: input.seq,
        type: input.type,
        ts: input.ts,
        skill_slug: input.skillSlug,
        skill_name: input.skillName,
        source: 'scan',
        payload: sanitizePayload(input.payload)
    };
}
function isUserTurn(role, content) {
    if (role !== 'user')
        return false;
    if (typeof content === 'string')
        return content.trim().length > 0;
    if (!Array.isArray(content))
        return false;
    const onlyToolResult = content.every((item) => asRecord(item).type === 'tool_result');
    return !onlyToolResult && extractText(content).trim().length > 0;
}
function claudeSessionId(file, entries) {
    const normalized = normalizePath(file);
    const marker = '/subagents/';
    const index = normalized.toLowerCase().lastIndexOf(marker);
    if (index >= 0) {
        const parent = basename(normalized.slice(0, index));
        const child = basename(normalized, '.jsonl');
        // Child id must come from the filename, not entry.sessionId (that field is the parent session).
        if (parent && child)
            return `${parent}/subagents/${child}`;
    }
    const fromEntry = entries.find((entry) => typeof entry.sessionId === 'string');
    return String(fromEntry?.sessionId || basename(file, '.jsonl'));
}
function assistantText(content) {
    if (typeof content === 'string')
        return sanitizeText(content).trim();
    if (!Array.isArray(content))
        return extractText(content).trim();
    const parts = [];
    for (const item of content) {
        const record = asRecord(item);
        const type = String(record.type || '');
        if (type && type !== 'text' && type !== 'output_text')
            continue;
        const text = typeof record.text === 'string' ? sanitizeText(record.text) : extractText(item);
        if (text.trim())
            parts.push(text.trim());
    }
    return parts.join('\n').trim();
}
function extractText(content) {
    if (typeof content === 'string')
        return sanitizeText(content);
    if (Array.isArray(content))
        return content.map((item) => extractText(item)).filter(Boolean).join('\n');
    if (content && typeof content === 'object') {
        const record = asRecord(content);
        if (typeof record.text === 'string')
            return sanitizeText(record.text);
        if (record.content !== undefined)
            return extractText(record.content);
    }
    return content == null ? '' : sanitizeText(JSON.stringify(content));
}
function parseMaybeJson(value) {
    if (typeof value !== 'string')
        return value ?? {};
    try {
        return JSON.parse(value);
    }
    catch {
        return value;
    }
}
function asRecord(value) {
    return value && typeof value === 'object' ? value : {};
}
function claudeProjectsRoot() {
    return process.env.SKILLHUB_CLAUDE_PROJECTS || join(homedir(), '.claude', 'projects');
}
function codexSessionsRoot() {
    return process.env.SKILLHUB_CODEX_SESSIONS || join(homedir(), '.codex', 'sessions');
}
async function claudeSessionIdFromFile(file) {
    const logical = claudeLogicalIdFromPath(file);
    if (logical)
        return logical;
    const fromEntry = await peekFirstJsonlSessionId(file);
    return fromEntry || basename(file, '.jsonl');
}
async function codexSessionIdFromFile(file) {
    const id = await peekCodexSessionId(file);
    return id || basename(file, '.jsonl');
}
async function peekFirstJsonlSessionId(file) {
    const lines = await peekJsonlLines(file, 3);
    for (const entry of lines) {
        const sessionId = String(entry.sessionId || '');
        if (sessionId)
            return sessionId;
    }
    return '';
}
async function peekCodexSessionId(file) {
    const lines = await peekJsonlLines(file, 12);
    for (const entry of lines) {
        if (entry.type === 'session_meta') {
            const id = String(asRecord(entry.payload).id || '');
            if (id)
                return id;
        }
    }
    return '';
}
async function peekJsonlLines(file, maxLines) {
    const rows = [];
    let handle;
    try {
        handle = createReadStream(file, { encoding: 'utf8' });
        const rl = createInterface({ input: handle, crlfDelay: Infinity });
        for await (const line of rl) {
            if (rows.length >= maxLines)
                break;
            const trimmed = line.trim();
            if (!trimmed)
                continue;
            try {
                const parsed = JSON.parse(trimmed);
                if (parsed && typeof parsed === 'object')
                    rows.push(parsed);
            }
            catch {
                // skip malformed
            }
        }
        rl.close();
    }
    catch {
        // ignore unreadable file
    }
    finally {
        handle?.destroy();
    }
    return rows;
}
export function sessionMatches(wanted, found, file, clientName) {
    if (!wanted)
        return true;
    const normalizedWanted = normalizePath(wanted);
    const normalizedFile = normalizePath(file);
    if (found && found === wanted)
        return true;
    if (normalizedFile === normalizedWanted)
        return true;
    if (clientName === 'claude-code') {
        const logicalId = claudeLogicalIdFromPath(file);
        if (logicalId) {
            // Subagent transcript: only exact logical id or full path. Bare child basename must not match.
            return logicalId === wanted;
        }
    }
    const fileBase = basename(normalizedFile);
    const wantedBase = basename(normalizedWanted);
    if (fileBase === `${wanted}.jsonl` || (wantedBase.endsWith('.jsonl') && fileBase === wantedBase))
        return true;
    return false;
}
function normalizePath(value) {
    return value.replace(/\\/g, '/');
}
function claudeLogicalIdFromPath(file) {
    const normalized = normalizePath(file);
    const marker = '/subagents/';
    const index = normalized.toLowerCase().lastIndexOf(marker);
    if (index < 0)
        return '';
    const parent = basename(normalized.slice(0, index));
    const child = basename(normalized, '.jsonl');
    return parent && child ? `${parent}/subagents/${child}` : '';
}
async function safeStat(path) {
    try {
        return await stat(path);
    }
    catch {
        return undefined;
    }
}
async function listFiles(root, depth, options) {
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
                if (!entry.endsWith('.jsonl'))
                    continue;
                if (options.sinceMs && info.mtimeMs < options.sinceMs)
                    continue;
                files.push(full);
            }
            catch {
                // skip
            }
        }
    }
    await walk(root, depth);
    return files;
}
async function readJsonl(path) {
    const rows = [];
    const rl = createInterface({ input: createReadStream(path, { encoding: 'utf8' }), crlfDelay: Infinity });
    for await (const line of rl) {
        const trimmed = line.trim();
        if (!trimmed)
            continue;
        try {
            const parsed = JSON.parse(trimmed);
            if (parsed && typeof parsed === 'object')
                rows.push(parsed);
        }
        catch {
            // skip
        }
    }
    return rows;
}
