import type { ModelOptions } from '@/types/settings';

/**
 * Hint texts for the model generation options fields. English source strings,
 * used as i18n keys so the three model forms share one wording.
 */
export const MODEL_FIELD_HINTS = {
  maxTokens: 'Max output tokens per reply, sent as-is to the provider.',
  maxContextTokens: 'Hard upper bound of the model window. Only validated against Context Token, not used at runtime.',
  contextToken: 'Working window: compaction triggers near 80% of it, and the chat token bar divides by it.',
} as const;

/** ModelOptions defaults on the backend (easyai-api ModelProviderConfig.kt). */
const DEFAULT_MAX_CONTEXT_TOKENS = 204_800;
const DEFAULT_CONTEXT_TOKENS = 204_800;

/**
 * Returns an i18n key when Context Token exceeds Max Context Tokens, or null when valid.
 * Blank fields are compared using the backend defaults, since the backend fills them in
 * before validating and would otherwise reject the save.
 */
export function validateContextTokenBounds(options: ModelOptions): string | null {
  const maxContextTokens = options.maxContextTokens ?? DEFAULT_MAX_CONTEXT_TOKENS;
  const contextToken = options.contextToken ?? DEFAULT_CONTEXT_TOKENS;
  if (contextToken > maxContextTokens) return 'Context Token must not exceed Max Context Tokens';
  return null;
}
