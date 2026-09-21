export interface InstallOptions {
    slug: string;
    serviceUrl: string;
    target?: string;
    /** SemVer version_label declared inside the skill package (not a content digest). */
    skillVersion?: string;
    json: boolean;
}
export declare function installCommand(options: InstallOptions): Promise<string>;
