import { scanAll } from './scan.js'
import { mergeEvents, readEvents } from './store.js'
import type { ObservationEvent } from './types.js'

export async function collectEvents(): Promise<ObservationEvent[]> {
  await mergeEvents(await scanAll())
  return readEvents()
}
