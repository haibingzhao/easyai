/**
 * Monotonic generation counter for "which session load is allowed to commit".
 *
 * Every asynchronous session load (switch, new session, leave, full reload after edit or
 * truncation) bumps it before issuing requests. A response is only allowed to touch the store
 * while its epoch is still current.
 *
 * Why not compare session ids: an A → B → A switch brings the id back to the same value, so an
 * id comparison cannot tell the stale A response from the fresh one. A counter can.
 */
let epoch = 0;

/** Start a new generation and return its number. */
export function bumpSessionEpoch(): number {
  return ++epoch;
}

/** True while [value] is still the newest generation. */
export function isCurrentEpoch(value: number): boolean {
  return value === epoch;
}

/** Current generation, for reads that must be validated later. */
export function currentEpoch(): number {
  return epoch;
}
