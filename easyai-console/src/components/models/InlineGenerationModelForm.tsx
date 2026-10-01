import { useRef, useState } from 'react';
import { i18n } from '../../utils/i18n';
import { Trash2 } from 'lucide-react';
import { modelConfigService } from '@/services/model-config-service';
import type {
  ModelConfigGroup,
  ModelProviderConfig,
  ModelType,
  Protocol,
  SaveModelProviderConfigRequest,
} from '@/types/settings';

/** Generation protocols offered in the UI — mirrors the backend GENERATION_PROTOCOLS whitelist. */
const GENERATION_PROTOCOLS: Protocol[] = ['OPENAI', 'DASHSCOPE', 'KLING'];

/** Brand casing over the enum name; DASHSCOPE is localized as 百炼 by i18n. */
const PROTOCOL_LABELS: Record<Protocol, string> = {
  OPENAI: 'OpenAI',
  ANTHROPIC: 'Anthropic',
  DASHSCOPE: 'DashScope',
  KLING: 'Kling',
};

interface InlineGenerationModelFormProps {
  modelType: Exclude<ModelType, 'CHAT'>;
  /** All of the caller's groups: used to inherit the connection and to reject duplicate names. */
  groups: ModelConfigGroup[];
  /** Adding into this group — connection settings are inherited, never re-typed. */
  targetGroup?: ModelConfigGroup;
  /** Editing a stored row instead of creating one. */
  editConfig?: ModelProviderConfig;
  onSave: (request: SaveModelProviderConfigRequest) => Promise<ModelProviderConfig>;
  onDone: () => void;
}

/** Reads the `{error: ...}` body the backend validation handler returns. */
const readError = (e: unknown): string => {
  if (!(e instanceof Error)) return 'Save failed';
  try {
    return String(JSON.parse(e.message).error);
  } catch {
    return e.message;
  }
};

const inputClass = 'flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm';

/**
 * Inline (page-embedded) form for one generation model row, mirroring the Text page's
 * `InlineAddModelForm`: a Connection Settings fieldset that creates the group on first save, then a
 * Model Settings fieldset that can be repeated with "Save & Add Another".
 *
 * Three modes: create a new group (page-level "Add Model"), add into an existing group (its `+`
 * entry, connection inherited), or edit a stored row (pencil). Standalone rows edit their own
 * connection without a group name.
 */
