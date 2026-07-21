import { useState, useEffect, useCallback, forwardRef, useImperativeHandle } from 'react';
import { i18n } from '../../utils/i18n';
import { modelConfigService } from '@/services/model-config-service';
import { Box, Pencil, Trash2, ChevronRight, ChevronDown, Plus } from 'lucide-react';
import type { ModelProviderInfo, ModelInfo, ModelProviderConfig, Protocol, SaveModelProviderConfigRequest, ModelOptions, ModelCapabilities } from '@/types/settings';

interface ModelProviderSelectorProps {
  activeConfigId?: string;
  onSave?: (config: ModelProviderConfig) => void;
  onSelectConfig?: (configId: string) => void;
}

export interface ModelProviderSelectorRef {
  save: () => Promise<void>;
}

interface EditDialogState {
  open: boolean;
  config?: ModelProviderConfig;
}

interface AddDialogState {
  open: boolean;
}

export const ModelProviderSelector = forwardRef<ModelProviderSelectorRef, ModelProviderSelectorProps>(
  ({ activeConfigId, onSave, onSelectConfig }, ref) => {
    const [availableProviders, setAvailableProviders] = useState<ModelProviderInfo[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [savedConfigs, setSavedConfigs] = useState<ModelProviderConfig[]>([]);
    const [selectedConfigId, setSelectedConfigId] = useState('');

    const [editDialog, setEditDialog] = useState<EditDialogState>({ open: false });
    const [addDialog, setAddDialog] = useState<AddDialogState>({ open: false });

    const [collapsedBuiltIn, setCollapsedBuiltIn] = useState(false);
    const [collapsedCustom, setCollapsedCustom] = useState(false);

    useEffect(() => {
      if (activeConfigId) {
        setSelectedConfigId(activeConfigId);
      }
    }, [activeConfigId]);

    useEffect(() => {
      const loadData = async () => {
        try {
          setLoading(true);
          const [providers, configs] = await Promise.all([
            modelConfigService.getAvailableProviders(),
            modelConfigService.getUserConfigurations(),
          ]);
          setAvailableProviders(providers);
          setSavedConfigs(configs);
        } catch (e) {
          setError(e instanceof Error ? e.message : 'Failed to load data');
        } finally {
          setLoading(false);
        }
      };
      loadData();
    }, []);

    const handleDeleteConfig = async (id: string) => {
      if (!confirm(i18n('Are you sure to delete this configuration?'))) {
        return;
      }
      try {
        await modelConfigService.deleteConfiguration(id);
        const configs = await modelConfigService.getUserConfigurations();
        setSavedConfigs(configs);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to delete configuration');
      }
    };

    const handleToggleEnabled = async (config: ModelProviderConfig) => {
      try {
        const request: SaveModelProviderConfigRequest = {
          id: config.id,
          name: config.name,
          protocol: config.protocol,
          isCustom: config.isCustom,
          baseUrl: config.baseUrl,
          apiKey: config.apiKey,
          modelId: config.modelId,
          modelName: config.modelName,
          isCustomModel: config.isCustomModel,
          enabled: !config.enabled,
          options: config.options,
          capabilities: config.capabilities,
        };
        const updatedConfig = await modelConfigService.saveConfiguration(request);
        const configs = await modelConfigService.getUserConfigurations();
        setSavedConfigs(configs);
        onSave?.(updatedConfig);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to update configuration');
      }
    };

    const handleSaveFromDialog = async (request: SaveModelProviderConfigRequest) => {
      try {
        setLoading(true);
        const savedConfig = await modelConfigService.saveConfiguration(request);
        const configs = await modelConfigService.getUserConfigurations();
        setSavedConfigs(configs);
        onSave?.(savedConfig);
        return savedConfig;
      } catch (e) {
        throw e;
      } finally {
        setLoading(false);
      }
    };

    useImperativeHandle(ref, () => ({
      save: async () => {},
    }));

    const builtInConfigs = savedConfigs.filter(c => !c.isCustom);
    const customConfigs = savedConfigs.filter(c => c.isCustom);

    if (loading && savedConfigs.length === 0) {
      return <div className="p-4 text-center text-muted">{i18n('Loading...')}</div>;
    }

    return (
      <div className="space-y-4">
        {error && (
          <div className="p-3 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-md text-sm">
            {error}
          </div>
        )}

        <button
          onClick={() => setAddDialog({ open: true })}
          className="flex items-center gap-2 px-4 py-2 text-sm rounded-md bg-muted hover:bg-muted/80 transition-colors"
        >
          <Plus className="w-4 h-4" />
          {i18n('Add Model')}
        </button>

        <div className="border border-border rounded-md overflow-hidden">
          {/* Table header */}
          <div className="grid grid-cols-3 bg-muted/50 px-4 py-2 text-sm font-medium">
            <div>{i18n('Model')}</div>
            <div>{i18n('Provider')}</div>
            <div className="text-right">{i18n('Actions')}</div>
          </div>

          {/* Built-in section */}
          {builtInConfigs.length > 0 && (
            <div>
              <button
                onClick={() => setCollapsedBuiltIn(!collapsedBuiltIn)}
                className="flex items-center gap-2 w-full px-4 py-2 text-sm font-medium hover:bg-muted/50 transition-colors"
              >
                {collapsedBuiltIn ? (
                  <ChevronRight className="w-4 h-4" />
                ) : (
                  <ChevronDown className="w-4 h-4" />
                )}
                {i18n('Built-in')}
              </button>
              {!collapsedBuiltIn && (
                <div className="divide-y divide-border">
                  {builtInConfigs.map(config => (
                    <ModelRow
                      key={config.id}
                      config={config}
                      selectedConfigId={selectedConfigId}
                      onSelect={() => {
                        setSelectedConfigId(config.id);
                        onSelectConfig?.(config.id);
                      }}
                      onEdit={() => setEditDialog({ open: true, config })}
                      onDelete={() => handleDeleteConfig(config.id)}
                      onToggleEnabled={() => handleToggleEnabled(config)}
                    />
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Custom section */}
          {customConfigs.length > 0 && (
            <div>
              <button
                onClick={() => setCollapsedCustom(!collapsedCustom)}
                className="flex items-center gap-2 w-full px-4 py-2 text-sm font-medium hover:bg-muted/50 transition-colors"
              >
                {collapsedCustom ? (
                  <ChevronRight className="w-4 h-4" />
                ) : (
                  <ChevronDown className="w-4 h-4" />
                )}
                {i18n('Custom')}
              </button>
              {!collapsedCustom && (
                <div className="divide-y divide-border">
                  {customConfigs.map(config => (
                    <ModelRow
                      key={config.id}
                      config={config}
                      selectedConfigId={selectedConfigId}
                      onSelect={() => {
                        setSelectedConfigId(config.id);
                        onSelectConfig?.(config.id);
                      }}
                      onEdit={() => setEditDialog({ open: true, config })}
                      onDelete={() => handleDeleteConfig(config.id)}
                      onToggleEnabled={() => handleToggleEnabled(config)}
                    />
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        {/* Edit dialog */}
        {editDialog.open && editDialog.config && (
          <EditModelDialog
            config={editDialog.config}
            availableProviders={availableProviders}
            onSave={async (request) => {
              const savedConfig = await handleSaveFromDialog(request);
              setEditDialog({ open: false });
              return savedConfig;
            }}
            onClose={() => setEditDialog({ open: false })}
          />
        )}

        {/* Add dialog */}
        {addDialog.open && (
          <AddModelDialog
            availableProviders={availableProviders}
            onSave={async (request) => {
              const savedConfig = await handleSaveFromDialog(request);
              setAddDialog({ open: false });
              return savedConfig;
            }}
            onClose={() => setAddDialog({ open: false })}
          />
        )}
      </div>
    );
  }
);

interface ModelRowProps {
  config: ModelProviderConfig;
  selectedConfigId: string;
  onSelect: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onToggleEnabled: () => void;
}

const ModelRow: React.FC<ModelRowProps> = ({
  config,
  selectedConfigId,
  onSelect,
  onEdit,
  onDelete,
  onToggleEnabled,
}) => {
  const displayName = config.isCustomModel && config.modelName
    ? config.modelName
    : config.name;
  const providerName = config.isCustom && config.baseUrl
    ? config.name
    : config.protocol;

  return (
    <div
      className={`grid grid-cols-3 items-center px-4 py-2.5 hover:bg-muted/30 transition-colors ${
        selectedConfigId === config.id ? 'bg-muted/50' : ''
      }`}
      onClick={onSelect}
    >
      <div className="flex items-center gap-2 min-w-0">
        <Box className="w-4 h-4 text-muted-foreground flex-shrink-0" />
        <span className="font-medium truncate">{displayName}</span>
      </div>
      <div className="text-muted-foreground truncate">{providerName}</div>
      <div className="flex items-center justify-end gap-2">
        <button
          onClick={(e) => {
            e.stopPropagation();
            onEdit();
          }}
          className="p-1 text-muted-foreground hover:text-foreground transition-colors"
          title={i18n('Edit')}
        >
          <Pencil className="w-4 h-4" />
        </button>
        <button
          onClick={(e) => {
            e.stopPropagation();
            onDelete();
          }}
          className="p-1 text-muted-foreground hover:text-red-500 transition-colors"
          title={i18n('Delete')}
        >
          <Trash2 className="w-4 h-4" />
        </button>
        <button
          onClick={(e) => {
            e.stopPropagation();
            onToggleEnabled();
          }}
          className={`relative w-9 h-5 rounded-full transition-colors ${
            config.enabled ? 'bg-green-500' : 'bg-muted-foreground/30'
          }`}
        >
          <div
            className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${
              config.enabled ? 'translate-x-4' : 'translate-x-0.5'
            }`}
          />
        </button>
      </div>
    </div>
  );
};

interface AddModelDialogProps {
  availableProviders: ModelProviderInfo[];
  onSave: (request: SaveModelProviderConfigRequest) => Promise<ModelProviderConfig>;
  onClose: () => void;
}

const AddModelDialog: React.FC<AddModelDialogProps> = ({ availableProviders, onSave, onClose }) => {
  const [configName, setConfigName] = useState('');
  const [selectedProtocol, setSelectedProtocol] = useState<Protocol | ''>('');
  const [isCustomProvider, setIsCustomProvider] = useState(false);
  const [selectedProviderId, setSelectedProviderId] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [selectedModelId, setSelectedModelId] = useState('');
  const [isCustomModel, setIsCustomModel] = useState(false);
  const [customModelName, setCustomModelName] = useState('');
  const [availableModels, setAvailableModels] = useState<ModelInfo[]>([]);
  const [loading, setLoading] = useState(false);

  const [options, setOptions] = useState<ModelOptions>({});
  const [showOptions, setShowOptions] = useState(false);
  const [capabilities, setCapabilities] = useState<ModelCapabilities>({});

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

  const handleSave = async () => {
    if (!configName.trim()) {
      alert(i18n('Please enter configuration name'));
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
    };

    try {
      setLoading(true);
      await onSave(request);
      onClose();
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-card rounded-lg border border-border shadow-lg w-full max-w-lg max-h-[90vh] overflow-y-auto">
        <div className="px-6 py-4 border-b border-border">
          <h2 className="text-lg font-semibold">{i18n('Add Model')}</h2>
        </div>
        <div className="px-6 py-4 space-y-4">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Configuration Name')}</label>
            <input
              type="text"
              value={configName}
              onChange={(e) => setConfigName(e.target.value)}
              placeholder={i18n('Enter configuration name')}
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

          {isCustomModel && (
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
        </div>
        <div className="px-6 py-4 border-t border-border flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm rounded-md hover:bg-muted transition-colors"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={handleSave}
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

interface EditModelDialogProps {
  config: ModelProviderConfig;
  availableProviders: ModelProviderInfo[];
  onSave: (request: SaveModelProviderConfigRequest) => Promise<ModelProviderConfig>;
  onClose: () => void;
}

const EditModelDialog: React.FC<EditModelDialogProps> = ({ config, availableProviders, onSave, onClose }) => {
  const [configName, setConfigName] = useState(config.name);
  const [selectedProtocol, setSelectedProtocol] = useState<Protocol>(config.protocol);
  const [isCustomProvider, setIsCustomProvider] = useState(config.isCustom);
  const [selectedProviderId, setSelectedProviderId] = useState(config.isCustom ? '' : config.modelId);
  const [apiKey, setApiKey] = useState(config.apiKey || '');
  const [baseUrl, setBaseUrl] = useState(config.baseUrl || '');
  const [selectedModelId, setSelectedModelId] = useState(config.isCustomModel ? '' : config.modelId);
  const [isCustomModel, setIsCustomModel] = useState(config.isCustomModel);
  const [customModelName, setCustomModelName] = useState(config.isCustomModel ? config.modelName || '' : '');
  const [availableModels, setAvailableModels] = useState<ModelInfo[]>([]);
  const [loading, setLoading] = useState(false);

  const [options, setOptions] = useState<ModelOptions>(config.options || {});
  const [showOptions, setShowOptions] = useState(!!config.options && Object.keys(config.options).length > 0);
  const [capabilities, setCapabilities] = useState<ModelCapabilities>(config.capabilities || {});

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

  useEffect(() => {
    if (!config.isCustom && config.modelId) {
      loadModels(config.modelId);
    }
  }, []);

  const handleProviderChange = (providerId: string, isCustom: boolean) => {
    setSelectedProviderId(providerId);
    setIsCustomProvider(isCustom);
    if (!isCustom && providerId) {
      loadModels(providerId);
    }
  };

  const handleSave = async () => {
    if (!configName.trim()) {
      alert(i18n('Please enter configuration name'));
      return;
    }

    const modelId = isCustomModel ? customModelName.trim() : selectedModelId;
    let modelName: string | undefined;
    if (isCustomModel) {
      modelName = customModelName.trim();
    } else if (selectedModelId) {
      const model = availableModels.find(m => m.id === selectedModelId);
      modelName = model?.name;
    }

    const request: SaveModelProviderConfigRequest = {
      id: config.id,
      name: configName.trim(),
      protocol: selectedProtocol,
      isCustom: isCustomProvider,
      baseUrl: isCustomProvider ? baseUrl.trim() : undefined,
      apiKey: apiKey.trim(),
      modelId,
      modelName,
      isCustomModel,
      enabled: config.enabled,
      options: Object.keys(options).length > 0 ? options : undefined,
      capabilities: capabilities.vision ? capabilities : undefined,
    };

    try {
      setLoading(true);
      await onSave(request);
      onClose();
    } catch (e) {
      console.error('Failed to save:', e);
    } finally {
      setLoading(false);
    }
  };

  const filteredProviders = availableProviders.filter(p => p.protocol === selectedProtocol);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-card rounded-lg border border-border shadow-lg w-full max-w-lg max-h-[90vh] overflow-y-auto">
        <div className="px-6 py-4 border-b border-border">
          <h2 className="text-lg font-semibold">{i18n('Edit Configuration')}</h2>
        </div>
        <div className="px-6 py-4 space-y-4">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Configuration Name')}</label>
            <input
              type="text"
              value={configName}
              onChange={(e) => setConfigName(e.target.value)}
              placeholder={i18n('Enter configuration name')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>

          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Protocol')}</label>
            <div className="flex gap-2">
              <button
                onClick={() => setSelectedProtocol('OPENAI')}
                className={`flex-1 px-3 py-2 text-sm rounded-md border transition-colors ${
                  selectedProtocol === 'OPENAI'
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'border-input hover:bg-muted'
                }`}
              >
                OpenAI
              </button>
              <button
                onClick={() => setSelectedProtocol('ANTHROPIC')}
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

          {isCustomModel && (
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
        </div>
        <div className="px-6 py-4 border-t border-border flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm rounded-md hover:bg-muted transition-colors"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={handleSave}
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
