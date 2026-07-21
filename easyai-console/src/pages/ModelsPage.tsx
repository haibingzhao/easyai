import React from 'react';
import { useSettingsStore } from '@/services/stores/settings-store';
import { ModelProviderSelector } from '@/components/dialogs/ModelProviderSelector';
import { i18n } from '@/utils/i18n';
import type { ModelProviderConfig } from '@/types/settings';

export const ModelsPage: React.FC = () => {
  const settings = useSettingsStore();

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
        <h1 className="text-xl font-semibold">{i18n('Models')}</h1>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-6">
        <ModelProviderSelector
          activeConfigId={settings.activeModelConfigId}
          onSave={(config: ModelProviderConfig) => {
            settings.updateSettings({
              apiKey: { ...settings.apiKey, [config.id]: config.apiKey || '' }
            });
            settings.saveSettings();
          }}
          onSelectConfig={(configId: string) => {
            settings.setActiveModelConfig(configId);
            settings.saveSettings();
          }}
        />
      </div>
    </div>
  );
};
