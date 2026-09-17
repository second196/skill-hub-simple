export interface UploadOptions {
    inputPaths: string[];
    serviceUrl: string;
    category: string;
    name?: string;
    description?: string;
    json: boolean;
}
export declare function uploadCommand(options: UploadOptions): Promise<string>;
