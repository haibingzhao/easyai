import React, { useState } from 'react';
import { useSettingsStore } from '@/services/stores/settings-store';
import { ModelProviderSelector } from '@/components/dialogs/ModelProviderSelector';
import { GenerationModelsSection } from '@/components/models/GenerationModelsSection';
import { i18n } from '@/utils/i18n';
import type { ModelProviderConfig, ModelType } from '@/types/settings';

const SECTIONS: Array<{ type: ModelType; label: string }> = [
  { type: 'CHAT', label: 'Text' },
  { type: 'IMAGE', label: 'Image' },
  { type: 'VIDEO', label: 'Video' },
  { type: 'SPEECH', label: 'Speech' },
  { type: 'MUSIC', label: 'Music' },
  { type: 'ASR', label: 'Transcription' },
];

export const ModelsPage: React.FC = () => {
  const settings = useSettingsStore();
  const [activeType, setActiveType] = useState<ModelType>('CHAT');

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
        <h1 className="text-xl font-semibold">{i18n('Models')}</h1>
      </div>

      {/* Type switch */}
      <div className="flex items-center gap-1 px-6 pt-4 shrink-0 flex-wrap">
        {SECTIONS.map(section => (
          <button
            key={section.type}
            onClick={() => setActiveType(section.type)}
            className={`px-3 py-1.5 text-sm rounded-md transition-colors ${
              activeType === section.type
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-muted'
            }`}
          >
            {i18n(section.label)}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-6">
        {activeType === 'CHAT' ? (
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
        ) : (
          <GenerationModelsSection modelType={activeType} />
        )}
      </div>
    </div>
  );
};
