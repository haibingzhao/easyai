import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Check, Copy } from 'lucide-react';
import type {
  ScriptDirectionParams,
  ScriptTtsParams,
} from '@/types/speech-script';
import { i18n } from '@/utils/i18n';

interface ScriptParamsPanelProps {
  ttsParams: ScriptTtsParams;
  directionParams: ScriptDirectionParams | null;
}

/** One label/value line. Values are rendered as text, never as markup, and may be long. */
const Row: React.FC<{ label: string; value: React.ReactNode; mono?: boolean }> = ({
  label,
  value,
  mono = true,
}) => {
  if (value === null || value === undefined || value === '') return null;
  return (
    <div className="grid grid-cols-[10rem_1fr] gap-2 py-0.5">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={`break-words ${mono ? 'font-mono' : ''}`}>{value}</dd>
    </div>
  );
};

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <section className="rounded-md border border-border p-3">
    <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
      {title}
    </h4>
    <dl className="space-y-0.5 text-xs">{children}</dl>
  </section>
);

/** `null` and `0` mean different things here: unset versus a real zero rate. */
const show = (value: unknown): string | null =>
  value === null || value === undefined || value === '' ? null : String(value);

const yesNo = (value: boolean | null | undefined): string | null =>
  value === null || value === undefined ? null : value ? i18n('Yes') : i18n('No');

/**
 * What the TTS model was asked for, read-only.
 *
 * This is a snapshot, not a live view: it answers "why did this sound like that" for a script that
 * may be months old, so it must never resolve anything against the current model rows. The copy
 * button exists because the common next step is pasting this into a bug report.
 */
