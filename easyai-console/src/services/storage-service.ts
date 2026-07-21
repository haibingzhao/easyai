import type { Settings, SessionMetadata } from '../types/settings';
import type { Message } from '../types/message';
import type { Artifact } from '../types/artifact';

const STORAGE_KEYS = {
  SETTINGS: 'easyai_settings',
  SESSIONS: 'easyai_sessions',
  MESSAGES: 'easyai_messages_',
  ARTIFACTS: 'easyai_artifacts_',
  SELECTED_MODEL: 'easyai_selected_model',
};

export class StorageService {
  getSettings(): Settings {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.SETTINGS);
      return data ? JSON.parse(data) : this.getDefaultSettings();
    } catch {
      return this.getDefaultSettings();
    }
  }

  saveSettings(settings: Settings): void {
    localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(settings));
  }

  getSelectedModelConfigId(): string {
    try {
      return localStorage.getItem(STORAGE_KEYS.SELECTED_MODEL) || '';
    } catch {
      return '';
    }
  }

  saveSelectedModelConfigId(configId: string): void {
    localStorage.setItem(STORAGE_KEYS.SELECTED_MODEL, configId);
  }

  getSwarmPresetModel(presetName: string): string {
    try {
      return localStorage.getItem(`easyai_swarm_model_${presetName}`) || '';
    } catch {
      return '';
    }
  }

  saveSwarmPresetModel(presetName: string, configId: string): void {
    localStorage.setItem(`easyai_swarm_model_${presetName}`, configId);
  }

  getSessions(): SessionMetadata[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.SESSIONS);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  saveSessions(sessions: SessionMetadata[]): void {
    localStorage.setItem(STORAGE_KEYS.SESSIONS, JSON.stringify(sessions));
  }

  getSessionMessages(sessionId: string): Message[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.MESSAGES + sessionId);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  saveSessionMessages(sessionId: string, messages: Message[]): void {
    localStorage.setItem(STORAGE_KEYS.MESSAGES + sessionId, JSON.stringify(messages));
  }

  getSessionArtifacts(sessionId: string): Artifact[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.ARTIFACTS + sessionId);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  saveSessionArtifacts(sessionId: string, artifacts: Artifact[]): void {
    localStorage.setItem(STORAGE_KEYS.ARTIFACTS + sessionId, JSON.stringify(artifacts));
  }

  clearSession(sessionId: string): void {
    localStorage.removeItem(STORAGE_KEYS.MESSAGES + sessionId);
    localStorage.removeItem(STORAGE_KEYS.ARTIFACTS + sessionId);
  }

  deleteSession(sessionId: string): void {
    this.clearSession(sessionId);
    const sessions = this.getSessions().filter(s => s.id !== sessionId);
    this.saveSessions(sessions);
  }

  clearAll(): void {
    localStorage.removeItem(STORAGE_KEYS.SETTINGS);
    localStorage.removeItem(STORAGE_KEYS.SESSIONS);
    localStorage.removeItem(STORAGE_KEYS.SELECTED_MODEL);
    Object.keys(localStorage)
      .filter(key => key.startsWith('easyai_'))
      .forEach(key => localStorage.removeItem(key));
  }

  private getDefaultSettings(): Settings {
    return {
      language: navigator.language.startsWith('zh') ? 'zh' : 'en',
      theme: 'system',
      proxyEnabled: false,
      proxyUrl: '',
      apiKey: {},
    };
  }
}

export const storageService = new StorageService();
