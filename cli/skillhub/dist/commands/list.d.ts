export interface ListOptions {
    serviceUrl: string;
    category?: string;
    json: boolean;
}
export declare function listCommand(options: ListOptions): Promise<string>;
