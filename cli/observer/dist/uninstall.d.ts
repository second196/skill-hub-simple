export interface UninstallOptions {
    keepData?: boolean;
    keepCodexFeatures?: boolean;
    packages?: boolean;
}
export declare function uninstallObserver(options?: UninstallOptions): Promise<string>;
/** Only touch hooks under [features]; leave other tables alone. */
export declare function rewriteFeaturesHooks(text: string, value: 'true' | 'false'): string;
