import type { ModelOptions, Protocol } from '@/types/settings';

/**
 * Hint texts for the model generation options fields. English source strings,
 * used as i18n keys so the three model forms share one wording.
 */
export const MODEL_FIELD_HINTS = {
  maxTokens: 'Max output tokens per reply, sent as-is to the provider.',
  maxContextTokens: 'Hard upper bound of the model window. Only validated against Context Token, not used at runtime.',
  contextToken: 'Working window: compaction triggers near 80% of it, and the chat token bar divides by it.',
  temperature: 'Empty sends nothing and lets the provider default apply — it is not observable afterwards. Judgement-critical models should state a number.',
  temperatureNotSettable: 'This model rejects sampling parameters; nothing is sent.',
} as const;

/**
 * Sampling temperature a fresh row starts with.
 *
 * The backend no longer fills one in when the field is absent: an unset temperature means "send
 * nothing", and the provider's own default (1.0 on the official OpenAI and Anthropic endpoints)
 * silently decides how variable that model's answers are. So the form states the number out loud,
 * and clearing it becomes a deliberate act instead of a side effect of never touching the field.
 */
export const DEFAULT_FORM_TEMPERATURE = 0.7;

/**
 * Upper sampling-temperature bound per wire protocol, mirroring the server-side range check.
 *
 * One 0-2 slider across all protocols lets a row be saved that the gateway then rejects: Anthropic
 * documents temperature as 0.0-1.0, and DashScope's range is the half-open [0, 2). The exact 2.0
 * boundary is not approximated with a fake maximum here — {@link validateTemperature} catches it at
 * save instead.
 * A form whose protocol has not been picked yet (`''`) gets the wider bound; the save is still
 * validated server-side once the row exists.
 */
export function temperatureMax(protocol: Protocol | '' | undefined): number {
  return protocol === 'ANTHROPIC' ? 1 : 2;
}

/**
 * Server-side temperature bounds, restated: `DefaultModelConfigService.isTemperatureInRange`.
 *
 * The input's `max` only caps a slider — a typed value is not blocked by it — so the save would
 * reach the backend and come back rejected. DashScope is the half-open `[0, 2)`: 2.0 exactly is a
 * rejection there while the same number is legal on OpenAI. An absent temperature is not an error:
 * it means "send nothing" and the vendor default applies.
 */
export function validateTemperature(
  options: ModelOptions,
  protocol: Protocol | '' | undefined
): string | null {
  const temperature = options.temperature;
  if (temperature === undefined || temperature === null) return null;
  if (temperature < 0) return 'Temperature must not be below 0.0';
  if (protocol === 'ANTHROPIC' && temperature > 1) return 'Temperature must not exceed 1.0 for ANTHROPIC';
  if (protocol === 'DASHSCOPE' && temperature >= 2) return 'Temperature must stay below 2.0 for DASHSCOPE';
  if (temperature > 2) return 'Temperature must not exceed 2.0';
  return null;
}

/** Initial options for a create form: an explicit sampling value, everything else unset. */
export function initialModelOptions(): ModelOptions {
  return { temperature: DEFAULT_FORM_TEMPERATURE };
}

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
