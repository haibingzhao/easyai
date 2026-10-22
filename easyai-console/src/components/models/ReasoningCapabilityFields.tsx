import { useEffect, useState } from 'react';
import type { ReasoningProfile } from '@/types/settings';
import { EFFORT_LEVELS } from '@/constants/reasoning';
import { buildManualProfile, effortLabel, validateManualProfile } from './reasoning-controls';
import { i18n } from '@/utils/i18n';

export interface ReasoningDeclarationChange {
  /** Explicit declaration; an unchecked thinking mode emits NONE rather than leaving the row undeclared. */
  profile: ReasoningProfile;
  /** False for a pure source override: keep the row's current thinking/effort values. */
  applyDefaults: boolean;
  thinking: boolean;
  effort?: string;
}

interface ReasoningCapabilityFieldsProps {
  /** Resolved profile for this model id; UNKNOWN when nothing declares it. */
  profile: ReasoningProfile;
  onChange: (change: ReasoningDeclarationChange) => void;
}

/**
 * Declares thinking/effort capability for models the catalog cannot describe.
 *
 * A catalog- or vendor-doc-derived profile renders as a read-only summary with an explicit
 * Override action, so the vendor matrix is not edited by accident. An UNKNOWN model renders the
 * declaration directly: unchecking "thinking mode" emits an explicit manual NONE, while a routine
 * edit that never touches this block leaves the row undeclared.
 */
export function ReasoningCapabilityFields({ profile, onChange }: ReasoningCapabilityFieldsProps) {
  const declaredInitially = profile.mode !== 'NONE' && profile.mode !== 'UNKNOWN';
  const [enabled, setEnabled] = useState(declaredInitially);
  const [thinkingOnly, setThinkingOnly] = useState(profile.mode === 'ALWAYS_ON');
  const [defaultThinking, setDefaultThinking] = useState(profile.defaultThinking);
  const [levels, setLevels] = useState<string[]>(profile.effortLevels);
  const [defaultEffort, setDefaultEffort] = useState<string | undefined>(profile.defaultEffort);

  useEffect(() => {
    setEnabled(profile.mode !== 'NONE' && profile.mode !== 'UNKNOWN');
    setThinkingOnly(profile.mode === 'ALWAYS_ON');
    setDefaultThinking(profile.defaultThinking || profile.mode === 'ALWAYS_ON');
    setLevels(profile.effortLevels);
    setDefaultEffort(profile.defaultEffort);
  }, [profile.mode, profile.source, profile.effortLevels, profile.defaultEffort, profile.defaultThinking]);

  const emit = (next: {
    enabled: boolean;
    thinkingOnly: boolean;
    defaultThinking: boolean;
    levels: string[];
    defaultEffort?: string;
  }) => {
    if (!next.enabled) {
      onChange({
        profile: {
          mode: 'NONE',
          effortLevels: [],
          defaultThinking: false,
          source: 'manual',
        },
        applyDefaults: true,
        thinking: false,
        effort: undefined,
      });
      return;
    }
    const built = buildManualProfile({
      thinkingOnly: next.thinkingOnly,
      defaultThinking: next.defaultThinking,
      effortLevels: next.levels,
      defaultEffort: next.defaultEffort,
    });
    if (validateManualProfile(built) !== null) return;
    onChange({
      profile: built,
      applyDefaults: true,
      thinking: built.defaultThinking,
      effort: built.defaultEffort,
    });
  };

  // A known matrix is shown read-only until the user explicitly overrides it.
  if (profile.mode !== 'UNKNOWN' && profile.source !== 'manual') {
    return (
      <div className="rounded-md border border-border p-2 space-y-1">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium">{i18n('Thinking')}</span>
          <button
            type="button"
            className="text-[11px] text-primary hover:underline"
            onClick={() => {
              const manual: ReasoningProfile = { ...profile, source: 'manual' };
              setEnabled(profile.mode !== 'NONE');
              onChange({
                profile: manual,
                // The override only changes the source; the row's runtime switches stay as saved.
                applyDefaults: false,
                thinking: manual.defaultThinking,
                effort: manual.defaultEffort,
              });
            }}
          >
            {i18n('Override')}
          </button>
        </div>
        <div className="text-[11px] text-muted-foreground">
          {modeLabel(profile.mode)}
          {profile.effortLevels.length > 0
            ? ` · ${profile.effortLevels.map(effortLabel).join(' / ')}`
            : profile.mode === 'NONE' ? '' : ' · -'}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <label
        className="flex items-center gap-2 text-xs font-medium"
        title={i18n('Marks this model as a reasoning model; clients use this to enable related behavior')}
      >
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => {
            const next = e.target.checked;
            setEnabled(next);
            emit({ enabled: next, thinkingOnly, defaultThinking, levels, defaultEffort });
          }}
        />
        {i18n('Thinking mode')}
      </label>

      {enabled && (
        <>
          <label
            className="flex items-center gap-2 text-xs text-muted-foreground"
            title={i18n('The model always runs with thinking on; the picker will not offer turning it off')}
          >
            <input
              type="checkbox"
              checked={thinkingOnly}
              onChange={(e) => {
                const next = e.target.checked;
                setThinkingOnly(next);
                if (next) setDefaultThinking(true);
                emit({ enabled, thinkingOnly: next, defaultThinking: next || defaultThinking, levels, defaultEffort });
              }}
            />
            {i18n('Thinking only')}
          </label>
          <label
            className="flex items-center gap-2 text-xs text-muted-foreground"
            title={i18n('The switch state new sessions start with; users can still turn thinking off unless the model is thinking-only')}
          >
            <input
              type="checkbox"
              checked={thinkingOnly || defaultThinking}
              disabled={thinkingOnly}
              onChange={(e) => {
                const next = e.target.checked;
                setDefaultThinking(next);
                emit({ enabled, thinkingOnly, defaultThinking: next, levels, defaultEffort });
              }}
            />
            {i18n('Default thinking on')}
          </label>

          <div>
            <label className="text-xs font-medium mb-1 block">{i18n('Default effort')}</label>
            <select
              value={defaultEffort ?? ''}
              onChange={(e) => {
                const next = e.target.value || undefined;
                setDefaultEffort(next);
                emit({ enabled, thinkingOnly, defaultThinking, levels, defaultEffort: next });
              }}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="">{i18n('Automatic (request default)')}</option>
              {levels.map(level => (
                <option key={level} value={level}>{effortLabel(level)}</option>
              ))}
            </select>
          </div>

          <div>
            <label className="text-xs font-medium mb-1 block">{i18n('Supported effort levels')}</label>
            <div className="grid grid-cols-3 gap-1">
              {EFFORT_LEVELS.map(level => (
                <label key={level} className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <input
                    type="checkbox"
                    checked={levels.includes(level)}
                    onChange={(e) => {
                      const checked = e.target.checked;
                      const nextLevels = EFFORT_LEVELS.filter(l => (l === level ? checked : levels.includes(l)));
                      const nextDefault = defaultEffort && nextLevels.includes(defaultEffort)
                        ? defaultEffort
                        : undefined;
                      setLevels(nextLevels);
                      setDefaultEffort(nextDefault);
                      emit({ enabled, thinkingOnly, defaultThinking, levels: nextLevels, defaultEffort: nextDefault });
                    }}
                  />
                  {effortLabel(level)}
                </label>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function modeLabel(mode: ReasoningProfile['mode']): string {
  switch (mode) {
    case 'NONE': return i18n('Not supported');
    case 'ALWAYS_ON': return i18n('Always on');
    case 'TOGGLEABLE': return i18n('Toggleable');
    default: return i18n('Unknown');
  }
}
