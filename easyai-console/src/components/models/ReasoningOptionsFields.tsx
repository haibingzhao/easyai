import { useEffect } from 'react';
import type { ModelOptions, Protocol, ReasoningProfile } from '@/types/settings';
import { effortLabel, resolveReasoningControls } from './reasoning-controls';
import { i18n } from '@/utils/i18n';

interface ReasoningOptionsFieldsProps {
  profile?: ReasoningProfile;
  protocol: Protocol | '';
  options: ModelOptions;
  setOptions: (updater: (prev: ModelOptions) => ModelOptions) => void;
}

/**
 * Runtime thinking switch + effort picker for one model config.
 *
 * All capability decisions come from [resolveReasoningControls], so the three model forms and the
 * composer panel cannot drift apart. Turning thinking off clears effort only when the new state
 * makes strength unavailable (OPENAI/DASHSCOPE); on ANTHROPIC, thinking off is exactly when effort
 * becomes editable, so the value is kept.
 */
export function ReasoningOptionsFields({ profile, protocol, options, setOptions }: ReasoningOptionsFieldsProps) {
  const thinking = options.thinking === true;
  const controls = resolveReasoningControls(profile, protocol, thinking);

  // A legacy row can carry effort alongside thinking (the old controls were independent), and a
  // profile/protocol change can shrink the offered levels. Clear the value as soon as the gate
  // says it cannot be expressed, so an untouched edit does not fail the backend's save check.
  useEffect(() => {
    if (options.effort === undefined) return;
    if (controls.strength === 'editable' && controls.effortLevels.includes(options.effort)) return;
    setOptions(prev => (prev.effort === options.effort ? { ...prev, effort: undefined } : prev));
  }, [controls.strength, controls.effortLevels, options.effort, setOptions]);

  const toggleThinking = () => {
    if (controls.toggle !== 'editable') return;
    const nextThinking = !thinking;
    const nextControls = resolveReasoningControls(profile, protocol, nextThinking);
    setOptions(prev => ({
      ...prev,
      thinking: nextThinking,
      effort: nextControls.strength === 'editable' ? prev.effort : undefined,
    }));
  };

  const switchOn = thinking || controls.toggle === 'locked-on';

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-xs font-medium">{i18n('Thinking')}</label>
        {controls.toggle === 'hidden' ? (
          <span className="text-xs text-muted-foreground">-</span>
        ) : (
          <button
            type="button"
            onClick={toggleThinking}
            disabled={controls.toggle === 'locked-on'}
            title={controls.toggle === 'locked-on' ? i18n('This model always runs with thinking on') : undefined}
            className={`relative w-9 h-5 rounded-full transition-colors ${
              switchOn ? 'bg-green-500' : 'bg-muted-foreground/30'
            } ${controls.toggle === 'locked-on' ? 'opacity-60 cursor-not-allowed' : ''}`}
          >
            <div
              className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${
                switchOn ? 'translate-x-4' : 'translate-x-0.5'
              }`}
            />
          </button>
        )}
      </div>

      <div>
        <label className="text-xs font-medium mb-1 block">{i18n('Effort')}</label>
        {controls.strength === 'hidden' ? (
          <div className="text-xs text-muted-foreground py-2">-</div>
        ) : (
          <select
            value={options.effort ?? ''}
            disabled={controls.strength === 'disabled'}
            onChange={(e) => setOptions(prev => ({ ...prev, effort: e.target.value || undefined }))}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm disabled:opacity-50"
          >
            <option value="">{i18n('Default')}</option>
            {controls.effortLevels.map(level => (
              <option key={level} value={level}>{effortLabel(level)}</option>
            ))}
          </select>
        )}
        {controls.hint && (
          <p className="mt-1 text-[11px] leading-tight text-muted-foreground">{i18n(controls.hint)}</p>
        )}
        {controls.unknown && (
          <p className="mt-1 text-[11px] leading-tight text-muted-foreground">
            {i18n('Unknown model — controls follow protocol defaults')}
          </p>
        )}
      </div>
    </div>
  );
}
