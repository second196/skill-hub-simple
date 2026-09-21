export interface ListOptions {
    serviceUrl: string;
    category?: string;
    json: boolean;
}
/**
 * list --json returns a raw array of skills.
 * Each row includes: name, slug, category, version_label, status, ...
 * Empty catalog text message is `暂无Skill` (keep in sync with docs).
 */
export declare function listCommand(options: ListOptions): Promise<string>;
