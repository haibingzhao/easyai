import React, { useEffect, useId, useMemo, useState } from 'react';
import { ExternalLink } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import type { AsrCapabilities, AsrParamSpec } from '@/types/settings';

interface AsrModelFieldsProps {
  capabilities: AsrCapabilities;
  /** The form's raw `media_options` JSON; this component is one of its editors. */
  options: string;
  onChange: (next: string) => void;
}

type OptionsObject = Record<string, unknown>;
type ParsedOptions = { value: OptionsObject } | { error: string };

const inputClass = 'flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm';

const CHANNEL_LABELS: Record<string, string> = {
  websocket: 'Realtime WebSocket',
  http: 'Segmented HTTP',
  none: 'Not usable for microphone input',
};

function parseOptions(raw: string): ParsedOptions {
  if (!raw.trim()) return { value: {} };
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { error: i18n('Generation options must be a JSON object') };
    }
    return { value: parsed as OptionsObject };
  } catch {
    return { error: i18n('Generation options must be a JSON object') };
  }
}

function sameValue(a: unknown, b: unknown): boolean {
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((item, i) => JSON.stringify(item) === JSON.stringify(b[i]));
  }
  if (typeof a === 'object' && typeof b === 'object' && a !== null && b !== null) {
    return JSON.stringify(a) === JSON.stringify(b);
  }
  return a === b;
}

/**
 * Capability-driven fields for one ASR model row.
 *
 * The block decides everything: which parameters exist, what each accepts, and which one the
 * product fixes. A parameter the model does not declare is not rendered at all (not disabled), so
 * the generational differences — 3.0 has no `vad_model`, Message alone has the intermediate-result
 * switch — cannot be written into the wrong row by hand. `media_options` stays the single source of
 * truth and keeps the raw JSON textarea usable: unparseable JSON disables the controls instead of
 * being overwritten.
 *
 * Writing a value equal to the capability default removes the key rather than storing it, so a row
 * only ever carries deliberate deviations and a later change of the project default still reaches
 * rows that never overrode it.
 */
