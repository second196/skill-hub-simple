/// <reference types="node" resolution-mode="require"/>
export declare function atomicWriteFile(target: string, data: string | Buffer, mode?: number): Promise<void>;
export declare function replaceFile(tmp: string, target: string): Promise<void>;
export declare function replaceFileSync(tmp: string, target: string): void;
export declare function fsyncDir(dir: string): void;
