import type { SpeechCapabilities } from '@/types/settings';

/** `media_options` as an object; every key the console does not understand is left alone. */
export type SpeechOptions = Record<string, unknown>;

/** Keys that only mean something for the voice they were chosen with. */
const VOICE_BOUND_KEYS = ['instruction', 'enable_ssml'] as const;

export interface ReconcileOptions {
  /**
   * Write the model's recommended voice into a row that has none. Only ever set for a row the user
   * has not filled in yet: re-seeding on every lookup would undo a deliberate "model default".
   */
  seedVoice?: boolean;
  /**
   * Voices the model that was selected *before* this one offered. Used to tell a stale voice from a
   * custom one: a voice that belonged to the previous catalog provably cannot run on the new model,
   * while a voice no catalog knows may simply be newer than the seeded list (a cloned voice, a
   * vendor addition) and must be left alone rather than deleted from the user's row.
   */
  previousVoices?: ReadonlySet<string> | null;
}

function parse(raw: string): SpeechOptions | null {
  const trimmed = raw.trim();
  if (!trimmed) return {};
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
    return parsed as SpeechOptions;
  } catch {
    return null;
  }
}

/**
 * Re-align a stored `media_options` string with the catalog entry of its model.
 *
 * Called when the model id changes, because the option object describes a *combination* of model
 * and voice: a voice from the previous model, a format or sample rate the new model does not offer,
 * or an instruction that only suited the old voice would otherwise be carried over and rejected by
 * the service at synthesis time.
 *
 * What it deliberately does **not** do:
 *  - delete a voice that no catalog knows — see [ReconcileOptions.previousVoices];
 *  - touch unparseable JSON — the raw editor owns that state and shows the error, so rewriting it
 *    here would destroy the user's text;
 *  - drop a key whose capability the catalog does not state — only an explicit `false` disables it,
 *    so a hand-written or older catalog entry cannot strip fields by omission.
 *
 * Unknown custom keys survive in every case.
 */
export function reconcileSpeechOptions(
  raw: string,
  capabilities: SpeechCapabilities,
  { seedVoice = false, previousVoices = null }: ReconcileOptions = {}
): string {
  const parsed = parse(raw);
  if (!parsed) return raw;

  const next: SpeechOptions = { ...parsed };
  const voices = capabilities.voices ?? [];
  const voice = typeof next.voice === 'string' ? next.voice : '';
  const known = voices.find((candidate) => candidate.voice === voice) ?? null;

  // Only a voice the *previous* model owned is provably unusable here; an unknown voice may be
  // newer than this catalog and is preserved for the service to judge.
  if (voice && !known && previousVoices?.has(voice)) {
    delete next.voice;
    // The instruction and the SSML switch were written for that voice, not for the model.
    for (const key of VOICE_BOUND_KEYS) delete next[key];
  }

  if (known?.instruction === false) delete next.instruction;
  if (known?.ssml === false) delete next.enable_ssml;

  const formats = capabilities.formats ?? [];
  if (typeof next.format === 'string' && formats.length > 0 && !formats.includes(next.format)) {
    delete next.format;
  }
  const sampleRates = capabilities.sampleRates ?? [];
  if (typeof next.sample_rate === 'number' && sampleRates.length > 0 && !sampleRates.includes(next.sample_rate)) {
    delete next.sample_rate;
  }
  if (capabilities.supportsInstruction === false) delete next.instruction;
  if (capabilities.supportsSsml === false) delete next.enable_ssml;

  if (seedVoice && !next.voice && capabilities.defaultVoice) {
    next.voice = capabilities.defaultVoice;
  }

  const before = JSON.stringify(parsed);
  const after = JSON.stringify(next);
  return before === after ? raw : after;
}
