import React, { useState, useEffect, useCallback } from 'react';
import { auxModelConfigService } from '@/services/aux-model-config-service';
import { modelConfigService } from '@/services/model-config-service';
import type { AuxModelConfig, AuxModelTaskKey, ModelProviderConfig } from '@/types/settings';
import { i18n } from '@/utils/i18n';
import { Cpu, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';

/** Human label per task; a task the UI does not know yet falls back to its raw key. */
const TASK_LABEL: Record<AuxModelTaskKey, string> = {
  compaction: 'Compaction Model',
  session_title: 'Session Title Model',
};

const SOURCE_BADGE: Record<AuxModelConfig['effectiveSource'], { label: string; tone: string }> = {
  user: { label: 'Custom model', tone: 'text-green-500 bg-green-500/10' },
  default: { label: 'Follow chat model', tone: 'text-muted-foreground bg-muted' },
};

const taskLabel = (key: AuxModelTaskKey): string => TASK_LABEL[key] ?? key;

export const AuxModelsTab: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [configs, setConfigs] = useState<AuxModelConfig[]>([]);
  const [models, setModels] = useState<ModelProviderConfig[]>([]);
  // Draft selection per task, keyed by taskKey; initialized from the server on load.
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [savingTask, setSavingTask] = useState<string | null>(null);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const load = useCallback(async () => {
    try {
      const [auxList, modelList] = await Promise.all([
        auxModelConfigService.list(),
        modelConfigService.getUserConfigurations(),
      ]);
      setConfigs(auxList);
      setModels(modelList);
      setDrafts(Object.fromEntries(auxList.map((c) => [c.taskKey, c.modelConfigId])));
      setLoadError('');
    } catch (e) {
      // 503 when persistence is off, 401/403 otherwise — the message comes from the server
      setLoadError(e instanceof Error ? e.message : i18n('Failed to load task model settings'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleSave = async (taskKey: AuxModelTaskKey) => {
    setSavingTask(taskKey);
    setMessage(null);
    try {
      const modelConfigId = drafts[taskKey] ?? '';
      const updated = await auxModelConfigService.save(taskKey, {
        modelConfigId: modelConfigId.trim() ? modelConfigId.trim() : null,
      });
      setConfigs((prev) => prev.map((c) => (c.taskKey === taskKey ? updated : c)));
      setMessage({ type: 'success', text: i18n('Settings saved successfully') });
    } catch (e) {
      setMessage({ type: 'error', text: e instanceof Error ? e.message : i18n('Failed to save') });
    } finally {
      setSavingTask(null);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-48">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="flex items-start gap-2 p-3 rounded-lg bg-destructive/10 text-destructive text-sm max-w-lg">
        <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
        <span>{loadError}</span>
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-lg">
      <div>
        <div className="flex items-center gap-2 mb-4">
          <Cpu className="w-5 h-5 text-muted-foreground" />
          <h2 className="text-lg font-medium">{i18n('Task Models')}</h2>
        </div>

        <p className="text-sm text-muted-foreground mb-4">
          {i18n('Choose a dedicated model for background tasks such as context compaction. By default each task follows the model selected in chat; picking a model here overrides it for that task only. Settings are stored per account and take effect immediately after saving.')}
        </p>

        <div className="space-y-5">
          {configs.map((config) => {
            const badge = SOURCE_BADGE[config.effectiveSource];
            const draft = drafts[config.taskKey] ?? '';
            const dirty = draft !== config.modelConfigId;
            return (
              <div key={config.taskKey} className="p-4 rounded-lg border border-border space-y-3">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{i18n(taskLabel(config.taskKey))}</span>
                  <span className={`flex items-center gap-1 text-xs px-2 py-0.5 rounded ${badge.tone}`}>
                    {i18n(badge.label)}
                  </span>
                </div>

                <select
                  value={draft}
                  onChange={(e) => setDrafts((prev) => ({ ...prev, [config.taskKey]: e.target.value }))}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                >
                  <option value="">{i18n('Follow chat model (default)')}</option>
                  {models.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.name || m.modelId}
                    </option>
                  ))}
                </select>

                <div className="flex justify-end">
                  <button
                    onClick={() => handleSave(config.taskKey)}
                    disabled={!dirty || savingTask === config.taskKey}
                    className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 transition-colors"
                  >
                    {savingTask === config.taskKey ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
                    {i18n('Save')}
                  </button>
                </div>
              </div>
            );
          })}

          {message && (
            <div className={`flex items-center gap-2 p-3 rounded-md text-sm ${
              message.type === 'success'
                ? 'bg-green-500/10 text-green-500'
                : 'bg-destructive/10 text-destructive'
            }`}>
              {message.type === 'success' ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
              {message.text}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
