import type { ObservationEvent, PlatformSkill } from './types.js';
export declare function writeReport(options: {
    output?: string;
    open?: boolean;
    serviceUrl?: string;
}): Promise<string>;
export declare function renderReport(events: ObservationEvent[], platform: PlatformSkill[], compared: boolean): string;
