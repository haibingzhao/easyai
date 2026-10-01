import { useState, useEffect, useCallback } from 'react';
import { i18n } from '../../utils/i18n';
import { Box, Plus, Pencil, Trash2, Star, FolderOpen, FolderX } from 'lucide-react';
import { modelConfigService } from '@/services/model-config-service';
import { readErrorMessage } from '@/services/api-client';
import { InlineGenerationModelForm } from '@/components/models/InlineGenerationModelForm';
import { GroupEditDialog } from '@/components/models/GroupEditDialog';
import type {
  ModelConfigGroup,
  ModelProviderConfig,
  ModelType,
  SaveModelConfigGroupRequest,
  SaveModelProviderConfigRequest,
} from '@/types/settings';

interface GenerationModelsSectionProps {
  /** Non-chat model population managed by this section. */
  modelType: Exclude<ModelType, 'CHAT'>;
}

/** Which inline form is open: create a group + first model, add into a group, or edit one row. */
type OpenForm =
  | { kind: 'create' }
  | { kind: 'group'; groupId: string }
  | { kind: 'edit'; config: ModelProviderConfig };

/**
 * Models-page section managing one generation model type (IMAGE / VIDEO / SPEECH / MUSIC / ASR).
 *
 * Mirrors the Text page: rows live in `model_provider_config` beside chat models partitioned by
 * modelType, are grouped under shared Connection Settings, and are added through page-embedded forms
 * rather than modals. Joining an existing group is the credential "reference" — a DashScope group can
 * hold text, image and video rows at once. The tool layer resolves a row per call, so edits apply hot.
 */
