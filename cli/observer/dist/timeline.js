export function canonicalEvents(events) {
    const scanned = new Set(events.filter((event) => event.source === 'scan').map((event) => event.session_id));
    return events.filter((event) => event.source === 'scan' || !scanned.has(event.session_id));
}
export function buildTimeline(events) {
    const grouped = new Map();
    for (const event of canonicalEvents(events)) {
        const list = grouped.get(event.session_id) || [];
        list.push(event);
        grouped.set(event.session_id, list);
    }
    const sessions = [];
    for (const [sessionId, list] of grouped) {
        list.sort((a, b) => a.ts.localeCompare(b.ts) || a.turn_index - b.turn_index || a.seq - b.seq);
        const turns = new Map();
        for (const event of list) {
            const items = turns.get(event.turn_index) || [];
            items.push(event);
            turns.set(event.turn_index, items);
        }
        const turnViews = [...turns.entries()]
            .sort((a, b) => a[0] - b[0])
            .map(([turnIndex, steps]) => ({
            turnIndex,
            startedAt: steps[0]?.ts || '',
            userText: firstUserText(steps),
            steps
        }));
        sessions.push({
            sessionId,
            clientId: list[0]?.client_id || '',
            clientName: list[0]?.client_name || 'unknown',
            startedAt: turnViews[0]?.startedAt || list[0]?.ts || '',
            endedAt: list[list.length - 1]?.ts || '',
            turns: turnViews
        });
    }
    return sessions.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
}
export function skillStats(events) {
    const map = new Map();
    for (const event of canonicalEvents(events)) {
        if (event.type !== 'skill' || !event.skill_slug)
            continue;
        const current = map.get(event.skill_slug) || {
            slug: event.skill_slug,
            name: event.skill_name || event.skill_slug,
            callCount: 0,
            sessionCount: 0,
            clientCount: 0,
            lastUsedAt: event.ts,
            sessions: new Set(),
            clients: new Set()
        };
        current.callCount += 1;
        current.sessions.add(event.session_id);
        current.clients.add(event.client_id);
        current.name = event.skill_name || current.name;
        if (event.ts > current.lastUsedAt)
            current.lastUsedAt = event.ts;
        map.set(event.skill_slug, current);
    }
    return [...map.values()]
        .map((item) => ({
        slug: item.slug,
        name: item.name,
        callCount: item.callCount,
        sessionCount: item.sessions.size,
        clientCount: item.clients.size,
        lastUsedAt: item.lastUsedAt
    }))
        .sort((a, b) => b.lastUsedAt.localeCompare(a.lastUsedAt) || b.callCount - a.callCount);
}
export function trendCounts(events, days = 7) {
    const today = new Date();
    today.setUTCHours(0, 0, 0, 0);
    const counts = new Map();
    for (let i = days - 1; i >= 0; i -= 1) {
        const day = new Date(today);
        day.setUTCDate(today.getUTCDate() - i);
        counts.set(day.toISOString().slice(0, 10), 0);
    }
    for (const event of canonicalEvents(events)) {
        if (event.type !== 'skill')
            continue;
        const day = event.ts.slice(0, 10);
        if (counts.has(day))
            counts.set(day, (counts.get(day) || 0) + 1);
    }
    return [...counts.entries()].map(([day, count]) => ({ day, count }));
}
export function firstUserText(steps) {
    const user = steps.find((step) => step.type === 'user');
    const text = user?.payload?.text;
    return typeof text === 'string' ? text : '';
}
export function normalizeSkillKey(value) {
    return (value || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}
