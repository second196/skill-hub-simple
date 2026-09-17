import { appendFile, mkdir } from 'node:fs/promises';
import { logPath, logsDir } from './paths.js';
export async function logObserver(message) {
    try {
        await mkdir(logsDir(), { recursive: true });
        const line = `${new Date().toISOString()} ${message.replace(/\s+/g, ' ').trim()}\n`;
        await appendFile(logPath(), line, 'utf8');
    }
    catch {
        // logging must never block upload or hook return
    }
}
