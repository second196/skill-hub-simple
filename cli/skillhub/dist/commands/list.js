import { apiRequest } from '../clients/api-client.js';
/**
 * list --json returns a raw array of skills.
 * Each row includes: name, slug, category, version_label, status, ...
 * Empty catalog text message is `暂无Skill` (keep in sync with docs).
 */
export async function listCommand(options) {
    const params = new URLSearchParams({ status: 'ACTIVE' });
    if (options.category)
        params.set('category', options.category);
    const items = await apiRequest(options.serviceUrl, `/api/skills?${params}`);
    if (options.json) {
        return JSON.stringify(items.map((item) => ({
            name: item.name,
            slug: item.slug,
            category: item.category,
            version_label: item.version_label,
            status: item.status,
            description: item.description,
            download_count: item.download_count,
            updated_at: item.updated_at
        })));
    }
    if (!items.length)
        return '暂无Skill';
    return items
        .map((item) => `${String(item.name ?? '')}  ${String(item.slug ?? '')}  [${String(item.category ?? '')}]  v${String(item.version_label ?? '')}`)
        .join('\n');
}
