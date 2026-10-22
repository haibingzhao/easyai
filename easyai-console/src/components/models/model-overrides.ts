import type { ModelOverrides, ModelProviderConfig } from '@/types/settings';
import { resolveReasoningControls } from './reasoning-controls';

/**
 * Drop values the current config cannot honor. Saved prefs can outlive an effort-level, gate or
 * max-window change; keeping them would make a send fail validation. Used both when the picker
 * loads and immediately before sending.
 */
export function sanitizeOverrides(
  config: ModelProviderConfig,
  raw: ModelOverrides | null
): ModelOverrides | null {
  if (!raw) return null;
  const controls = resolveReasoningControls(
    config.capabilities?.reasoning,
    config.protocol,
    config.options?.thinking === true
  );
  const maxContext = config.options?.maxContextTokens ?? 204_800;
  const next: ModelOverrides = {};
  if (raw.effort && controls.strength === 'editable' && controls.effortLevels.includes(raw.effort)) {
    next.effort = raw.effort;
  }
  if (raw.contextToken != null && raw.contextToken > 0 && raw.contextToken <= maxContext) {
    next.contextToken = raw.contextToken;
  }
  return next.effort || next.contextToken != null ? next : null;
}
