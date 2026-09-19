/**
 * Skill 调用 Token 用量
 *
 * 采集口径：
 * - Claude Code / Codex 会话 JSONL 中的 API usage
 * - skill 激活的回合窗口内累加
 * - 写入 skill 事件 payload.usage
 */
export interface SkillTokenUsage {
    inputTokens: number;
    cacheReadTokens: number;
    cacheWriteTokens: number;
    outputTokens: number;
    reasoningOutputTokens: number;
    totalTokens: number;
    requestCount: number;
    windowFromTs?: string;
    windowToTs?: string;
}
export declare function emptySkillTokenUsage(): SkillTokenUsage;
export declare function parseUsageRecord(raw: unknown): SkillTokenUsage | null;
export declare function parseClaudeMessageUsage(message: unknown): SkillTokenUsage | null;
export declare function parseCodexTokenCountPayload(payload: unknown): SkillTokenUsage | null;
export declare function addSkillTokenUsage(target: SkillTokenUsage, delta: SkillTokenUsage, ts?: string): void;
export declare function skillTokenUsageToPayload(usage: SkillTokenUsage): Record<string, unknown>;
export declare function usageFromEventPayload(payload: unknown): SkillTokenUsage | null;
export declare function isRollupSkillPayload(payload: unknown): boolean;
export declare function formatTokenCount(value: number): string;
