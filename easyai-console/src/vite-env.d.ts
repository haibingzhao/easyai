/// <reference types="vite/client" />

interface EasyaiDesktopBridge {
  platform: string;
  appVersion: string;
  onStatus: (callback: (status: { phase: 'starting' | 'ready' | 'error'; message: string }) => void) => void;
  restartBackend: () => Promise<void>;
  openLogs: () => Promise<void>;
  openSystemSettings?: () => Promise<void>;
}

declare global {
  interface Window {
    easyaiDesktop?: EasyaiDesktopBridge;
  }
}

export {};
