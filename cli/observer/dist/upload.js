import { drainQueue } from './drain.js';
import { listSessionSources } from './scan.js';
import { enqueueSession } from './store.js';
import { logObserver } from './log.js';
import { annotateSessions } from './ingest.js';
export { fetchPlatformSkills, platformIndex } from './platform.js';
export { annotateSessions };
export async function uploadObservations(serviceUrl) {
    const sources = await listSessionSources();
    for (const source of sources) {
        try {
            await enqueueSession({
                clientName: source.clientName,
                sessionId: source.sessionId,
                sourcePath: source.path,
                ended: true,
                sourceMtimeMs: source.mtimeMs
            });
        }
        catch (error) {
            await logObserver(`upload enqueue failed ${source.clientName} ${source.sessionId}: ${error instanceof Error ? error.message : String(error)}`);
        }
    }
    return drainQueue({ serviceUrl, reconcile: true });
}
