export type InitialSelection =
  | { kind: 'unselected' }
  | { kind: 'cleared' }
  | { kind: 'model'; configId: string };

export interface InitialSelectionInput {
  enabledIds: string[];
  selectedId: string | null | undefined;
  storedId: string | null;
  /** `allowEmptySelection` narrowed to controlled mode. */
  supportsEmptySelection: boolean;
}

/**
 * Which model the selector resolves on mount.
 *
 * - explicit null under empty mode is a real choice ("server default") and must not be pinned
 * - a controlled/stored id wins while it still exists, otherwise the first enabled model
 * - an empty-mode host whose id disappeared gets `cleared` so the host can drop its state
 * - with no enabled models at all nothing proves the id is stale, so it is left untouched
 */
export function resolveInitialSelection(input: InitialSelectionInput): InitialSelection {
  if (input.supportsEmptySelection && input.selectedId === null) return { kind: 'unselected' };
  if (input.enabledIds.length === 0) return { kind: 'unselected' };
  const currentId = input.selectedId ?? input.storedId ?? input.enabledIds[0];
  if (currentId && input.enabledIds.includes(currentId)) return { kind: 'model', configId: currentId };
  if (input.supportsEmptySelection) return { kind: 'cleared' };
  const fallback = input.enabledIds[0];
  return fallback ? { kind: 'model', configId: fallback } : { kind: 'unselected' };
}
