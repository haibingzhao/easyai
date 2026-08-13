import { useState, useCallback, useRef } from 'react';
import { i18n } from '../../utils/i18n';
import { TokenInput } from '../TokenInput';
import { modelConfigService } from '@/services/model-config-service';
import { ChevronRight, ChevronDown } from 'lucide-react';
import type { ModelProviderInfo, ModelInfo, ModelProviderConfig, ModelConfigGroup, SaveModelProviderConfigRequest, ModelOptions, ModelCapabilities } from '@/types/settings';

interface AddModelToGroupFormProps {
  group: ModelConfigGroup;
  availableProviders: ModelProviderInfo[];
  onSave: (request: SaveModelProviderConfigRequest) => Promise<ModelProviderConfig>;
  onDone: () => void;
}

/**
 * Inline form for adding a model to an existing group.
 * Connection settings (protocol, provider, API key, base URL) are inherited from the group.
 */
export const AddModelToGroupForm: React.FC<AddModelToGroupFormProps> = ({ group, availableProviders, onSave, onDone }) => {
  const [selectedProviderId, setSelectedProviderId] = useState('');
  const [availableModels, setAvailableModels] = useState<ModelInfo[]>([]);

  const [configName, setConfigName] = useState('');
  const [selectedModelId, setSelectedModelId] = useState('');
  const [isCustomModel, setIsCustomModel] = useState(group.isCustom);
  const [customModelName, setCustomModelName] = useState('');
  const [options, setOptions] = useState<ModelOptions>({});
  const [showOptions, setShowOptions] = useState(true);
  const [capabilities, setCapabilities] = useState<ModelCapabilities>({});

  const [loading, setLoading] = useState(false);
  const [addedCount, setAddedCount] = useState(0);
  const configNameRef = useRef<HTMLInputElement>(null);

  const loadModels = useCallback(async (providerId: string) => {
    if (!providerId) {
      setAvailableModels([]);
      return;
    }
    try {
      const models = await modelConfigService.getModelsForProvider(providerId);
      setAvailableModels(models);
    } catch (e) {
      console.error('Failed to load models:', e);
    }
  }, []);

  const handleProviderChange = (providerId: string) => {
    setSelectedProviderId(providerId);
    setSelectedModelId('');
    setIsCustomModel(group.isCustom || !providerId);
    setCustomModelName('');
    if (providerId) {
      loadModels(providerId);
    } else {
      setAvailableModels([]);
    }
  };

  const resetModelFields = () => {
    setConfigName('');
    setSelectedModelId('');
    setIsCustomModel(group.isCustom);
    setCustomModelName('');
    setOptions({});
    setCapabilities({});
    setShowOptions(true);
  };

  const filteredProviders = availableProviders.filter(
    p => p.protocol === group.protocol && !p.isCustom
  );

  const handleSave = async (addAnother: boolean) => {
    if (!configName.trim()) {
      alert(i18n('Please enter configuration name'));
      return;
    }
    if (!customModelName.trim()) {
      alert(i18n('Please enter Model Name'));
      return;
    }
    if (options.contextToken != null && options.maxContextTokens != null
      && options.contextToken > options.maxContextTokens) {
      alert(i18n('Context Token must not exceed Max Context Tokens'));
      return;
    }

    const effectiveIsCustomModel = isCustomModel || group.isCustom || !selectedModelId;
    const modelId = effectiveIsCustomModel ? customModelName.trim() : selectedModelId;
    const modelName = customModelName.trim();

    try {
      setLoading(true);

      const request: SaveModelProviderConfigRequest = {
        name: configName.trim(),
        protocol: group.protocol,
        isCustom: group.isCustom,
        baseUrl: group.isCustom ? group.baseUrl : undefined,
        // Do not send group.apiKey (masked) — backend resolves real key from group
        modelId,
        modelName,
        isCustomModel: effectiveIsCustomModel,
        enabled: true,
        options: Object.keys(options).length > 0 ? options : undefined,
        capabilities: capabilities.vision ? capabilities : undefined,
        groupId: group.id,
      };

      await onSave(request);

      if (addAnother) {
        setAddedCount(prev => prev + 1);
        resetModelFields();
        configNameRef.current?.focus();
      } else {
        onDone();
      }
    } catch (e) {
      const msg = e instanceof Error ? (() => { try { return JSON.parse(e.message).error; } catch { return e.message; } })() : 'Save failed';
      alert(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="border-t border-border bg-muted/20">
      <div className="px-6 py-4 space-y-4">
        {/* Inherited connection settings summary */}
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span className="px-1.5 py-0.5 rounded bg-muted border border-border">{group.protocol}</span>
          {group.isCustom && group.baseUrl && (
            <span className="truncate max-w-[200px]">{group.baseUrl}</span>
          )}
          <span>{i18n('Connection settings inherited from group "{name}"').replace('{name}', group.name)}</span>
        </div>

        {/* Provider selection for model list (non-custom groups only) */}
        {!group.isCustom && filteredProviders.length > 0 && (
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Model Provider')}</label>
            <select
              value={selectedProviderId}
              onChange={(e) => handleProviderChange(e.target.value)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="">{i18n('Select Provider')}</option>
              {filteredProviders.map(provider => (
                <option key={provider.id} value={provider.id}>
                  {provider.name}
                </option>
              ))}
            </select>
          </div>
        )}

        {/* Model selection from provider list */}
        {!group.isCustom && selectedProviderId && (
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Select Model')}</label>
            <select
              value={isCustomModel ? 'custom' : selectedModelId}
              onChange={(e) => {
                if (e.target.value === 'custom') {
                  setIsCustomModel(true);
                  setSelectedModelId('');
                } else {
                  setIsCustomModel(false);
                  setSelectedModelId(e.target.value);
                  const model = availableModels.find(m => m.id === e.target.value);
                  setCustomModelName(model?.name || e.target.value);
                }
              }}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="">{i18n('Select Model')}</option>
              {availableModels.filter(m => !m.isCustom).map(model => (
                <option key={model.id} value={model.id}>
                  {model.name}
                </option>
              ))}
              <option value="custom">{i18n('Custom Model')}</option>
            </select>
          </div>
        )}

        <div>
          <label className="text-sm font-medium mb-1 block">
            {i18n('Configuration Name')} <span className="text-red-500">*</span>
          </label>
          <input
            ref={configNameRef}
            type="text"
            value={configName}
            onChange={(e) => setConfigName(e.target.value)}
            placeholder={i18n('Enter configuration name')}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
          />
        </div>

        <div>
          <label className="text-sm font-medium mb-1 block">
            {i18n('Model Name')} <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={customModelName}
            onChange={(e) => setCustomModelName(e.target.value)}
            placeholder={i18n('Enter Model Name')}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
          />
        </div>

        <div className="flex items-center justify-between">
          <label className="text-sm font-medium">{i18n('Vision (Image Input)')}</label>
          <button
            onClick={() => setCapabilities(prev => ({ ...prev, vision: !prev.vision }))}
            className={`relative w-9 h-5 rounded-full transition-colors ${
              capabilities.vision ? 'bg-green-500' : 'bg-muted-foreground/30'
            }`}
          >
            <div
              className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${
                capabilities.vision ? 'translate-x-4' : 'translate-x-0.5'
              }`}
            />
          </button>
        </div>

        <div>
          <button
            onClick={() => setShowOptions(!showOptions)}
            className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            {showOptions ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
            {i18n('Options')}
          </button>
          {showOptions && (
            <div className="mt-2 space-y-3 pl-4 border-l-2 border-border">
              <div>
                <label className="text-xs font-medium mb-1 block">{i18n('Temperature')}</label>
                <input
                  type="number"
                  step="0.1"
                  min="0"
                  max="2"
                  value={options.temperature ?? ''}
                  onChange={(e) => setOptions(prev => ({
                    ...prev,
                    temperature: e.target.value ? parseFloat(e.target.value) : undefined,
                  }))}
                  placeholder="0.7"
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                />
              </div>
              <div>
                <label className="text-xs font-medium mb-1 block">{i18n('Max Tokens')}</label>
                <TokenInput
                  value={options.maxTokens}
                  onChange={(v) => setOptions(prev => ({ ...prev, maxTokens: v }))}
                  placeholder="16"
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                />
              </div>
              <div className="flex items-center justify-between">
                <label className="text-xs font-medium">{i18n('Thinking')}</label>
                <button
                  onClick={() => setOptions(prev => ({ ...prev, thinking: !prev.thinking }))}
                  className={`relative w-9 h-5 rounded-full transition-colors ${
                    options.thinking ? 'bg-green-500' : 'bg-muted-foreground/30'
                  }`}
                >
                  <div
                    className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${
                      options.thinking ? 'translate-x-4' : 'translate-x-0.5'
                    }`}
                  />
                </button>
              </div>
              <div>
                <label className="text-xs font-medium mb-1 block">{i18n('Effort')}</label>
                <select
                  value={options.effort || ''}
                  onChange={(e) => setOptions(prev => ({
                    ...prev,
                    effort: (e.target.value || undefined) as ModelOptions['effort'],
                  }))}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                >
                  <option value="">{i18n('Default')}</option>
                  <option value="low">Low</option>
                  <option value="medium">Medium</option>
                  <option value="high">High</option>
                  <option value="xhigh">XHigh</option>
                  <option value="max">Max</option>
                </select>
              </div>
              <div>
                <label className="text-xs font-medium mb-1 block">{i18n('Max Context Tokens')}</label>
                <TokenInput
                  value={options.maxContextTokens}
                  onChange={(v) => setOptions(prev => ({ ...prev, maxContextTokens: v }))}
                  placeholder="200"
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                />
              </div>
              <div>
                <label className="text-xs font-medium mb-1 block">{i18n('Context Token')}</label>
                <TokenInput
                  value={options.contextToken}
                  onChange={(v) => setOptions(prev => ({ ...prev, contextToken: v }))}
                  placeholder="200"
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                />
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Footer buttons */}
      <div className="px-6 py-3 border-t border-border flex items-center justify-between">
        <span className="text-xs text-muted-foreground">
          {addedCount > 0 && i18n('Added {n} model(s)').replace('{n}', String(addedCount))}
        </span>
        <div className="flex gap-2">
          <button
            onClick={onDone}
            className="px-4 py-2 text-sm rounded-md hover:bg-muted transition-colors"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={() => handleSave(true)}
            disabled={loading}
            className="px-4 py-2 text-sm rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50"
          >
            {i18n('Save & Add Another')}
          </button>
          <button
            onClick={() => handleSave(false)}
            disabled={loading}
            className="px-4 py-2 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {loading ? i18n('Saving...') : i18n('Save')}
          </button>
        </div>
      </div>
    </div>
  );
};