export const AsrModelFields: React.FC<AsrModelFieldsProps> = ({ capabilities, options, onChange }) => {
  const parsed = useMemo(() => parseOptions(options), [options]);
  const fieldId = useId();
  const current = 'value' in parsed ? parsed.value : {};
  const params = capabilities.params ?? {};

  const write = (key: string, spec: AsrParamSpec, value: unknown) => {
    if (!('value' in parsed)) return;
    const next: OptionsObject = { ...parsed.value };
    if (value === undefined || value === null || value === '' || sameValue(value, spec.default)) {
      delete next[key];
    } else {
      next[key] = value;
    }
    onChange(JSON.stringify(next));
  };

  if ('error' in parsed) {
    return (
      <div className="p-3 rounded-md bg-destructive/10 text-destructive text-sm">
        {parsed.error}
        <p className="text-xs mt-1 text-destructive/80">
          {i18n('Fix the Generation Options JSON above before using the structured fields.')}
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3 rounded-md border border-border p-4 bg-muted/20">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium text-muted-foreground">{i18n('Recognition Settings')}</span>
        {capabilities.docsUrl && (
          <a
            href={capabilities.docsUrl}
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            {i18n('Protocol reference')} <ExternalLink className="w-3 h-3" />
          </a>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3 text-xs">
        <div>
          <span className="text-muted-foreground">{i18n('Transport')}</span>
          <p className="text-sm font-medium">
            {i18n(CHANNEL_LABELS[capabilities.channel] ?? capabilities.channel)}
          </p>
        </div>
        <div>
          <span className="text-muted-foreground">{i18n('Audio')}</span>
          <p className="text-sm font-medium">
            {(capabilities.audio?.sampleRates?.[0] ?? 16000) / 1000} kHz · {i18n('mono')} · PCM16LE
          </p>
        </div>
      </div>

      {capabilities.channel === 'none' ? (
        <p className="text-xs text-destructive">
          {i18n('This model transcribes files, not a live microphone; pick a realtime model for voice input.')}
        </p>
      ) : (
        <>
          <AsrFeatureBadges capabilities={capabilities} />

          {Object.entries(params).map(([key, spec]) => {
            const id = `${fieldId}-${key}`;
            const value = key in current ? current[key] : spec.default;
            return (
              <div key={key}>
                <label htmlFor={id} className="text-sm font-medium mb-1 flex items-center gap-2">
                  <span>{key}</span>
                  {spec.locked && (
                    <span className="text-xs px-1.5 py-0.5 rounded bg-primary/10 text-primary">
                      {i18n('Fixed')}
                    </span>
                  )}
                  {spec.finalOnly && (
                    <span className="text-xs text-muted-foreground">{i18n('final results only')}</span>
                  )}
                </label>
                {spec.locked ? (
                  <p id={id} className="text-sm text-muted-foreground">
                    {String(spec.default)} · {i18n('always sent by chat dictation')}
                  </p>
                ) : (
                  <AsrParamControl id={id} name={key} spec={spec} value={value} onChange={(next) => write(key, spec, next)} />
                )}
                {(spec.note || spec.unit) && (
                  <p className="text-xs text-muted-foreground mt-1">
                    {[spec.note, spec.unit && `${i18n('unit')}: ${spec.unit}`].filter(Boolean).join(' · ')}
                    {spec.vendorDefault !== undefined &&
                      ` — ${i18n('documented default')}: ${String(spec.vendorDefault)}`}
                  </p>
                )}
              </div>
            );
          })}

          <p className="text-xs text-muted-foreground">
            {i18n('Chat dictation always streams 16 kHz mono PCM; these settings shape the request, not the capture chain.')}
          </p>
        </>
      )}
    </div>
  );
};

const AsrParamControl: React.FC<{
  id: string;
  name: string;
  spec: AsrParamSpec;
  value: unknown;
  onChange: (next: unknown) => void;
}> = ({ id, name, spec, value, onChange }) => {
  switch (spec.type) {
    case 'enum':
      return (
        <select id={id} value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)} className={inputClass}>
          <option value="">{i18n('Project default')}</option>
          {(spec.options ?? []).map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
      );
    case 'int':
      return <AsrIntField id={id} name={name} spec={spec} value={value} onChange={onChange} />;
    case 'bool':
      return (
        <label className="flex items-center gap-2 text-sm">
          <input id={id} type="checkbox" checked={value === true} onChange={(e) => onChange(e.target.checked)} />
          <span className="text-muted-foreground">
            {value === true ? i18n('On') : i18n('Off')}
          </span>
        </label>
      );
    case 'string':
      return (
        <input id={id} type="text" value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)} className={inputClass} />
      );
    case 'string[]':
      return (
        <AsrListField id={id} spec={spec} value={value} onChange={onChange} />
      );
    case 'map':
      return <AsrJsonField id={id} spec={spec} value={value} onChange={onChange} />;
    default:
      // An unknown type can only come from a catalog newer than this build; the raw JSON textarea
      // still edits it, so refusing to guess a control is the honest option.
      return <p className="text-xs text-muted-foreground">{i18n('Unsupported control; edit the raw JSON above.')}</p>;
  }
};

/** Number editing keeps the typed text until it parses, otherwise a half-typed value would be lost. */
const AsrIntField: React.FC<{
  id: string;
  name: string;
  spec: AsrParamSpec;
  value: unknown;
  onChange: (next: unknown) => void;
}> = ({ id, name, spec, value, onChange }) => {
  const [draft, setDraft] = useState(value === undefined ? '' : String(value));
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDraft(value === undefined ? '' : String(value));
  }, [value]);

  const commit = (raw: string) => {
    setDraft(raw);
    if (raw.trim() === '') {
      setError(null);
      onChange(undefined);
      return;
    }
    const parsed = Number(raw);
    if (!Number.isInteger(parsed)) {
      setError(i18n('Enter a whole number'));
      return;
    }
    if ((spec.min !== undefined && parsed < spec.min) || (spec.max !== undefined && parsed > spec.max)) {
      setError(`${i18n('Out of range')}: ${spec.min ?? '-∞'}–${spec.max ?? '∞'}`);
      return;
    }
    setError(null);
    onChange(parsed);
  };

  return (
    <>
      <input
        id={id}
        name={name}
        type="number"
        value={draft}
        min={spec.min}
        max={spec.max}
        onChange={(e) => commit(e.target.value)}
        className={inputClass}
      />
      {error && <p className="text-xs text-destructive mt-1">{error}</p>}
    </>
  );
};

