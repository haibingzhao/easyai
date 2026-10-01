import { useState } from 'react';
import { i18n } from '../../utils/i18n';
import type { ModelConfigGroup, SaveModelConfigGroupRequest } from '@/types/settings';

interface GroupEditDialogProps {
  group: ModelConfigGroup;
  onSave: (request: SaveModelConfigGroupRequest) => Promise<void>;
  onClose: () => void;
}

/** Shared connection-settings editor for a model config group (Text and generation pages). */
export const GroupEditDialog: React.FC<GroupEditDialogProps> = ({ group, onSave, onClose }) => {
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
