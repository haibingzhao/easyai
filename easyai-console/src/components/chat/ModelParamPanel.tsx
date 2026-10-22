import type { ModelOverrides, ModelProviderConfig } from '@/types/settings';
import { effortLabel, resolveReasoningControls } from '@/components/models/reasoning-controls';
import { i18n } from '@/utils/i18n';
import { formatTokenWindow } from '@/utils/format';

interface ModelParamPanelProps {
  config: ModelProviderConfig;
  value?: ModelOverrides;
  onChange: (next: ModelOverrides) => void;
  onClose: () => void;
  sheet?: boolean;
}

/**
 * Effort / working-window editor shown on hover or long-press. Selecting a value equal to the
 * model config's own default clears the override instead of storing a redundant copy.
 *
 * The window presets derive from the model's hard maximum (25/50/75/100%) so 128K and 1M models
 * both get useful choices instead of a fixed 200K/400K/600K list.
 */
export function ModelParamPanel({ config, value, onChange, onClose, sheet = false }: ModelParamPanelProps) {
  const controls = resolveReasoningControls(
    config.capabilities?.reasoning,
    config.protocol,
    config.options?.thinking === true
  );
  const configEffort = config.options?.effort;
  const configContext = config.options?.contextToken ?? 204_800;
  const maxContext = config.options?.maxContextTokens ?? 204_800;
  const effectiveContext = value?.contextToken ?? configContext;
  const contextOptions = buildContextOptions(maxContext, configContext);

  const setEffort = (effort?: string) => {
    if (effort && effort === configEffort) {
      onChange({ ...value, effort: undefined, contextToken: value?.contextToken });
    } else {
      onChange({ ...value, effort, contextToken: value?.contextToken });
    }
  };

  const setContext = (contextToken: number) => {
    if (contextToken === configContext) {
      onChange({ ...value, effort: value?.effort, contextToken: undefined });
    } else {
      onChange({ ...value, effort: value?.effort, contextToken });
    }
  };

  return (
    <div className="p-3 space-y-3">
      <div className={sheet ? 'flex items-start justify-between gap-2' : ''}>
        <div className="min-w-0">
          <div className="text-sm font-medium truncate">{config.name}</div>
          <div className="text-xs text-muted-foreground truncate">{config.modelName || config.modelId}</div>
        </div>
        {sheet && (
          <button type="button" onClick={onClose} className="text-xs text-primary shrink-0">
            {i18n('Done')}
          </button>
        )}
      </div>

      <div>
        <div className="text-xs font-medium mb-1">{i18n('Effort')}</div>
        {controls.strength === 'editable' ? (
          <div className="flex flex-wrap gap-1">
            <button
              type="button"
              onClick={() => setEffort(undefined)}
              className={`px-2 py-0.5 rounded border text-[11px] transition-colors ${
                !value?.effort
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border text-muted-foreground hover:text-foreground'
              }`}
            >
              {i18n('Default')}
            </button>
            {controls.effortLevels.map(level => (
              <button
                key={level}
                type="button"
                onClick={() => setEffort(level)}
                className={`px-2 py-0.5 rounded border text-[11px] transition-colors ${
                  value?.effort === level
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border text-muted-foreground hover:text-foreground'
                }`}
              >
                {effortLabel(level)}
              </button>
            ))}
          </div>
        ) : (
          <div className="text-xs text-muted-foreground">-</div>
        )}
        {controls.strength !== 'editable' && (
          <p className="mt-1 text-[11px] leading-tight text-muted-foreground">
            {controls.hint ? i18n(controls.hint) : i18n('Not supported')}
          </p>
        )}
        {controls.unknown && (
          <p className="mt-1 text-[11px] leading-tight text-muted-foreground">
            {i18n('Unknown model — controls follow protocol defaults')}
          </p>
        )}
      </div>

      <div>
        <div className="text-xs font-medium mb-1">{i18n('Context window')}</div>
        <div className="flex flex-wrap gap-1">
          {contextOptions.map(tokens => (
            <button
              key={tokens}
              type="button"
              onClick={() => setContext(tokens)}
              className={`px-2 py-0.5 rounded border text-[11px] transition-colors ${
                effectiveContext === tokens
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border text-muted-foreground hover:text-foreground'
              }`}
            >
              {formatTokenWindow(tokens)}
            </button>
          ))}
        </div>
        <p className="mt-1 text-[11px] leading-tight text-muted-foreground">
          {i18n('Model limit')}: {formatTokenWindow(maxContext)}
        </p>
      </div>
    </div>
  );
}

/** 25/50/75/100% of the model's hard window, plus its configured value when it fits. */
function buildContextOptions(maxContext: number, configured: number): number[] {
  const values = [0.25, 0.5, 0.75, 1]
    .map(fraction => Math.floor((maxContext * fraction) / 1024) * 1024)
    .filter(value => value > 0);
  if (configured > 0 && configured <= maxContext) values.push(configured);
  return [...new Set(values)].sort((a, b) => a - b);
}
