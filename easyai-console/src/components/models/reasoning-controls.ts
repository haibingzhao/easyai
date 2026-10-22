import type { ModelOptions, Protocol, ReasoningProfile } from '@/types/settings';
import { EFFORT_LEVELS, UNKNOWN_REASONING_PROFILE } from '@/constants/reasoning';
import { i18n } from '@/utils/i18n';

export type ToggleState = 'editable' | 'locked-on' | 'hidden';
export type StrengthState = 'editable' | 'disabled' | 'hidden';

export interface ReasoningControls {
  toggle: ToggleState;
  strength: StrengthState;
  /** Levels to offer, canonical order; empty when the model has no effort knob. */
  effortLevels: string[];
  unknown: boolean;
  /** Why strength cannot be edited right now (i18n source string). */
  hint?: string;
}

/**
 * The one place that turns a profile + protocol + thinking state into UI affordances.
 *
 * Kept in sync with the adapters' wire rules: Anthropic's budget and effort are mutually exclusive
 * (effort only while thinking is off), while the other protocols only carry effort while thinking
 * is on. `UNKNOWN` keeps both controls editable and lets adapters fall back to legacy behavior.
 */
export function resolveReasoningControls(
  profile: ReasoningProfile | null | undefined,
  protocol: Protocol | '' | undefined,
  thinking: boolean
): ReasoningControls {
  const resolved = profile ?? UNKNOWN_REASONING_PROFILE;
  if (resolved.mode === 'NONE') {
    return { toggle: 'hidden', strength: 'hidden', effortLevels: [], unknown: false };
  }

  const unknown = resolved.mode === 'UNKNOWN';
  const effortLevels = resolved.effortLevels.length > 0
    ? resolved.effortLevels
    : unknown ? [...EFFORT_LEVELS] : [];

  const toggle: ToggleState = resolved.mode === 'ALWAYS_ON' ? 'locked-on' : 'editable';
  if (effortLevels.length === 0) {
    return { toggle, strength: 'hidden', effortLevels, unknown };
  }
  if (unknown) {
    return { toggle, strength: 'editable', effortLevels, unknown };
  }

  const editable = resolved.mode === 'ALWAYS_ON'
    ? true
    : protocol === 'ANTHROPIC' ? !thinking : thinking;
  return {
    toggle,
    strength: editable ? 'editable' : 'disabled',
    effortLevels,
    unknown,
    hint: editable
      ? undefined
      : protocol === 'ANTHROPIC'
        ? 'Anthropic uses a fixed thinking budget while thinking is on; turn thinking off to set effort'
        : 'Effort only applies while thinking is on',
  };
}

/** What the manual-declaration block collects for an unknown model. */
export interface ReasoningDeclaration {
  thinkingOnly: boolean;
  /** Runtime default for the thinking switch; forced true for [thinkingOnly] models. */
  defaultThinking: boolean;
  effortLevels: string[];
  defaultEffort?: string;
}

/**
 * Turns an enabled declaration into a profile. Unchecking the capability writes an explicit NONE
 * before this builder is reached, so a disabled declaration never gets here.
 */
export function buildManualProfile(declaration: ReasoningDeclaration): ReasoningProfile {
  const levels = EFFORT_LEVELS.filter(level => declaration.effortLevels.includes(level));
  const defaultEffort = declaration.defaultEffort && levels.includes(declaration.defaultEffort)
    ? declaration.defaultEffort
    : undefined;
  return {
    mode: declaration.thinkingOnly ? 'ALWAYS_ON' : 'TOGGLEABLE',
    effortLevels: levels,
    defaultThinking: declaration.thinkingOnly || declaration.defaultThinking,
    defaultEffort,
    source: 'manual',
  };
}

/** Null when the profile is internally consistent; otherwise an i18n source string. */
export function validateManualProfile(profile: ReasoningProfile): string | null {
  const unknownLevels = profile.effortLevels.filter(level => !EFFORT_LEVELS.includes(level));
  if (unknownLevels.length > 0) return `Unknown effort levels: ${unknownLevels.join(', ')}`;
  if (profile.defaultEffort && !profile.effortLevels.includes(profile.defaultEffort)) {
    return 'Default effort must be one of the supported effort levels';
  }
  if (profile.mode === 'ALWAYS_ON' && !profile.defaultThinking) {
    return 'A thinking-only model must default thinking to on';
  }
  if (profile.mode === 'NONE' && (profile.effortLevels.length > 0 || profile.defaultEffort)) {
    return 'A model without thinking cannot offer effort levels';
  }
  return null;
}

/**
 * Drops an effort the profile/protocol gate no longer allows. Saving a legacy row whose thinking
 * and effort controls used to be independent would otherwise hit the backend's strict gate.
 */
export function sanitizeReasoningOptions(
  options: ModelOptions,
  profile: ReasoningProfile | null | undefined,
  protocol: Protocol | '' | undefined
): ModelOptions {
  if (options.effort === undefined) return options;
  const controls = resolveReasoningControls(profile, protocol, options.thinking === true);
  if (controls.strength === 'editable' && controls.effortLevels.includes(options.effort)) {
    return options;
  }
  return { ...options, effort: undefined };
}

/** Display label for an effort level; unknown names fall through verbatim. */
export function effortLabel(level: string): string {
  const labels: Record<string, string> = {
    low: 'Low',
    medium: 'Medium',
    high: 'High',
    xhigh: 'XHigh',
    max: 'Max',
  };
  const key = labels[level];
  return key ? i18n(key) : level;
}
