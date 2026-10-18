import React from 'react';
import { i18n } from '@/utils/i18n';

interface ConsolidationSwitchProps {
  enabled: boolean;
  /** Consolidation needs a curation model; until one is configured the switch stays off-limits. */
  modelConfigured: boolean;
  /** False while the settings have not loaded: nothing can be read or written yet. */
  available?: boolean;
  onChange: (enabled: boolean) => void;
}

/**
 * The consolidation master switch, shared by the Memories sidebar and the settings dialog. Both
 * surfaces used to carry their own copy of this markup, including a slightly different tooltip.
 */
export const ConsolidationSwitch: React.FC<ConsolidationSwitchProps> = ({
  enabled,
  modelConfigured,
  available = true,
  onChange,
}) => (
  <button
    onClick={() => onChange(!enabled)}
    disabled={!available || (!enabled && !modelConfigured)}
    className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
      enabled ? 'bg-green-500' : 'bg-muted-foreground/30'
    }`}
    title={
      !modelConfigured
        ? i18n('Memory curation model not configured')
        : i18n('Enable Memory Consolidation')
    }
  >
    <span
      className={`inline-block h-3.5 w-3.5 rounded-full bg-white transition-transform ${
        enabled ? 'translate-x-[18px]' : 'translate-x-0.5'
      }`}
    />
  </button>
);
