import { useState, useEffect, useCallback } from 'react';
import { i18n } from '../../utils/i18n';
import { Plus, Pencil, Trash2, Star } from 'lucide-react';
import { modelConfigService } from '@/services/model-config-service';
import type {
  ModelConfigGroup,
  ModelProviderConfig,
  ModelType,
  Protocol,
  SaveModelProviderConfigRequest,
} from '@/types/settings';

/** Generation protocols offered to the UI — mirrors the backend GENERATION_PROTOCOLS whitelist. */
const GENERATION_PROTOCOLS: Protocol[] = ['OPENAI', 'DASHSCOPE', 'KLING'];

interface GenerationModelsSectionProps {
  /** Non-chat model population managed by this section. */
  modelType: Exclude<ModelType, 'CHAT'>;
}

interface Draft {
  id?: string;
  name: string;
  modelId: string;
  protocol: Protocol;
  baseUrl: string;
  apiKey: string;
  groupId: string;
  mediaOptions: string;
  timeoutSeconds: number;
  isDefault: boolean;
  enabled: boolean;
}

const emptyDraft = (): Draft => ({
  name: '',
  modelId: '',
  protocol: 'OPENAI',
  baseUrl: '',
  apiKey: '',
  groupId: '',
  mediaOptions: '',
  timeoutSeconds: 600,
  isDefault: false,
  enabled: true,
});

const draftFrom = (config: ModelProviderConfig): Draft => ({
  id: config.id,
  name: config.name,
  modelId: config.modelId,
  protocol: config.protocol,
  baseUrl: config.baseUrl ?? '',
  apiKey: '',
  groupId: config.groupId ?? '',
  mediaOptions: config.mediaOptions ?? '',
  timeoutSeconds: config.timeoutSeconds ?? 600,
  isDefault: config.isDefault ?? false,
  enabled: config.enabled,
});

/**
 * Models-page section managing one generation model type (IMAGE / VIDEO / SPEECH / MUSIC / ASR).
 *
 * Rows live in `model_provider_config` beside chat models, partitioned by modelType; a row may join
 * an existing group to inherit its protocol/baseUrl/apiKey (the group mechanism is the shared-credential
 * "reference"). The tool layer resolves a row per call, so changes here apply without a restart.
 */