export const GenerationModelsSection: React.FC<GenerationModelsSectionProps> = ({ modelType }) => {
  const [configs, setConfigs] = useState<ModelProviderConfig[]>([]);
  const [groups, setGroups] = useState<ModelConfigGroup[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openForm, setOpenForm] = useState<OpenForm | null>(null);
  const [editingGroup, setEditingGroup] = useState<ModelConfigGroup | null>(null);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      // Untyped group list: every group is a candidate host for a generation row, even one that
      // currently only holds chat models.
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
    setOpenForm(null);
  }, [loadData]);

  /** Rebuilds a full save request from a stored row — the write path replaces the whole row. */
  const requestFromConfig = (
    config: ModelProviderConfig,
    patch: Partial<SaveModelProviderConfigRequest> = {}
  ): SaveModelProviderConfigRequest => ({
    id: config.id,
    name: config.name,
    protocol: config.protocol,
    isCustom: config.isCustom,
    baseUrl: config.baseUrl,
    // Masked on read — omit it and the store keeps the row's key (or its group's).
    modelId: config.modelId,
    modelName: config.modelName ?? config.modelId,
    isCustomModel: true,
    enabled: config.enabled,
    timeoutSeconds: config.timeoutSeconds ?? 600,
    groupId: config.groupId,
    modelType,
    mediaOptions: config.mediaOptions,
    isDefault: config.isDefault ?? false,
    ...patch,
  });

  const handleSaveFromForm = async (request: SaveModelProviderConfigRequest): Promise<ModelProviderConfig> => {
    const saved = await modelConfigService.saveConfiguration(request);
    await loadData();
    return saved;
  };

  const handlePatch = async (config: ModelProviderConfig, patch: Partial<SaveModelProviderConfigRequest>) => {
    try {
      setError(null);
      await modelConfigService.saveConfiguration(requestFromConfig(config, patch));
      await loadData();
    } catch (e) {
      setError(readErrorMessage(e, 'Failed to update'));
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm(i18n('Are you sure to delete this configuration?'))) return;
    try {
      setError(null);
      await modelConfigService.deleteConfiguration(id);
      await loadData();
    } catch (e) {
      setError(readErrorMessage(e, 'Failed to delete'));
    }
  };

  /** Generation rows of the current type belonging to one group. */
  function rowsOf(groupId: string) {
    return configs.filter(c => c.groupId === groupId);
  }

  const handleDeleteGroup = async (group: ModelConfigGroup) => {
    const total = group.models.length + rowsOf(group.id).length;
    const msg = i18n('Delete group "{name}" and its {n} model(s)?')
      .replace('{name}', group.name)
      .replace('{n}', String(total));
    if (!confirm(msg)) return;
    try {
      setError(null);
      await modelConfigService.deleteGroup(group.id);
      await loadData();
    } catch (e) {
      setError(readErrorMessage(e, 'Failed to delete group'));
    }
  };

  const handleSaveGroup = async (request: SaveModelConfigGroupRequest) => {
    try {
      setError(null);
      await modelConfigService.updateGroup(request.id!, request);
      setEditingGroup(null);
      await loadData();
    } catch (e) {
      setError(readErrorMessage(e, 'Failed to update group'));
    }
  };

  const ungrouped = configs.filter(c => !c.groupId);

  const renderRows = (rows: ModelProviderConfig[]) => (
    <>
      <div className="grid grid-cols-[1fr_auto_auto_auto_auto] gap-3 bg-muted/30 px-4 py-1.5 text-xs font-medium text-muted-foreground">
        <div>{i18n('Model')}</div>
        <div>{i18n('Provider')}</div>
        <div>{i18n('Default')}</div>
        <div>{i18n('Enabled')}</div>
        <div className="text-right">{i18n('Actions')}</div>
      </div>
      {rows.map(config => (
        <div key={config.id} className="grid grid-cols-[1fr_auto_auto_auto_auto] gap-3 items-center px-4 py-2.5 hover:bg-muted/30">
          <div className="flex items-center gap-2 min-w-0">
            <Box className="w-4 h-4 text-muted-foreground flex-shrink-0" />
            <span className="font-medium truncate">{config.modelId || config.name}</span>
          </div>
          <div className="text-muted-foreground text-sm">{config.protocol}</div>
          <div className="text-sm">
            {config.isDefault
              ? <Star className="w-4 h-4 text-primary fill-current" />
              : <span className="text-muted-foreground">—</span>}
          </div>
          <button
            onClick={() => handlePatch(config, { enabled: !config.enabled })}
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
                onClick={() => handlePatch(config, { isDefault: true })}
                className="p-1 text-muted-foreground hover:text-primary transition-colors"
                title={i18n('Set as default')}
              >
                <Star className="w-4 h-4" />
              </button>
            )}
            <button
              onClick={() => setOpenForm({ kind: 'edit', config })}
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
      {rows.length === 0 && (
        <div className="px-4 py-3 text-sm text-muted-foreground">{i18n('No models yet')}</div>
      )}
    </>
  );

  if (loading && configs.length === 0 && groups.length === 0) {
    return <div className="p-4 text-center text-muted">{i18n('Loading...')}</div>;
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="p-3 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-md text-sm">{error}</div>
      )}

      <button
        onClick={() => setOpenForm(prev => prev?.kind === 'create' ? null : { kind: 'create' })}
        className="flex items-center gap-2 px-4 py-2 text-sm rounded-md bg-muted hover:bg-muted/80 transition-colors"
      >
        <Plus className="w-4 h-4" />
        {i18n('Add Model')}
      </button>

      {openForm?.kind === 'create' && (
        <InlineGenerationModelForm
          key="create"
          modelType={modelType}
          groups={groups}
          onSave={handleSaveFromForm}
          onDone={() => setOpenForm(null)}
        />
      )}

      {groups.map(group => {
        const addingHere = openForm?.kind === 'group' && openForm.groupId === group.id;
        const addTarget = addingHere ? group : undefined;
        const editingConfig = openForm?.kind === 'edit' && openForm.config.groupId === group.id
          ? openForm.config
          : undefined;
        return (
          <div key={group.id} className="border border-border rounded-md overflow-hidden">
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
                  onClick={() => setOpenForm(addingHere ? null : { kind: 'group', groupId: group.id })}
                  className="p-1 text-muted-foreground hover:text-green-500 transition-colors"
                  title={i18n('Add Model to Group')}
                >
                  <Plus className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => setEditingGroup(group)}
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

            {(editingConfig || addTarget) && (
              <InlineGenerationModelForm
                key={editingConfig ? `edit-${editingConfig.id}` : `group-${group.id}`}
                modelType={modelType}
                groups={groups}
                targetGroup={addTarget}
                editConfig={editingConfig}
                onSave={handleSaveFromForm}
                onDone={() => setOpenForm(null)}
              />
            )}

            {renderRows(rowsOf(group.id))}
          </div>
        );
      })}

      {(ungrouped.length > 0 || (openForm?.kind === 'edit' && !openForm.config.groupId)) && (
        <div className="border border-border rounded-md overflow-hidden">
          <div className="flex items-center gap-2 px-4 py-2.5 bg-muted/50">
            <Box className="w-4 h-4 text-muted-foreground" />
            <span className="font-medium text-sm">{i18n('Ungrouped')}</span>
          </div>

          {openForm?.kind === 'edit' && !openForm.config.groupId && (
            <InlineGenerationModelForm
              key={`edit-${openForm.config.id}`}
              modelType={modelType}
              groups={groups}
              editConfig={openForm.config}
              onSave={handleSaveFromForm}
              onDone={() => setOpenForm(null)}
            />
          )}

          {renderRows(ungrouped)}
        </div>
      )}

      {editingGroup && (
        <GroupEditDialog
          group={editingGroup}
          onSave={handleSaveGroup}
          onClose={() => setEditingGroup(null)}
        />
      )}
    </div>
  );
};