export const InlineGenerationModelForm: React.FC<InlineGenerationModelFormProps> = ({
  modelType,
  groups,
  targetGroup,
  editConfig,
  onSave,
  onDone,
}) => {
  const inheritedGroup = targetGroup
    ?? (editConfig?.groupId ? groups.find(g => g.id === editConfig.groupId) : undefined);
  const isEdit = !!editConfig;
  /** Only the page-level "Add Model" flow types a new group name; the others reuse an existing one. */
  const createsGroup = !inheritedGroup && !isEdit;

  // ─── Connection Settings (group or the row's own) ──────────────────────────
  const [groupName, setGroupName] = useState('');
  const [protocol, setProtocol] = useState<Protocol>(
    inheritedGroup?.protocol ?? editConfig?.protocol ?? 'OPENAI'
  );
  const [baseUrl, setBaseUrl] = useState(inheritedGroup?.baseUrl ?? editConfig?.baseUrl ?? '');
  // Never prefilled: the API key arrives masked from the server, and blank means "keep stored key".
  const [apiKey, setApiKey] = useState('');
  const [groupId, setGroupId] = useState<string | undefined>(editConfig?.groupId);

  // ─── Model Settings ───────────────────────────────────────────────────────
  const [configName, setConfigName] = useState(editConfig?.name ?? '');
  const [modelId, setModelId] = useState(editConfig?.modelId ?? '');
  const [mediaOptions, setMediaOptions] = useState(editConfig?.mediaOptions ?? '');
  const [timeoutSeconds, setTimeoutSeconds] = useState(editConfig?.timeoutSeconds ?? 600);
  const [isDefault, setIsDefault] = useState(editConfig?.isDefault ?? false);
  const [enabled, setEnabled] = useState(editConfig?.enabled ?? true);

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [addedCount, setAddedCount] = useState(0);
  const configNameRef = useRef<HTMLInputElement>(null);

  const createdGroup = createsGroup && !!groupId;
  const showConnectionFields = !inheritedGroup;

  const resetModelFields = () => {
    setConfigName('');
    setModelId('');
    setMediaOptions('');
    setIsDefault(false);
    setEnabled(true);
    setTestResult(null);
  };

  const buildRequest = (resolvedGroupId?: string): SaveModelProviderConfigRequest => {
    const connection = inheritedGroup
      ? {
          protocol: inheritedGroup.protocol,
          isCustom: inheritedGroup.isCustom,
          baseUrl: inheritedGroup.isCustom ? inheritedGroup.baseUrl : undefined,
          // Omitted on purpose: the backend resolves the group's real key.
          apiKey: undefined as string | undefined,
        }
      : {
          protocol,
          isCustom: true,
          baseUrl: baseUrl.trim() || undefined,
          apiKey: apiKey.trim() || undefined,
        };
    return {
      id: editConfig?.id,
      name: configName.trim(),
      ...connection,
      modelId: modelId.trim(),
      modelName: modelId.trim(),
      isCustomModel: true,
      enabled,
      timeoutSeconds,
      groupId: inheritedGroup?.id ?? resolvedGroupId,
      modelType,
      mediaOptions: mediaOptions.trim() || undefined,
      isDefault,
    };
  };

  const validate = (): string | null => {
    if (!configName.trim()) return i18n('Please enter configuration name');
    if (!modelId.trim()) return i18n('Please enter the generation model id');
    if (mediaOptions.trim()) {
      try {
        const parsed: unknown = JSON.parse(mediaOptions);
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          return i18n('Generation options must be a JSON object');
        }
      } catch {
        return i18n('Generation options must be a JSON object');
      }
    }
    if (showConnectionFields) {
      if (!baseUrl.trim()) return i18n('Please enter Base URL');
      if (createsGroup) {
        if (!groupName.trim()) return i18n('Please enter group name');
        if (!apiKey.trim()) return i18n('Please enter API Key');
        const clash = groups.find(g => g.name.trim().toLowerCase() === groupName.trim().toLowerCase());
        if (clash) {
          return i18n('Group "{name}" already exists — add this model from that group instead')
            .replace('{name}', clash.name);
        }
      }
    }
    return null;
  };

  const handleSave = async (addAnother: boolean) => {
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    try {
      setError(null);
      setSaving(true);
      let currentGroupId = groupId;
      if (createsGroup && !currentGroupId) {
        const group = await modelConfigService.saveGroup({
          name: groupName.trim(),
          protocol,
          isCustom: true,
          baseUrl: baseUrl.trim(),
          apiKey: apiKey.trim(),
        });
        currentGroupId = group.id;
        setGroupId(currentGroupId);
      }
      await onSave(buildRequest(currentGroupId));
      if (addAnother) {
        setAddedCount(prev => prev + 1);
        resetModelFields();
        configNameRef.current?.focus();
      } else {
        onDone();
      }
    } catch (e) {
      setError(readError(e));
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    try {
      setError(null);
      setTestResult(null);
      const result = await modelConfigService.testGenerationConfig(buildRequest(groupId));
      setTestResult({ ok: result.success, message: result.message });
    } catch (e) {
      setError(readError(e));
    }
  };

  const title = isEdit
    ? i18n('Edit Configuration')
    : targetGroup
      ? i18n('Add Model to Group')
      : i18n('Add Model');

  return (
    <div className="border border-border rounded-lg overflow-hidden">
      <div className="px-6 py-3 border-b border-border bg-muted/30 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{title}</h3>
        {addedCount > 0 && (
          <span className="text-xs text-green-600 dark:text-green-400">
            {createsGroup
              ? i18n('Added {n} model(s) to {group}').replace('{n}', String(addedCount)).replace('{group}', groupName)
              : i18n('Added {n} model(s)').replace('{n}', String(addedCount))}
          </span>
        )}
      </div>

      <div className="px-6 py-4 space-y-4">
        {inheritedGroup && (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <span className="px-1.5 py-0.5 rounded bg-muted border border-border">{inheritedGroup.protocol}</span>
            {inheritedGroup.isCustom && inheritedGroup.baseUrl && (
              <span className="truncate max-w-[200px]">{inheritedGroup.baseUrl}</span>
            )}
            <span>{i18n('Connection settings inherited from group "{name}"').replace('{name}', inheritedGroup.name)}</span>
          </div>
        )}

        {showConnectionFields && (
          <fieldset className="rounded-md border border-border p-4 bg-muted/20 space-y-3">
            <legend className="text-xs font-medium text-muted-foreground px-1">{i18n('Connection Settings')}</legend>

            {createsGroup && (
              <div>
                <label className="text-sm font-medium mb-1 block">
                  {i18n('Group Name')} <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={groupName}
                  onChange={e => setGroupName(e.target.value)}
                  readOnly={createdGroup}
                  placeholder={i18n('e.g. My OpenAI Account')}
                  className={`${inputClass} ${createdGroup ? 'opacity-60' : ''}`}
                />
                {createdGroup && (
                  <p className="text-xs text-muted-foreground mt-1">
                    {i18n('Connection settings are managed by the group. Edit the group to change protocol, provider, or API key.')}
                  </p>
                )}
              </div>
            )}

            <div>
              <label className="text-sm font-medium mb-1 block">
                {i18n('Protocol')} <span className="text-red-500">*</span>
              </label>
              <div className="flex gap-2">
                {GENERATION_PROTOCOLS.map(p => (
                  <button
                    key={p}
                    onClick={() => setProtocol(p)}
                    className={`flex-1 px-3 py-2 text-sm rounded-md border transition-colors ${
                      protocol === p ? 'bg-primary text-primary-foreground border-primary' : 'border-input hover:bg-muted'
                    }`}
                  >
                    {i18n(PROTOCOL_LABELS[p])}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="text-sm font-medium mb-1 block">
                {i18n('Base URL')} <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={baseUrl}
                onChange={e => setBaseUrl(e.target.value)}
                placeholder="https://dashscope.aliyuncs.com/api/v1 …"
                className={inputClass}
              />
            </div>

            <div>
              <label className="text-sm font-medium mb-1 block">
                {i18n('API Key')} {createsGroup && <span className="text-red-500">*</span>}
              </label>
              <input
                type="password"
                value={apiKey}
                onChange={e => setApiKey(e.target.value)}
                placeholder={isEdit ? i18n('Leave blank to keep current key') : i18n('Enter API Key')}
                className={inputClass}
              />
            </div>
          </fieldset>
        )}

        <fieldset className="rounded-md border border-border p-4 space-y-3">
          <legend className="text-xs font-medium text-muted-foreground px-1 flex items-center gap-2">
            {i18n('Model Settings')}
            {!isEdit && (
              <button
                onClick={resetModelFields}
                title={i18n('Clear model settings')}
                className="text-muted-foreground hover:text-red-500 transition-colors"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            )}
          </legend>

          <div>
            <label className="text-sm font-medium mb-1 block">
              {i18n('Configuration Name')} <span className="text-red-500">*</span>
            </label>
            <input
              ref={configNameRef}
              type="text"
              value={configName}
              onChange={e => setConfigName(e.target.value)}
              placeholder={i18n('Enter configuration name')}
              className={inputClass}
            />
          </div>

          <div>
            <label className="text-sm font-medium mb-1 block">
              {i18n('Model ID')} <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={modelId}
              onChange={e => setModelId(e.target.value)}
              placeholder="wan2.5-t2i-preview / kling-v1 / whisper-1…"
              className={inputClass}
            />
          </div>

          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Generation Options (JSON)')}</label>
            <textarea
              value={mediaOptions}
              onChange={e => setMediaOptions(e.target.value)}
              placeholder='{"size":"1024x1024","voice":"alloy"}'
              rows={3}
              className="flex w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
            />
          </div>

          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Timeout (seconds)')}</label>
            <input
              type="number"
              min={1}
              max={1800}
              value={timeoutSeconds}
              onChange={e => setTimeoutSeconds(Number(e.target.value) || 600)}
              className={inputClass}
            />
          </div>

          <div className="flex items-center gap-6">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={isDefault} onChange={e => setIsDefault(e.target.checked)} />
              {i18n('Set as default')}
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} />
              {i18n('Enabled')}
            </label>
          </div>
        </fieldset>

        {error && (
          <div className="p-3 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-md text-sm">{error}</div>
        )}
        {testResult && (
          <div className={`p-2 rounded-md text-sm ${
            testResult.ok
              ? 'bg-green-50 dark:bg-green-900/20 text-green-600 dark:text-green-400'
              : 'bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400'
          }`}>{testResult.message}</div>
        )}
      </div>

      <div className="px-6 py-4 border-t border-border flex items-center justify-between">
        <button
          onClick={handleTest}
          disabled={saving}
          className="px-4 py-2 text-sm rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50"
        >
          {i18n('Test')}
        </button>
        <div className="flex gap-2">
          <button
            onClick={onDone}
            className="px-4 py-2 text-sm rounded-md hover:bg-muted transition-colors"
          >
            {i18n('Cancel')}
          </button>
          {!isEdit && (
            <button
              onClick={() => handleSave(true)}
              disabled={saving}
              className="px-4 py-2 text-sm rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50"
            >
              {i18n('Save & Add Another')}
            </button>
          )}
          <button
            onClick={() => handleSave(false)}
            disabled={saving}
            className="px-4 py-2 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {saving ? i18n('Saving...') : i18n('Save')}
          </button>
        </div>
      </div>
    </div>
  );
};
