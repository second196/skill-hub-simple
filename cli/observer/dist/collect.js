import { scanAll } from './scan.js';
import { mergeEvents, readEvents } from './store.js';
export async function collectEvents() {
    await mergeEvents(await scanAll());
    return readEvents();
}
