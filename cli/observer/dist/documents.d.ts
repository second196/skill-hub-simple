export declare const MAX_DOCUMENT_BYTES: number;
export declare function isDocumentPath(path: string | undefined): boolean;
export declare function isCodePath(path: string | undefined): boolean;
export declare function readDocument(path: string): Promise<string | undefined>;
export declare function truncateText(value: string, maxBytes?: number): string;
export declare function extractPaths(value: unknown, found?: Set<string>): string[];
export declare function valueContains(value: unknown, needle: string): boolean;
