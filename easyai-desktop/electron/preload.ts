/**
 * Minimal preload bridge for the splash/error pages and future desktop-aware
 * features in the console UI.
 */
import { contextBridge, ipcRenderer } from 'electron';

export interface BackendStatus {
  phase: 'starting' | 'ready' | 'error';
  message: string;
}

/** Mirror of updater.ts UpdaterStatus (kept inline so the preload bundle does
 *  not pull in electron-updater). */
export type UpdaterStatus =
  | { state: 'idle' }
  | { state: 'checking' }
  | { state: 'update-available'; version: string; releaseNotes: string }
  | { state: 'update-not-available' }
  | { state: 'downloading'; percent: number }
  | { state: 'downloaded'; version: string }
  | { state: 'not-configured' }
  | { state: 'error'; message: string };

contextBridge.exposeInMainWorld('easyaiDesktop', {
  platform: process.platform,
  appVersion: process.env.EASYAI_APP_VERSION ?? '0.1.0',
  onStatus: (callback: (status: BackendStatus) => void): void => {
    ipcRenderer.on('backend-status', (_event, status: BackendStatus) => callback(status));
  },
  restartBackend: (): Promise<void> => ipcRenderer.invoke('backend:restart'),
  openLogs: (): Promise<void> => ipcRenderer.invoke('backend:open-logs'),
  openSystemSettings: (): Promise<void> => ipcRenderer.invoke('app:open-system-settings'),
  // Auto-update (detection + prompt; install gated until the build is signed).
  onUpdaterStatus: (callback: (status: UpdaterStatus) => void): void => {
    ipcRenderer.on('updater-status', (_event, status: UpdaterStatus) => callback(status));
  },
  checkForUpdates: (): Promise<void> => ipcRenderer.invoke('updater:check'),
  downloadAndInstall: (): Promise<void> => ipcRenderer.invoke('updater:download-install'),
  openDownloadPage: (): Promise<void> => ipcRenderer.invoke('updater:open-download-page'),
});
