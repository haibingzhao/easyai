import React, { useId, useMemo, useState } from 'react';
import { ExternalLink, Search } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import type { SpeechCapabilities, VoiceSpec } from '@/types/settings';

interface SpeechModelFieldsProps {
  capabilities: SpeechCapabilities;
  /** The form's raw `media_options` JSON; this component is one of its editors. */
  options: string;
  onChange: (next: string) => void;
}

type OptionsObject = Record<string, unknown>;

type ParsedOptions = { value: OptionsObject } | { error: string };

const GENDER_LABELS: Record<string, string> = {
  female: 'Female',
  male: 'Male',
  child: 'Child',
};

const inputClass = 'flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm';

const FORMAT_HINTS: Record<string, string> = {
  mp3: 'MP3',
  wav: 'WAV — generation tools only; chat read-aloud always uses MP3',
  pcm: 'PCM — generation tools only; chat read-aloud always uses MP3',
  opus: 'Opus — generation tools only; chat read-aloud always uses MP3',
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

/** `名称 · 首个特质 (voice)` — the vendor's own words, so a user can pick by ear-description. */
function voiceLabel(voice: VoiceSpec): string {
  const trait = voice.traits?.[0];
  const base = trait ? `${voice.name} · ${trait}` : voice.name;
  const unsupported = voice.ssml === false ? ` — ${i18n('no SSML / instruction')}` : '';
  return `${base} (${voice.voice})${unsupported}`;
}

/**
 * Capability-driven fields for one SPEECH model row.
 *
 * Everything here is rendered from the catalog: a control whose capability is missing does not
 * appear, so a model without SSML cannot be configured as if it had it. The single source of truth
 * remains `media_options`, shared with the raw-JSON textarea — unparseable JSON disables the
 * controls instead of being silently overwritten.
 *
 * Voice-level support outranks the model-level flags: choosing a voice that has no `instruction`
 * support strips the instruction from the options rather than sending an argument the service would
 * ignore or reject.
 */
export const SpeechModelFields: React.FC<SpeechModelFieldsProps> = ({ capabilities, options, onChange }) => {
  const [query, setQuery] = useState('');
  const parsed = useMemo(() => parseOptions(options), [options]);
  const fieldId = useId();

  const current = 'value' in parsed ? parsed.value : {};
  const voiceId = typeof current.voice === 'string' ? current.voice : '';
  // No stored voice means the model default, so the picker must show *its* first format rather
  // than a hardcoded mp3 the model may not offer.
  const format =
    typeof current.format === 'string' && current.format
      ? current.format
      : capabilities.formats?.[0] ?? 'mp3';
  const sampleRate = typeof current.sample_rate === 'number' ? current.sample_rate : undefined;
  const instruction = typeof current.instruction === 'string' ? current.instruction : '';
  const ssmlEnabled = current.enable_ssml === true;

  const voices = capabilities.voices ?? [];
  const selectedVoice = voices.find((v) => v.voice === voiceId) ?? null;

  const update = (patch: OptionsObject) => {
    if (!('value' in parsed)) return;
    const next: OptionsObject = { ...parsed.value };
    for (const [key, value] of Object.entries(patch)) {
      if (value === undefined || value === null || value === '') delete next[key];
      else next[key] = value;
    }
    onChange(JSON.stringify(next));
  };

  const selectVoice = (nextVoiceId: string) => {
    const voice = voices.find((v) => v.voice === nextVoiceId) ?? null;
    const patch: OptionsObject = { voice: nextVoiceId };
    // Per-voice facts win: a voice without instruction support must not keep one on the row.
    if (voice && voice.instruction === false) {
      patch.instruction = undefined;
      patch.enable_ssml = false;
    }
    if (voice && voice.ssml === false) patch.enable_ssml = false;
    update(patch);
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

  const filtered = query.trim() ? filterVoices(voices, query.trim()) : voices;

  return (
    <div className="space-y-3 rounded-md border border-border p-4 bg-muted/20">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium text-muted-foreground">{i18n('Speech Settings')}</span>
        {capabilities.docsUrl && (
          <a
            href={capabilities.docsUrl}
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            {i18n('Voice list')} <ExternalLink className="w-3 h-3" />
          </a>
        )}
      </div>

      <CapabilityBadges capabilities={capabilities} selectedVoice={selectedVoice} />

      <div>
        <label htmlFor={`${fieldId}-voice`} className="text-sm font-medium mb-1 block">
          {i18n('Voice')}
          {capabilities.defaultVoice && (voiceId === '' || voiceId === capabilities.defaultVoice) && (
            <span className="ml-2 text-xs px-1.5 py-0.5 rounded bg-green-500/10 text-green-600 dark:text-green-400">
              {i18n('Recommended')}
            </span>
          )}
        </label>
        {voices.length > 12 && (
          <div className="relative mb-1">
            <Search className="w-3.5 h-3.5 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={i18n('Search by name, trait or language')}
              aria-label={i18n('Search voices')}
              className={`${inputClass} pl-7`}
            />
          </div>
        )}
        <select
          id={`${fieldId}-voice`}
          value={voiceId}
          onChange={(e) => selectVoice(e.target.value)}
          className={inputClass}
        >
          {/* Always rendered, even when a voice is chosen: React needs a matching option for the
              empty value, or clearing the row would silently fall back to the first voice. */}
          <option value="">{i18n('Use the model default')}</option>
          {voiceId && !selectedVoice && (
            <option value={voiceId}>{`${i18n('Custom / unrecognised voice')} (${voiceId})`}</option>
          )}
          {/* A search that excludes the current voice would otherwise blank the control: the
              browser needs an option matching `value` to have anything to show. */}
          {query.trim() && selectedVoice && !filtered.some((v) => v.voice === selectedVoice.voice) && (
            <option value={voiceId}>{voiceLabel(selectedVoice)}</option>
          )}
          {query.trim()
            ? filtered.map((voice) => (
                <option key={voice.voice} value={voice.voice}>{voiceLabel(voice)}</option>
              ))
            : voiceBlocks(capabilities, voices).flatMap((block) => renderGroup(block))}
        </select>
        <VoiceDetail voice={selectedVoice} />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor={`${fieldId}-format`} className="text-sm font-medium mb-1 block">{i18n('Format')}</label>
          <select
            id={`${fieldId}-format`}
            value={format}
            onChange={(e) => update({ format: e.target.value })}
            className={inputClass}
          >
            {(capabilities.formats.length > 0 ? capabilities.formats : ['mp3']).map((value) => (
              <option key={value} value={value}>{i18n(FORMAT_HINTS[value] ?? value)}</option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${fieldId}-rate`} className="text-sm font-medium mb-1 block">{i18n('Sample rate (Hz)')}</label>
          <select
            id={`${fieldId}-rate`}
            value={sampleRate ?? ''}
            onChange={(e) => update({ sample_rate: e.target.value ? Number(e.target.value) : undefined })}
            className={inputClass}
          >
            <option value="">{i18n('Model default')}</option>
            {(capabilities.sampleRates ?? []).map((rate) => (
              <option key={rate} value={rate}>{rate}</option>
            ))}
          </select>
        </div>
      </div>

      {capabilities.supportsInstruction && selectedVoice?.instruction !== false && (
        <div>
          <label htmlFor={`${fieldId}-instruction`} className="text-sm font-medium mb-1 block">{i18n('Instruction')}</label>
          <textarea
            id={`${fieldId}-instruction`}
            value={instruction}
            onChange={(e) => update({ instruction: e.target.value })}
            rows={2}
            placeholder={i18n('e.g. Read this in a warm, slightly slower voice.')}
            className="flex w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
          />
          {capabilities.supportsEmotionTags && capabilities.emotionTags.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-1">
              {capabilities.emotionTags.map((tag) => (
                <button
                  key={tag}
                  type="button"
                  title={i18n('Changes the delivery of everything written after it')}
                  onClick={() => update({ instruction: `${instruction}${tag}` })}
                  className="text-xs px-1.5 py-0.5 rounded border border-border hover:bg-muted"
                >
                  {tag}
                </button>
              ))}
            </div>
          )}
          {capabilities.supportsEmotionTags && (capabilities.paralinguisticTags?.length ?? 0) > 0 && (
            <div className="flex flex-wrap gap-1 mt-1">
              {(capabilities.paralinguisticTags ?? []).map((tag) => (
                <button
                  key={tag}
                  type="button"
                  title={i18n('Inserts one sound at this point in the text')}
                  onClick={() => update({ instruction: `${instruction}${tag}` })}
                  className="text-xs px-1.5 py-0.5 rounded border border-dashed border-border hover:bg-muted"
                >
                  {tag}
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {capabilities.supportsSsml && selectedVoice?.ssml !== false && (
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            checked={ssmlEnabled}
            onChange={(e) => update({ enable_ssml: e.target.checked })}
            className="mt-0.5"
          />
          <span>
            {i18n('Enable SSML')}
            <span className="block text-xs text-muted-foreground">
              {i18n('The whole text must then be one <speak> document and is sent in a single frame.')}
            </span>
          </span>
        </label>
      )}

      <p className="text-xs text-muted-foreground">
        {i18n('Chat read-aloud always speaks plain text as MP3 from this model\'s default voice; these settings exist for the speech generation tool.')}
      </p>
    </div>
  );
};

/** One block per declared voice group, plus a catch-all for voices no declared group covers. */
interface VoiceBlock {
  key: string;
  label: string;
  voices: VoiceSpec[];
}

/**
 * Build the picker's blocks.
 *
 * A catalog entry that lists voices but no `voiceGroups` — or whose groups are incomplete — would
 * otherwise render a `<select>` with no options while the row holds 139 voices. Anything the
 * declared groups do not cover therefore lands in a trailing "other voices" block.
 */
function voiceBlocks(capabilities: SpeechCapabilities, voices: VoiceSpec[]): VoiceBlock[] {
  const covered = new Set<string>();
  const blocks: VoiceBlock[] = [];
  for (const group of capabilities.voiceGroups ?? []) {
    const members = voices.filter((voice) => voice.group === group.key);
    for (const voice of members) covered.add(voice.voice);
    blocks.push({ key: group.key, label: `${group.label} (${group.count})`, voices: members });
  }
  const rest = voices.filter((voice) => !covered.has(voice.voice));
  if (rest.length > 0) blocks.push({ key: '__other', label: i18n('Other voices'), voices: rest });
  return blocks.filter((block) => block.voices.length > 0);
}

/** A block becomes one `optgroup` per gender, keeping the vendor's publication order. */
function renderGroup(block: VoiceBlock) {
  const byGender = new Map<string, VoiceSpec[]>();
  for (const voice of block.voices) {
    const key = voice.gender ?? 'other';
    byGender.set(key, [...(byGender.get(key) ?? []), voice]);
  }
  // `<optgroup>` cannot nest, so block and gender are folded into one label per group.
  return [...byGender.entries()].map(([gender, list]) => (
    <optgroup
      key={`${block.key}:${gender}`}
      label={`${block.label} · ${i18n(GENDER_LABELS[gender] ?? 'Unspecified')}`}
    >
      {list.map((voice) => (
        <option key={voice.voice} value={voice.voice}>
          {voiceLabel(voice)}
        </option>
      ))}
    </optgroup>
  ));
}

function filterVoices(voices: VoiceSpec[], query: string): VoiceSpec[] {
  const needle = query.toLowerCase();
  return voices.filter((voice) =>
    [voice.name, voice.voice, voice.localName, voice.languageLabel, ...(voice.traits ?? []), ...(voice.scenarios ?? [])]
      .filter((part): part is string => !!part)
      .some((part) => part.toLowerCase().includes(needle))
  );
}

const VoiceDetail: React.FC<{ voice: VoiceSpec | null }> = ({ voice }) => {
  if (!voice) return null;
  const bits: string[] = [];
  if (voice.traits?.length) bits.push(voice.traits.join(' / '));
  if (voice.accent) bits.push(voice.accent);
  if (voice.scenarios?.length) bits.push(voice.scenarios.join(' / '));
  if (voice.dialects?.length) bits.push(`${i18n('Dialects')}: ${voice.dialects.join(' / ')}`);
  if (voice.languageInferred) bits.push(i18n('Language not stated by the vendor'));
  return bits.length === 0 ? null : (
    <p className="text-xs text-muted-foreground mt-1">{bits.join(' · ')}</p>
  );
};

const CapabilityBadges: React.FC<{ capabilities: SpeechCapabilities; selectedVoice: VoiceSpec | null }> = ({
  capabilities,
  selectedVoice,
}) => {
  const badges: Array<{ label: string; on: boolean }> = [
    { label: 'SSML', on: capabilities.supportsSsml && selectedVoice?.ssml !== false },
    { label: i18n('Emotion tags'), on: capabilities.supportsEmotionTags },
    { label: i18n('Instruction'), on: capabilities.supportsInstruction && selectedVoice?.instruction !== false },
    { label: 'LaTeX', on: capabilities.supportsLatex },
    { label: i18n('Timestamps'), on: capabilities.supportsWordTimestamp && selectedVoice?.timestamp !== false },
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
