export interface AgentSkillTarget {
    agent: string;
    path: string;
}
export declare function userSkillStoreRoot(): string;
export declare function installedAgentTargets(): Promise<AgentSkillTarget[]>;
export declare function exposeSkillToAgent(source: string, target: AgentSkillTarget, slug: string): Promise<'linked' | 'copied' | 'skipped'>;
