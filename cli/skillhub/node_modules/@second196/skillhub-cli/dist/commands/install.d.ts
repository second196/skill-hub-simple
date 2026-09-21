export interface InstallOptions {
    slug: string;
    serviceUrl: string;
    target?: string;
    version?: string;
    json: boolean;
}
export declare function installCommand(options: InstallOptions): Promise<string>;
