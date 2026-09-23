export interface UninstallSkillOptions {
    slugs?: string[];
    all?: boolean;
    target?: string;
    json: boolean;
}
export declare function uninstallCommand(options: UninstallSkillOptions): Promise<string>;
