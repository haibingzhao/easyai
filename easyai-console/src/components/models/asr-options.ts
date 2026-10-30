import type { AsrCapabilities } from '@/types/settings';

/** `media_options` as an object; keys this file does not recognise are left alone. */
export type AsrOptions = Record<string, unknown>;

export interface ReconcileAsrOptions {
  /**
   * The capability block of the model the row pointed at *before* this one, or null when the user
   * has not switched models (opening a stored row, first fill of a blank id).
   *
   * Without it nothing is dropped: a key can only be proven stale by the model that declared it.
   */
  previousCapabilities?: AsrCapabilities | null;
}

function parse(raw: string): AsrOptions | null {
  const trimmed = raw.trim();
  if (!trimmed) return {};
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
    return parsed as AsrOptions;
  } catch {
    return null;
  }
}

/**
 * Drop parameters the newly selected model does not declare.
 *
 * The parameter set is generational, not cosmetic: `vad_model` and `keep_dialect` exist on 3.1
 * only, `intermediate_result_enabled` on Message only. Carrying one over is either a 4xx or —
 * worse — silently ignored, so the request goes out looking configured when it is not. The form
 * stops rendering the control, but the stored key would still be sent.
 *
 * What it deliberately does not do:
 *  - touch unparseable JSON — the raw editor owns that state and shows the error;
 *  - drop a key neither block declares — it may be a field from a newer catalog than this build;
 *  - rewrite a surviving value, even one equal to the default. Reconcile only removes.
 */
export function reconcileAsrOptions(
  raw: string,
  capabilities: AsrCapabilities,
  { previousCapabilities = null }: ReconcileAsrOptions = {}
): string {
  const parsed = parse(raw);
  if (!parsed) return raw;
  const previous = previousCapabilities?.params ?? null;
  if (!previous) return raw;

  const next: AsrOptions = { ...parsed };
  const declared = capabilities.params ?? {};
  let dropped = false;
  for (const key of Object.keys(previous)) {
    if (key in declared) continue;
    if (!(key in next)) continue;
    delete next[key];
    dropped = true;
  }
  return dropped ? JSON.stringify(next) : raw;
}

/** The value a request would send for [key]: the row's own value, else the capability default. */
export function effectiveAsrValue(
  raw: string,
  capabilities: AsrCapabilities,
  key: string
): unknown {
  const parsed = parse(raw) ?? {};
  if (key in parsed) return parsed[key];
  return capabilities.params?.[key]?.default;
}
