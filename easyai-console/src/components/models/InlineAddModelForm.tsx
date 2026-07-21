import { useState, useCallback, useRef } from 'react';
import { i18n } from '../../utils/i18n';
import { modelConfigService } from '@/services/model-config-service';
import { ChevronRight, ChevronDown } from 'lucide-react';
import type { ModelProviderInfo, ModelInfo, ModelProviderConfig, Protocol, SaveModelProviderConfigRequest, ModelOptions, ModelCapabilities } from '@/types/settings';

interface InlineAddModelFormProps {
  availableProviders: ModelProviderInfo[];
  onSave: (request: SaveModelProviderConfigRequest) => Promise<ModelProviderConfig>;
  onDone: () => void;
}

export const InlineAddModelForm: React.FC<InlineAddModelFormProps> = ({ availableProviders, onSave, onDone }) => {
  // ─── Connection Settings (shared/group fields) ─────────────────────────────
  const [groupName, setGroupName] = useState('');
  const [selectedProtocol, setSelectedProtocol] = useState<Protocol | ''>('');
  const [isCustomProvider, setIsCustomProvider] = useState(false);
  const [selectedProviderId, setSelectedProviderId] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('');

  // ─── Model Settings (per-model fields) ─────────────────────────────────────
  const [configName, setConfigName] = useState('');
  const [selectedModelId, setSelectedModelId] = useState('');
  const [isCustomModel, setIsCustomModel] = useState(false);
  const [customModelName, setCustomModelName] = useState('');
  const [availableModels, setAvailableModels] = useState<ModelInfo[]>([]);
  const [options, setOptions] = useState<ModelOptions>({});
  const [showOptions, setShowOptions] = useState(false);
  const [capabilities, setCapabilities] = useState<ModelCapabilities>({});

  // ─── State ─────────────────────────────────────────────────────────────────
  const [loading, setLoading] = useState(false);
  const [addedCount, setAddedCount] = useState(0);
  const [groupId, setGroupId] = useState<string | undefined>(undefined);
  const modelNameRef = useRef<HTMLInputElement>(null);

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

  const handleProviderChange = (providerId: string, isCustom: boolean) => {
    setSelectedProviderId(providerId);
    setIsCustomProvider(isCustom);
    setSelectedModelId('');
    setIsCustomModel(isCustom);
    setCustomModelName('');
    if (!isCustom && providerId) {
      loadModels(providerId);
    }
  };

  const handleProtocolChange = (protocol: Protocol) => {
    setSelectedProtocol(protocol);
    setSelectedProviderId('');
    setIsCustomProvider(false);
    setAvailableModels([]);
    setSelectedModelId('');
    setIsCustomModel(false);
    setCustomModelName('');
  };

  const resetModelFields = () => {
    setConfigName('');
    setSelectedModelId('');
    setIsCustomModel(false);
    setCustomModelName('');
    setOptions({});
    setCapabilities({});
    setShowOptions(false);
  };

  const handleSave = async (addAnother: boolean) => {
    if (!groupName.trim()) {
      alert(i18n('Please enter group name'));
      return;
    }
    if (!selectedProtocol) {
      alert(i18n('Please select protocol'));
      return;
    }
    if (!apiKey.trim()) {
      alert(i18n('Please enter API Key'));
      return;
    }
    if (isCustomProvider && !baseUrl.trim()) {
      alert(i18n('Please enter Base URL'));
      return;
    }
    if (!isCustomProvider && !selectedProviderId) {
      alert(i18n('Please select provider'));
      return;
    }
    if (!configName.trim()) {
      alert(i18n('Please enter configuration name'));
      return;
    }
    if (isCustomModel && !customModelName.trim()) {
      alert(i18n('Please enter Model Name'));
      return;
    }

    let modelId = selectedModelId;
    let modelName: string | undefined;
    if (isCustomModel) {
      modelId = customModelName.trim();
      modelName = customModelName.trim();
    } else if (selectedModelId) {
      const model = availableModels.find(m => m.id === selectedModelId);
      modelName = model?.name;
    }

    try {
      setLoading(true);

      // Ensure group exists
      let currentGroupId = groupId;
      if (!currentGroupId) {
        const group = await modelConfigService.saveGroup({
          name: groupName.trim(),
          protocol: selectedProtocol,
          isCustom: isCustomProvider,
          baseUrl: isCustomProvider ? baseUrl.trim() : undefined,
          apiKey: apiKey.trim(),
        });
        currentGroupId = group.id;
        setGroupId(currentGroupId);
      }

      const request: SaveModelProviderConfigRequest = {
        name: configName.trim(),
        protocol: selectedProtocol,
        isCustom: isCustomProvider,
        baseUrl: isCustomProvider ? baseUrl.trim() : undefined,
        apiKey: apiKey.trim(),
        modelId,
        modelName,
        isCustomModel,
        enabled: true,
        options: Object.keys(options).length > 0 ? options : undefined,
        capabilities: capabilities.vision ? capabilities : undefined,
        groupId: currentGroupId,
      };

      await onSave(request);

      if (addAnother) {
        setAddedCount(prev => prev + 1);
        resetModelFields();
        modelNameRef.current?.focus();
      } else {
        onDone();
      }
    } catch (e) {
      console.error('Failed to save:', e);
    } finally {
      setLoading(false);
    }
  };

  const filteredProviders = selectedProtocol
    ? availableProviders.filter(p => p.protocol === selectedProtocol)
    : [];

  return (
    <div className="border border-border rounded-lg overflow-hidden">
      {/* Header */}
      <div className="px-6 py-3 border-b border-border bg-muted/30 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{i18n('Add Model')}</h3>
        {addedCount > 0 && (
          <span className="text-xs text-green-600 dark:text-green-400">
            {i18n('Added {n} model(s) to {group}').replace('{n}', String(addedCount)).replace('{group}', groupName)}
          </span>
        )}
      </div>

      <div className="px-6 py-4 space-y-4 max-h-[60vh] overflow-y-auto">
        {/* ─── Connection Settings (Group) ─────────────────────────────────── */}
        <fieldset className="rounded-md border border-border p-4 bg-muted/20 space-y-3">
          <legend className="text-xs font-medium text-muted-foreground px-1">{i18n('Connection Settings')}</legend>

          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Group Name')}</label>
            <input
              type="text"
              value={groupName}
              onChange={(e) => setGroupName(e.target.value)}
              placeholder={i18n('e.g. My OpenAI Account')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>

          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Protocol')}</label>
            <div className="flex gap-2">
              <button
                onClick={() => handleProtocolChange('OPENAI')}
                className={`flex-1 px-3 py-2 text-sm rounded-md border transition-colors ${
                  selectedProtocol === 'OPENAI'
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'border-input hover:bg-muted'
                }`}
              >
                OpenAI
              </button>
              <button
                onClick={() => handleProtocolChange('ANTHROPIC')}
                className={`flex-1 px-3 py-2 text-sm rounded-md border transition-colors ${
                  selectedProtocol === 'ANTHROPIC'
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'border-input hover:bg-muted'
                }`}
              >
                Anthropic
              </button>
            </div>
          </div>

          {selectedProtocol && (
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Model Provider')}</label>
              <select
                value={isCustomProvider ? 'custom' : selectedProviderId}
                onChange={(e) => {
                  if (e.target.value === 'custom') {
                    handleProviderChange('', true);
                  } else {
                    handleProviderChange(e.target.value, false);
                  }
                }}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              >
                <option value="" disabled hidden>{i18n('Select Provider')}</option>
                {filteredProviders.filter(p => !p.isCustom).map(provider => (
                  <option key={provider.id} value={provider.id}>
                    {provider.name}
                  </option>
                ))}
                <option value="custom">{i18n('Custom Provider')}</option>
              </select>
            </div>
          )}

          {isCustomProvider && (
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Base URL')}</label>
              <input
                type="text"
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                placeholder={i18n('Enter Base URL')}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
          )}

          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('API Key')}</label>
            <input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder={i18n('Enter API Key')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
        </fieldset>

        {/* ─── Model Settings (Per-model) ──────────────────────────────────── */}
        <fieldset className="rounded-md border border-border p-4 space-y-3">
          <legend className="text-xs font-medium text-muted-foreground px-1">{i18n('Model Settings')}</legend>

          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Configuration Name')}</label>
            <input
              ref={modelNameRef}
              type="text"
              value={configName}
              onChange={(e) => setConfigName(e.target.value)}
              placeholder={i18n('Enter configuration name')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>

          {(!isCustomProvider && selectedProviderId) && (
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
                    setCustomModelName('');
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

          {(isCustomModel || isCustomProvider) && (
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Model Name')}</label>
              <input
                type="text"
                value={customModelName}
                onChange={(e) => setCustomModelName(e.target.value)}
                placeholder={i18n('Enter Model Name')}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
          )}

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
                  <input
                    type="number"
                    min="1"
                    value={options.maxTokens ?? ''}
                    onChange={(e) => setOptions(prev => ({
                      ...prev,
                      maxTokens: e.target.value ? parseInt(e.target.value) : undefined,
                    }))}
                    placeholder="4096"
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
              </div>
            )}
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
        </fieldset>
      </div>

      {/* Footer buttons */}
      <div className="px-6 py-4 border-t border-border flex justify-end gap-2">
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
  );
};
