import { create } from 'zustand';
import type { Settings } from '@/types/settings';
import { storageService } from '@/services/storage-service';
import { setLanguage } from '@/utils/i18n';
import { setTheme } from '@/utils/theme';

interface SettingsState extends Settings {
  activeModelConfigId: string;
  selectedModelConfigId: string;
  loadSettings: () => void;
  updateSettings: (updates: Partial<Settings>) => void;
  saveSettings: () => void;
  setApiKey: (provider: string, key: string) => void;
  setActiveModelConfig: (configId: string) => void;
  setSelectedModelConfig: (configId: string) => void;
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  language: 'en',
  theme: 'system',
  proxyEnabled: false,
  proxyUrl: '',
  apiKey: {},
  activeModelConfigId: '',
  selectedModelConfigId: '',

  loadSettings: () => {
    const settings = storageService.getSettings();
    set(settings);
    setLanguage(settings.language);
    setTheme(settings.theme);
    const selectedModelId = storageService.getSelectedModelConfigId();
    set({ selectedModelConfigId: selectedModelId });
  },

  updateSettings: (updates) => {
    set(updates);
    if (updates.language) {
      setLanguage(updates.language);
    }
    if (updates.theme) {
      setTheme(updates.theme);
    }
  },

  saveSettings: () => {
    const state = get();
    const settings: Settings = {
      language: state.language,
      theme: state.theme,
      proxyEnabled: state.proxyEnabled,
      proxyUrl: state.proxyUrl,
      apiKey: state.apiKey,
    };
    storageService.saveSettings(settings);
  },

  setApiKey: (provider, key) => set((state) => ({
    apiKey: { ...state.apiKey, [provider]: key }
  })),

  setActiveModelConfig: (configId: string) => set({ activeModelConfigId: configId }),

  setSelectedModelConfig: (configId: string) => {
    set({ selectedModelConfigId: configId });
    storageService.saveSelectedModelConfigId(configId);
  },
}));