export const ScriptParamsPanel: React.FC<ScriptParamsPanelProps> = ({ ttsParams, directionParams }) => {
  const [copied, setCopied] = useState(false);

  const json = useMemo(
    () => JSON.stringify({ ttsParams, directionParams }, null, 2),
    [ttsParams, directionParams]
  );

  const copiedTimer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (copiedTimer.current !== null) window.clearTimeout(copiedTimer.current);
    },
    []
  );

  const copy = useCallback(() => {
    void (async () => {
      try {
        await navigator.clipboard.writeText(json);
        setCopied(true);
        if (copiedTimer.current !== null) window.clearTimeout(copiedTimer.current);
        copiedTimer.current = window.setTimeout(() => setCopied(false), 1500);
      } catch {
        // A blocked clipboard is not worth an error banner; the JSON is on screen either way.
      }
    })();
  }, [json]);

  const { provider, synthesis, rowDefaults, overrides, perSegment, cost } = ttsParams;

  return (
    <div className="space-y-3" data-script-params>
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">
          {i18n('Captured when the plan was made — later model changes do not rewrite it.')}
        </p>
        <button
          type="button"
          onClick={copy}
          className="inline-flex shrink-0 items-center gap-1 rounded-md border border-border px-2 py-1 text-xs hover:bg-muted"
          title={i18n('Copy as JSON')}
        >
          {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
          {copied ? i18n('Copied') : i18n('Copy as JSON')}
        </button>
      </div>

      <Section title={i18n('Model')}>
        <Row label={i18n('Model')} value={provider.modelDisplayName ?? provider.modelId} />
        <Row label={i18n('Model id')} value={provider.modelId} />
        <Row label={i18n('Model config id')} value={provider.modelConfigId} />
        <Row label={i18n('Protocol')} value={provider.protocol} />
        <Row label={i18n('Endpoint host')} value={provider.baseUrlHost} />
        <Row label={i18n('Snapshot version')} value={String(ttsParams.schemaVersion)} />
        <Row
          label={i18n('Captured at')}
          value={new Date(ttsParams.capturedAt).toLocaleString()}
          mono={false}
        />
      </Section>

      <Section title={i18n('Synthesis')}>
        <Row label={i18n('Mode')} value={ttsParams.mode} />
        <Row label={i18n('Format')} value={synthesis.format} />
        <Row label={i18n('MIME type')} value={synthesis.mimeType} />
        <Row label={i18n('Sample rate')} value={show(synthesis.sampleRate)} />
        <Row label={i18n('Bit rate')} value={show(synthesis.bitRate)} />
        <Row label={i18n('Input mode')} value={synthesis.inputMode} />
        <Row label={i18n('Timeout')} value={`${synthesis.timeoutSeconds}s`} />
        <Row label={i18n('Characters')} value={String(cost.characters)} />
        <Row label={i18n('Character limit')} value={String(cost.maxChars)} />
      </Section>

      {rowDefaults && (
        <Section title={i18n('Model row defaults')}>
          <Row label={i18n('Voice')} value={rowDefaults.voice} />
          <Row label={i18n('Word timestamps')} value={yesNo(rowDefaults.wordTimestampEnabled)} />
          {rowDefaults.options && Object.keys(rowDefaults.options).length > 0 && (
            <Row
              label={i18n('Row options')}
              value={<pre className="whitespace-pre-wrap text-[11px]">{JSON.stringify(rowDefaults.options, null, 2)}</pre>}
            />
          )}
        </Section>
      )}

      <Section title={i18n('Overrides')}>
        <Row label={i18n('Voice')} value={overrides.voice} />
        <Row label={i18n('Instruction')} value={overrides.instruction} />
        <Row label={i18n('Emotion tag')} value={overrides.emotionTag} />
        <Row label={i18n('Rate')} value={show(overrides.rate)} />
        <Row label={i18n('Pitch')} value={show(overrides.pitch)} />
        {!overrides.voice &&
          !overrides.instruction &&
          !overrides.emotionTag &&
          overrides.rate === null &&
          overrides.pitch === null && (
            <Row label="—" value={i18n('No overrides were sent')} mono={false} />
          )}
      </Section>

      <Section title={i18n('Per segment')}>
        {perSegment.length === 0 ? (
          <Row label="—" value={i18n('No per-segment parameters')} mono={false} />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-[11px]">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-1 pr-2 font-normal">#</th>
                  <th className="py-1 pr-2 font-normal">{i18n('Voice')}</th>
                  <th className="py-1 pr-2 font-normal">{i18n('Role')}</th>
                  <th className="py-1 pr-2 font-normal">{i18n('Emotion tag')}</th>
                  <th className="py-1 pr-2 font-normal">{i18n('Rate')}</th>
                  <th className="py-1 pr-2 font-normal">{i18n('Pause after')}</th>
                  <th className="py-1 pr-2 font-normal">{i18n('Chars in / out')}</th>
                </tr>
              </thead>
              <tbody className="font-mono">
                {perSegment.map((segment, index) => (
                  <tr key={segment.segmentId} className="border-t border-border/60">
                    <td className="py-1 pr-2">{index + 1}</td>
                    <td className="py-1 pr-2">{segment.voice}</td>
                    <td className="py-1 pr-2">{segment.role}</td>
                    <td className="py-1 pr-2">{segment.emotion ?? '—'}</td>
                    <td className="py-1 pr-2">{segment.rate ?? '—'}</td>
                    <td className="py-1 pr-2">{segment.pauseAfterMs}ms</td>
                    <td className="py-1 pr-2">
                      {segment.sourceChars} → {segment.spokenChars}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {directionParams && (
        <Section title={i18n('Direction model')}>
          <Row label={i18n('Model id')} value={directionParams.modelId} />
          <Row label={i18n('Model config id')} value={directionParams.modelConfigId} />
          <Row label={i18n('Protocol')} value={directionParams.protocol} />
          <Row label={i18n('Sampling')} value={directionParams.samplingIntent} />
          <Row label={i18n('Thinking')} value={yesNo(directionParams.thinking)} />
          <Row label={i18n('Strategy')} value={directionParams.strategy} />
          <Row label={i18n('Prompt version')} value={`v${directionParams.promptVersion}`} />
          <Row label={i18n('Prompt hash')} value={directionParams.promptHash} />
          <Row label={i18n('Schema version')} value={String(directionParams.schemaVersion)} />
          <Row label={i18n('Timeout')} value={`${directionParams.timeoutMs}ms`} />
        </Section>
      )}

      {!directionParams && (
        <p className="text-xs text-muted-foreground">
          {i18n('This script was read straight through; no planning model was called.')}
        </p>
      )}
    </div>
  );
};
