import React, { useState, useEffect } from 'react';
import { useSettingsStore } from '@/services/stores/settings-store';
import { modelConfigService } from '@/services/model-config-service';
import { storageService } from '@/services/storage-service';
import type { ModelProviderConfig, ModelOptions, ModelCapabilities } from '@/types/settings';
import { i18n } from '@/utils/i18n';
import { ChevronDown } from 'lucide-react';

interface ModelSelectorProps {
  onModelChange: (configId: string, options?: ModelOptions, capabilities?: ModelCapabilities) => void;
}

export const ModelSelector: React.FC<ModelSelectorProps> = ({ onModelChange }) => {
  const [enabledConfigs, setEnabledConfigs] = useState<ModelProviderConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [dropdownOpen, setDropdownOpen] = useState(false);

  const selectedModelConfigId = useSettingsStore((state) => state.selectedModelConfigId);
  const setSelectedModelConfig = useSettingsStore((state) => state.setSelectedModelConfig);

  useEffect(() => {
    const loadConfigs = async () => {
      try {
        setLoading(true);
        const configs = await modelConfigService.getUserConfigurations();
        const enabled = configs.filter(c => c.enabled !== false);
        setEnabledConfigs(enabled);

        if (enabled.length > 0) {
          // Read directly from localStorage to avoid race condition:
          // React child effects run before parent effects, so loadSettings()
          // in App.tsx may not have populated the store yet.
          const storedId = storageService.getSelectedModelConfigId();
          const currentId = storedId || enabled[0].id;
          const currentConfig = enabled.find(c => c.id === currentId) || enabled[0];
          if (currentConfig?.id) {
            onModelChange(currentConfig.id, currentConfig.options, currentConfig.capabilities);
          }
        }
      } catch (e) {
        console.error('Failed to load model configs:', e);
      } finally {
        setLoading(false);
      }
    };
    loadConfigs();
  }, []);

  const handleSelect = (config: ModelProviderConfig) => {
    setSelectedModelConfig(config.id);
    onModelChange(config.id, config.options, config.capabilities);
    setDropdownOpen(false);
  };

  const selectedConfig = enabledConfigs.find(c => c.id === selectedModelConfigId);

  if (loading) {
    return (
      <div className="text-xs text-muted-foreground flex items-center gap-1">
        <span className="w-3 h-3 rounded-full bg-muted-foreground/50 animate-pulse"></span>
        {i18n('Loading models...')}
      </div>
    );
  }

  if (enabledConfigs.length === 0) {
    return (
      <div className="text-xs text-muted-foreground">
        {i18n('No models configured. Please add a model first.')}
      </div>
    );
  }

  return (
    <div className="relative">
      <button
        onClick={() => setDropdownOpen(!dropdownOpen)}
        className="flex items-center gap-1 px-2 py-1 text-xs text-muted-foreground hover:text-foreground rounded-md hover:bg-muted transition-colors"
      >
        {selectedConfig ? (selectedConfig.modelName || selectedConfig.modelId) : i18n('Select Model')}
        <ChevronDown className={`w-3 h-3 transition-transform ${dropdownOpen ? 'rotate-180' : ''}`} />
      </button>

      {dropdownOpen && (
        <div className="absolute bottom-full left-0 mb-2 min-w-[200px] bg-popover border border-border rounded-md shadow-lg z-50">
          {enabledConfigs.map(config => (
            <button
              key={config.id}
              onClick={() => handleSelect(config)}
              className={`w-full text-left px-3 py-2 hover:bg-muted transition-colors ${
                selectedModelConfigId === config.id ? 'bg-muted' : ''
              }`}
            >
              <div className="text-sm font-medium">{config.name}</div>
              <div className="text-xs text-muted-foreground mt-0.5">{config.modelName || config.modelId}</div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
};