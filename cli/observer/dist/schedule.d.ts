export declare function installBootTasks(): Promise<string>;
export type ScheduledRunner = (command: string, args: string[]) => Promise<string>;
export declare function setScheduledRunner(runner?: ScheduledRunner): void;
export declare function runScheduled(command: string, args: string[]): Promise<string>;