export const GenerationModelsSection: React.FC<GenerationModelsSectionProps> = ({ modelType }) => {
  const [configs, setConfigs] = useState<ModelProviderConfig[]>([]);
  const [groups, setGroups] = useState<ModelConfigGroup[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [testMessage, setTestMessage] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [configList, groupList] = await Promise.all([
        modelConfigService.getUserConfigurations(modelType),
        modelConfigService.getGroups(),
      ]);
      setConfigs(configList);
      setGroups(groupList);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load data');
    } finally {
      setLoading(false);
    }
  }, [modelType]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const buildRequest = (d: Draft): SaveModelProviderConfigRequest => {
    const group = d.groupId ? groups.find(g => g.id === d.groupId) : undefined;
    return {
      id: d.id,
      name: d.name.trim(),
      // Group members inherit the connection; standalone rows are always custom entries.
      protocol: group ? group.protocol : d.protocol,
      isCustom: group ? group.isCustom : true,
      baseUrl: group ? (group.isCustom ? group.baseUrl : undefined) : (d.baseUrl.trim() || undefined),
      // Never echo the masked key back — undefined means "keep stored / group key".
      apiKey: d.apiKey.trim() ? d.apiKey.trim() : undefined,
      modelId: d.modelId.trim(),
      modelName: d.modelId.trim(),
      isCustomModel: true,
      enabled: d.enabled,
      timeoutSeconds: d.timeoutSeconds,
      groupId: d.groupId || undefined,
      modelType,
      mediaOptions: d.mediaOptions.trim() || undefined,
      isDefault: d.isDefault,
    };
  };

  const validate = (d: Draft): string | null => {
    if (!d.name.trim()) return i18n('Please enter configuration name');
    if (!d.modelId.trim()) return i18n('Please enter the generation model id');
    if (!d.groupId && !d.baseUrl.trim()) return i18n('Standalone models need a Base URL (or join a group)');
    if (d.mediaOptions.trim()) {
      try {
        const parsed: unknown = JSON.parse(d.mediaOptions);
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          return i18n('Generation options must be a JSON object');
        }
      } catch {
        return i18n('Generation options must be a JSON object');
      }
    }
    return null;
  };

  const handleSave = async () => {
    if (!draft) return;
    const problem = validate(draft);
    if (problem) {
      setError(problem);
      return;
    }
    try {
      setError(null);
      await modelConfigService.saveConfiguration(buildRequest(draft));
      setDraft(null);
      await loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save');
    }
  };

  const handleTest = async () => {
    if (!draft) return;
    const problem = validate(draft);
    if (problem) {
      setError(problem);
      return;
    }
    try {
      setError(null);
      setTestMessage(null);
      const result = await modelConfigService.testGenerationConfig(buildRequest(draft));
      setTestMessage(result.success ? `OK — ${result.message}` : result.message);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Test failed');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm(i18n('Are you sure to delete this configuration?'))) return;
    try {
      setError(null);
      await modelConfigService.deleteConfiguration(id);
      await loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete');
    }
  };

  const handleUpdate = async (config: ModelProviderConfig, patch: Partial<Draft>) => {
    try {
      setError(null);
      await modelConfigService.saveConfiguration(buildRequest({ ...draftFrom(config), ...patch }));
      await loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update');
    }
  };

  const groupName = (groupId?: string): string | undefined =>
    groupId ? groups.find(g => g.id === groupId)?.name : undefined;

  return (
    <div className="space-y-4">
      {error && (
        <div className="p-3 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-md text-sm">{error}</div>
      )}

      <button
        onClick={() => { setDraft(emptyDraft()); setTestMessage(null); }}
        className="flex items-center gap-2 px-4 py-2 text-sm rounded-md bg-muted hover:bg-muted/80 transition-colors"
      >
        <Plus className="w-4 h-4" />
        {i18n('Add')} {i18n('Model')}
      </button>

      {loading && configs.length === 0 ? (
        <div className="p-4 text-center text-muted">{i18n('Loading...')}</div>
      ) : configs.length === 0 && !draft ? (
        <div className="px-4 py-3 text-sm text-muted-foreground">{i18n('No models yet')}</div>
      ) : (
        <div className="border border-border rounded-md overflow-hidden">
          <div className="grid grid-cols-[1fr_auto_auto_auto_auto] gap-3 bg-muted/30 px-4 py-1.5 text-xs font-medium text-muted-foreground">
            <div>{i18n('Model')}</div>
            <div>{i18n('Provider')}</div>
            <div>{i18n('Group')}</div>
            <div>{i18n('Enabled')}</div>
            <div className="text-right">{i18n('Actions')}</div>
          </div>
          {configs.map(config => (
            <div key={config.id} className="grid grid-cols-[1fr_auto_auto_auto_auto] gap-3 items-center px-4 py-2.5 hover:bg-muted/30">
              <div className="flex items-center gap-2 min-w-0">
                <span className="font-medium truncate">{config.modelId || config.name}</span>
                {config.isDefault && (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-primary/10 text-primary border border-primary/20 shrink-0">
                    {i18n('Default')}
                  </span>
                )}
              </div>
              <div className="text-muted-foreground text-sm">{config.protocol}</div>
              <div className="text-muted-foreground text-sm truncate max-w-40">{groupName(config.groupId) ?? '—'}</div>
              <button
                onClick={() => handleUpdate(config, { enabled: !config.enabled })}
                className={`relative w-9 h-5 rounded-full transition-colors ${
                  config.enabled ? 'bg-green-500' : 'bg-muted-foreground/30'
                }`}
                title={i18n('Enabled')}
              >
                <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${
                  config.enabled ? 'translate-x-4' : 'translate-x-0.5'
                }`} />
              </button>
              <div className="flex items-center justify-end gap-2">
                {!config.isDefault && (
                  <button
                    onClick={() => handleUpdate(config, { isDefault: true })}
                    className="p-1 text-muted-foreground hover:text-primary transition-colors"
                    title={i18n('Set as default')}
                  >
                    <Star className="w-4 h-4" />
                  </button>
                )}
                <button
                  onClick={() => { setDraft(draftFrom(config)); setTestMessage(null); }}
                  className="p-1 text-muted-foreground hover:text-foreground transition-colors"
                  title={i18n('Edit')}
                >
                  <Pencil className="w-4 h-4" />
                </button>
                <button
                  onClick={() => handleDelete(config.id)}
                  className="p-1 text-muted-foreground hover:text-red-500 transition-colors"
                  title={i18n('Delete')}
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {draft && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="bg-card rounded-lg border border-border shadow-lg w-full max-w-lg max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-4 border-b border-border">
              <h2 className="text-lg font-semibold">
                {draft.id ? i18n('Edit Configuration') : `${i18n('Add')} ${i18n('Model')}`}
              </h2>
            </div>
            <div className="px-6 py-4 space-y-4">
              <Field label={i18n('Configuration Name')} required>
                <input
                  type="text"
                  value={draft.name}
                  onChange={e => setDraft({ ...draft, name: e.target.value })}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                />
              </Field>
              <Field label={i18n('Model ID')} required>
                <input
                  type="text"
                  value={draft.modelId}
                  onChange={e => setDraft({ ...draft, modelId: e.target.value })}
                  placeholder="wan2.5-t2i-preview / kling-v1 / whisper-1…"
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                />
              </Field>
              <Field label={i18n('Group')}>
                <select
                  value={draft.groupId}
                  onChange={e => setDraft({ ...draft, groupId: e.target.value })}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                >
                  <option value="">{i18n('Standalone')}</option>
                  {groups.map(g => (
                    <option key={g.id} value={g.id}>{g.name} ({g.protocol})</option>
                  ))}
                </select>
              </Field>
              {draft.groupId ? (
                <p className="text-xs text-muted-foreground">
                  {i18n('Connection settings are managed by the group. Edit the group to change protocol, provider, or API key.')}
                </p>
              ) : (
                <>
                  <Field label={i18n('Protocol')}>
                    <select
                      value={draft.protocol}
                      onChange={e => setDraft({ ...draft, protocol: e.target.value as Protocol })}
                      className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                    >
                      {GENERATION_PROTOCOLS.map(p => <option key={p} value={p}>{p}</option>)}
                    </select>
                  </Field>
                  <Field label={i18n('Base URL')} required>
                    <input
                      type="text"
                      value={draft.baseUrl}
                      onChange={e => setDraft({ ...draft, baseUrl: e.target.value })}
                      placeholder="https://…/v1"
                      className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                    />
                  </Field>
                  <Field label={i18n('API Key')}>
                    <input
                      type="password"
                      value={draft.apiKey}
                      onChange={e => setDraft({ ...draft, apiKey: e.target.value })}
                      placeholder={draft.id ? i18n('Leave blank to keep current key') : i18n('Enter API Key')}
                      className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                    />
                  </Field>
                </>
              )}
              <Field label={i18n('Generation Options (JSON)')}>
                <textarea
                  value={draft.mediaOptions}
                  onChange={e => setDraft({ ...draft, mediaOptions: e.target.value })}
                  placeholder='{"size":"1024x1024","voice":"alloy"}'
                  rows={3}
                  className="flex w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
                />
              </Field>
              <Field label={i18n('Timeout (seconds)')}>
                <input
                  type="number"
                  min={1}
                  max={1800}
                  value={draft.timeoutSeconds}
                  onChange={e => setDraft({ ...draft, timeoutSeconds: Number(e.target.value) || 600 })}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                />
              </Field>
              <div className="flex items-center gap-6">
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={draft.isDefault}
                    onChange={e => setDraft({ ...draft, isDefault: e.target.checked })}
                  />
                  {i18n('Set as default')}
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={draft.enabled}
                    onChange={e => setDraft({ ...draft, enabled: e.target.checked })}
                  />
                  {i18n('Enabled')}
                </label>
              </div>
              {testMessage && (
                <div className={`p-2 rounded-md text-sm ${
                  testMessage.startsWith('OK')
                    ? 'bg-green-50 dark:bg-green-900/20 text-green-600 dark:text-green-400'
                    : 'bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400'
                }`}>{testMessage}</div>
              )}
            </div>
            <div className="px-6 py-4 border-t border-border flex justify-between">
              <button
                onClick={handleTest}
                className="px-4 py-2 text-sm rounded-md border border-border hover:bg-muted transition-colors"
              >
                {i18n('Test')}
              </button>
              <div className="flex gap-2">
                <button
                  onClick={() => setDraft(null)}
                  className="px-4 py-2 text-sm rounded-md hover:bg-muted transition-colors"
                >
                  {i18n('Cancel')}
                </button>
                <button
                  onClick={handleSave}
                  className="px-4 py-2 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90"
                >
                  {i18n('Save')}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const Field: React.FC<{ label: string; required?: boolean; children: React.ReactNode }> = ({ label, required, children }) => (
  <div>
    <label className="text-sm font-medium mb-1 block">
      {label} {required && <span className="text-red-500">*</span>}
    </label>
    {children}
  </div>
);
