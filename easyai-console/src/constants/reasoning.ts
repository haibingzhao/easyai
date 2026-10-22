import type { ReasoningProfile } from '@/types/settings';

/** Every effort name the backend adapters understand, in ascending order. */
export const EFFORT_LEVELS: readonly string[] = ['low', 'medium', 'high', 'xhigh', 'max'];

/** Fallback profile for models nothing is known about: controls stay editable, adapters use legacy. */
export const UNKNOWN_REASONING_PROFILE: ReasoningProfile = {
  mode: 'UNKNOWN',
  effortLevels: [],
  defaultThinking: false,
  source: 'unknown',
};
