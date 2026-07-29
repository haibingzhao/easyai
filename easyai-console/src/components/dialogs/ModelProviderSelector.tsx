import { useState, useEffect, useCallback, forwardRef, useImperativeHandle } from 'react';
import { i18n } from '../../utils/i18n';
import { TokenInput } from '../TokenInput';
import { modelConfigService } from '@/services/model-config-service';
import { Box, Pencil, Trash2, ChevronRight, ChevronDown, Plus, FolderOpen, FolderX } from 'lucide-react';
import { InlineAddModelForm } from '@/components/models/InlineAddModelForm';
import { AddModelToGroupForm } from '@/components/models/AddModelToGroupForm';
import type { ModelProviderInfo, ModelInfo, ModelProviderConfig, ModelConfigGroup, SaveModelProviderConfigRequest, SaveModelConfigGroupRequest, ModelOptions, ModelCapabilities } from '@/types/settings';

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

interface EditGroupState {
  open: boolean;
  group?: ModelConfigGroup;
}

export const ModelProviderSelector = forwardRef<ModelProviderSelectorRef, ModelProviderSelectorProps>(
  ({ activeConfigId, onSave, onSelectConfig }, ref) => {
    const [availableProviders, setAvailableProviders] = useState<ModelProviderInfo[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [groups, setGroups] = useState<ModelConfigGroup[]>([]);
    const [ungroupedConfigs, setUngroupedConfigs] = useState<ModelProviderConfig[]>([]);
    const [selectedConfigId, setSelectedConfigId] = useState('');

    const [editDialog, setEditDialog] = useState<EditDialogState>({ open: false });
    const [editGroup, setEditGroup] = useState<EditGroupState>({ open: false });
    const [showAddForm, setShowAddForm] = useState(false);
    const [addingGroupId, setAddingGroupId] = useState<string | null>(null);

    useEffect(() => {
      if (activeConfigId) {
        setSelectedConfigId(activeConfigId);
      }
    }, [activeConfigId]);

    const loadData = useCallback(async () => {
      try {
        setLoading(true);
        const [providers, groupList, configs] = await Promise.all([
          modelConfigService.getAvailableProviders(),
          modelConfigService.getGroups(),
          modelConfigService.getUserConfigurations(),
        ]);
        setAvailableProviders(providers);
        setGroups(groupList);
        // Ungrouped = configs with no groupId
        setUngroupedConfigs(configs.filter(c => !c.groupId));
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to load data');
      } finally {
        setLoading(false);
      }
    }, []);

    useEffect(() => {
      loadData();
    }, [loadData]);

    const handleDeleteConfig = async (id: string) => {
      if (!confirm(i18n('Are you sure to delete this configuration?'))) {
        return;
      }
      try {
        await modelConfigService.deleteConfiguration(id);
        await loadData();
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
          // Do not send masked apiKey back — null means "keep existing"
          modelId: config.modelId,
          modelName: config.modelName,
          isCustomModel: config.isCustomModel,
          enabled: !config.enabled,
          options: config.options,
          capabilities: config.capabilities,
          groupId: config.groupId,
        };
        const updatedConfig = await modelConfigService.saveConfiguration(request);
        await loadData();
        onSave?.(updatedConfig);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to update configuration');
      }
    };

    const handleSaveFromForm = async (request: SaveModelProviderConfigRequest) => {
      const savedConfig = await modelConfigService.saveConfiguration(request);
      await loadData();
      onSave?.(savedConfig);
      return savedConfig;
    };

    const handleDeleteGroup = async (group: ModelConfigGroup) => {
      const msg = i18n('Delete group "{name}" and its {n} model(s)?')
        .replace('{name}', group.name)
        .replace('{n}', String(group.models.length));
      if (!confirm(msg)) {
        return;
      }
      try {
        await modelConfigService.deleteGroup(group.id);
        await loadData();
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to delete group');
      }
    };

    const handleSaveGroup = async (request: SaveModelConfigGroupRequest) => {
      try {
        setLoading(true);
        await modelConfigService.updateGroup(request.id!, request);
        setEditGroup({ open: false });
        await loadData();
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to update group');
      } finally {
        setLoading(false);
      }
    };

    useImperativeHandle(ref, () => ({
      save: async () => {},
    }));

    if (loading && groups.length === 0 && ungroupedConfigs.length === 0) {
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
          onClick={() => setShowAddForm(prev => !prev)}
          className="flex items-center gap-2 px-4 py-2 text-sm rounded-md bg-muted hover:bg-muted/80 transition-colors"
        >
          <Plus className="w-4 h-4" />
          {i18n('Add Model')}
        </button>

        {/* Inline Add Model Form */}
        {showAddForm && (
          <InlineAddModelForm
            availableProviders={availableProviders}
            onSave={handleSaveFromForm}
            onDone={() => setShowAddForm(false)}
          />
        )}

        {/* Grouped models */}
        {groups.map(group => (
          <div key={group.id} className="border border-border rounded-md overflow-hidden">
            {/* Group header */}
            <div className="flex items-center justify-between px-4 py-2.5 bg-muted/50">
              <div className="flex items-center gap-2">
                <FolderOpen className="w-4 h-4 text-muted-foreground" />
                <span className="font-medium text-sm">{group.name}</span>
                <span className="text-xs text-muted-foreground px-1.5 py-0.5 rounded bg-muted border border-border">
                  {group.protocol}
                </span>
              </div>
              <div className="flex items-center gap-1">
                <button
                  onClick={() => setAddingGroupId(prev => prev === group.id ? null : group.id)}
                  className="p-1 text-muted-foreground hover:text-green-500 transition-colors"
                  title={i18n('Add Model to Group')}
                >
                  <Plus className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => setEditGroup({ open: true, group })}
                  className="p-1 text-muted-foreground hover:text-foreground transition-colors"
                  title={i18n('Edit Group')}
                >
                  <Pencil className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => handleDeleteGroup(group)}
                  className="p-1 text-muted-foreground hover:text-red-500 transition-colors"
                  title={i18n('Delete Group')}
                >
                  <FolderX className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>

            {/* Inline add-model-to-group form */}
            {addingGroupId === group.id && (
              <AddModelToGroupForm
                group={group}
                availableProviders={availableProviders}
                onSave={handleSaveFromForm}
                onDone={() => setAddingGroupId(null)}
              />
            )}

            {/* Group models table */}
            <div className="grid grid-cols-3 bg-muted/30 px-4 py-1.5 text-xs font-medium text-muted-foreground">
              <div>{i18n('Model')}</div>
              <div>{i18n('Provider')}</div>
              <div className="text-right">{i18n('Actions')}</div>
            </div>
            {group.models.map(config => (
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
            {group.models.length === 0 && (
              <div className="px-4 py-3 text-sm text-muted-foreground">{i18n('No models yet')}</div>
            )}
          </div>
        ))}

        {/* Ungrouped models (backward compatibility) */}
        {ungroupedConfigs.length > 0 && (
          <div className="border border-border rounded-md overflow-hidden">
            <div className="flex items-center gap-2 px-4 py-2.5 bg-muted/50">
              <Box className="w-4 h-4 text-muted-foreground" />
              <span className="font-medium text-sm">{i18n('Ungrouped')}</span>
            </div>
            <div className="grid grid-cols-3 bg-muted/30 px-4 py-1.5 text-xs font-medium text-muted-foreground">
              <div>{i18n('Model')}</div>
              <div>{i18n('Provider')}</div>
              <div className="text-right">{i18n('Actions')}</div>
            </div>
            {ungroupedConfigs.map(config => (
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

        {/* Edit model dialog */}
        {editDialog.open && editDialog.config && (
          <EditModelDialog
            config={editDialog.config}
            onSave={async (request) => {
              const savedConfig = await handleSaveFromForm(request);
              setEditDialog({ open: false });
              return savedConfig;
            }}
            onClose={() => setEditDialog({ open: false })}
          />
        )}

        {/* Edit group dialog */}
        {editGroup.open && editGroup.group && (
          <EditGroupDialog
            group={editGroup.group}
            onSave={handleSaveGroup}
            onClose={() => setEditGroup({ open: false })}
          />
        )}
      </div>
    );
  }
);

// ─── ModelRow ──────────────────────────────────────────────────────────────────

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

// ─── EditGroupDialog ───────────────────────────────────────────────────────────

interface EditGroupDialogProps {
  group: ModelConfigGroup;
  onSave: (request: SaveModelConfigGroupRequest) => Promise<void>;
  onClose: () => void;
}

const EditGroupDialog: React.FC<EditGroupDialogProps> = ({ group, onSave, onClose }) => {
  const [name, setName] = useState(group.name);
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState(group.baseUrl || '');
  const [loading, setLoading] = useState(false);

  const handleSave = async () => {
    if (!name.trim()) {
      alert(i18n('Please enter group name'));
      return;
    }
    if (group.isCustom && !baseUrl.trim()) {
      alert(i18n('Please enter Base URL'));
      return;
    }
    try {
      setLoading(true);
      await onSave({
        id: group.id,
        name: name.trim(),
        protocol: group.protocol,
        isCustom: group.isCustom,
        baseUrl: baseUrl.trim() || undefined,
        apiKey: apiKey.trim() || undefined,
        timeoutSeconds: group.timeoutSeconds,
      });
    } catch (e) {
      console.error('Failed to save group:', e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-card rounded-lg border border-border shadow-lg w-full max-w-md">
        <div className="px-6 py-4 border-b border-border">
          <h2 className="text-lg font-semibold">{i18n('Edit Group')}</h2>
        </div>
        <div className="px-6 py-4 space-y-4">
          <div>
            <label className="text-sm font-medium mb-1 block">
              {i18n('Group Name')} <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
          {group.isCustom && (
            <div>
              <label className="text-sm font-medium mb-1 block">
                {i18n('Base URL')} <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
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
              placeholder={group.apiKey ? i18n('Leave blank to keep current key') : i18n('Enter API Key')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
          <p className="text-xs text-muted-foreground">
            {i18n('Changes will be applied to all {n} model(s) in this group.').replace('{n}', String(group.models.length))}
          </p>
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

// ─── EditModelDialog ───────────────────────────────────────────────────────────

interface EditModelDialogProps {
  config: ModelProviderConfig;
  onSave: (request: SaveModelProviderConfigRequest) => Promise<ModelProviderConfig>;
  onClose: () => void;
}

const EditModelDialog: React.FC<EditModelDialogProps> = ({ config, onSave, onClose }) => {
  const hasGroup = !!config.groupId;

  const [configName, setConfigName] = useState(config.name);
  const [selectedModelId, setSelectedModelId] = useState(config.isCustomModel ? '' : config.modelId);
  const [isCustomModel, setIsCustomModel] = useState(config.isCustomModel);
  const [customModelName, setCustomModelName] = useState(
    (config.isCustomModel || config.isCustom) ? (config.modelName || config.modelId || '') : ''
  );
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

  const handleSave = async () => {
    if (!configName.trim()) {
      alert(i18n('Please enter configuration name'));
      return;
    }
    if ((isCustomModel || config.isCustom) && !customModelName.trim()) {
      alert(i18n('Please enter Model Name'));
      return;
    }
    if (options.contextToken != null && options.maxContextTokens != null
      && options.contextToken > options.maxContextTokens) {
      alert(i18n('Context Token must not exceed Max Context Tokens'));
      return;
    }

    const useCustomName = isCustomModel || config.isCustom;
    const modelId = useCustomName ? customModelName.trim() : selectedModelId;
    let modelName: string | undefined;
    if (useCustomName) {
      modelName = customModelName.trim();
    } else if (selectedModelId) {
      const model = availableModels.find(m => m.id === selectedModelId);
      modelName = model?.name;
    }

    const request: SaveModelProviderConfigRequest = {
      id: config.id,
      name: configName.trim(),
      protocol: config.protocol,
      isCustom: config.isCustom,
      baseUrl: config.baseUrl,
      // Do not send masked apiKey back — null means "keep existing"
      modelId,
      modelName,
      isCustomModel,
      enabled: config.enabled,
      options: Object.keys(options).length > 0 ? options : undefined,
      capabilities: capabilities.vision ? capabilities : undefined,
      groupId: config.groupId,
    };

    try {
      setLoading(true);
      await onSave(request);
      onClose();
    } catch (e) {
      const msg = e instanceof Error ? (() => { try { return JSON.parse(e.message).error; } catch { return e.message; } })() : 'Save failed';
      alert(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-card rounded-lg border border-border shadow-lg w-full max-w-lg max-h-[90vh] overflow-y-auto">
        <div className="px-6 py-4 border-b border-border">
          <h2 className="text-lg font-semibold">{i18n('Edit Configuration')}</h2>
          {hasGroup && (
            <p className="text-xs text-muted-foreground mt-1">
              {i18n('Connection settings are managed by the group. Edit the group to change protocol, provider, or API key.')}
            </p>
          )}
        </div>
        <div className="px-6 py-4 space-y-4">
          <div>
            <label className="text-sm font-medium mb-1 block">
              {i18n('Configuration Name')} <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={configName}
              onChange={(e) => setConfigName(e.target.value)}
              placeholder={i18n('Enter configuration name')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>

          {(!config.isCustom && config.modelId) && (
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

          {(isCustomModel || config.isCustom) && (
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
          )}

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