/** One entry per line: `string[]` parameters are short lists whose order the service keeps. */
const AsrListField: React.FC<{
  id: string;
  spec: AsrParamSpec;
  value: unknown;
  onChange: (next: unknown) => void;
}> = ({ id, spec, value, onChange }) => {
  const [error, setError] = useState<string | null>(null);
  // The textarea owns the raw text: deriving it from `value` on every render would drop the empty
  // line the user just typed (Enter would appear to do nothing) and remount the element per
  // keystroke, taking the caret with it.
  const [draft, setDraft] = useState(() => toLines(value));

  useEffect(() => {
    const next = toLines(value);
    // Only an external change may replace the text; the round trip through `onChange` must not.
    if (normalizeLines(next) !== normalizeLines(draft)) setDraft(next);
  }, [value, draft]);

  const commit = (raw: string) => {
    setDraft(raw);
    const items = normalizeLines(raw).split('\n').filter((line) => line !== '');
    if (spec.maxItems !== undefined && items.length > spec.maxItems) {
      setError(`${i18n('At most')} ${spec.maxItems}`);
      return;
    }
    setError(null);
    onChange(items.length === 0 ? undefined : items);
  };

  return (
    <>
      <textarea
        id={id}
        value={draft}
        rows={3}
        onChange={(e) => commit(e.target.value)}
        placeholder={i18n('One value per line')}
        className="flex w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
      />
      {error && <p className="text-xs text-destructive mt-1">{error}</p>}
    </>
  );
};

/** The list as the textarea shows it: one entry per line. */
function toLines(value: unknown): string {
  return Array.isArray(value) ? value.join('\n') : '';
}

/** Trimmed, blank-free form used to tell "the same list" from "a real edit". */
function normalizeLines(text: string): string {
  return text.split('\n').map((line) => line.trim()).filter((line) => line !== '').join('\n');
}

/** Key/value parameters (hot words) are a JSON object; invalid JSON is reported, never stored. */
const AsrJsonField: React.FC<{
  id: string;
  spec: AsrParamSpec;
  value: unknown;
  onChange: (next: unknown) => void;
}> = ({ id, value, onChange }) => {
  const [draft, setDraft] = useState(value === undefined ? '' : JSON.stringify(value));
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDraft(value === undefined ? '' : JSON.stringify(value));
  }, [value]);

  const commit = (raw: string) => {
    setDraft(raw);
    if (raw.trim() === '') {
      setError(null);
      onChange(undefined);
      return;
    }
    try {
      const parsed: unknown = JSON.parse(raw);
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        setError(i18n('Must be a JSON object'));
        return;
      }
      setError(null);
      onChange(parsed);
    } catch {
      setError(i18n('Must be a JSON object'));
    }
  };

  return (
    <>
      <textarea
        id={id}
        value={draft}
        rows={3}
        onChange={(e) => commit(e.target.value)}
        placeholder='{"热词": 5}'
        className="flex w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
      />
      {error && <p className="text-xs text-destructive mt-1">{error}</p>}
    </>
  );
};

const AsrFeatureBadges: React.FC<{ capabilities: AsrCapabilities }> = ({ capabilities }) => {
  const features = capabilities.features;
  const badges: Array<{ label: string; on: boolean }> = [
    { label: i18n('Context'), on: (features?.context ?? 'none') !== 'none' },
    { label: i18n('Dynamic context'), on: features?.continueTask === true },
    { label: i18n('Speaker split'), on: features?.speakerDiarization === true },
    { label: i18n('Emotion'), on: features?.emotion === true },
  ];
  return (
    <div className="flex flex-wrap gap-1">
      {badges.map((badge) => (
        <span
          key={badge.label}
          className={`text-xs px-1.5 py-0.5 rounded ${
            badge.on ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground line-through'
          }`}
        >
          {badge.label}
        </span>
      ))}
    </div>
  );
};
