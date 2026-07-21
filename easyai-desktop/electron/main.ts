/**
 * EasyAI desktop shell.
 *
 * Lifecycle: single-instance lock -> splash window -> spawn embedded JVM
 * backend on a free port -> poll /api/chat/health -> load the console UI
 * served same-origin by the backend.
 *
 * Dev escape hatch: EASYAI_BACKEND_URL skips spawning and loads an
 * externally running backend (e.g. `mvn spring-boot:run` from the IDE).
 */
import { app, BrowserWindow, ipcMain, Menu, shell } from 'electron';
import path from 'path';
import {
  BackendHandle,
  backendUrl,
  findFreePort,
  startBackend,
  stopBackend,
  waitForHealth,
} from './backend';
import { loadConfig, resolveDatabase, resolveWorkDir } from './config';
import { logDir } from './paths';
import type { BackendStatus } from './preload';
import {
  checkForUpdates,
  downloadUpdate,
  initUpdater,
  isAutoCheckEnabled,
  onUpdaterStatus,
  openDownloadPage,
} from './updater';

const HEALTH_TIMEOUT_MS = 120_000;

let mainWindow: BrowserWindow | null = null;
let backendHandle: BackendHandle | null = null;
let shuttingDown = false;
let launching = false;
let lastStatus: BackendStatus = { phase: 'starting', message: 'Preparing workspace…' };
let consoleUrl: string | null = null;

function pagePath(name: string): string {
  return path.join(__dirname, '..', 'electron', name);
}

function sendStatus(status: BackendStatus): void {
  lastStatus = status;
  mainWindow?.webContents.send('backend-status', status);
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    title: 'EasyAI',
    show: false,
    backgroundColor: '#0b0f17',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  // Re-send the latest status once a splash/error page finished loading,
  // so early status updates are never lost.
  mainWindow.webContents.on('did-finish-load', () => {
    mainWindow?.webContents.send('backend-status', lastStatus);
  });

  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  void mainWindow.loadFile(pagePath('loading.html'));
}

async function showError(message: string): Promise<void> {
  sendStatus({ phase: 'error', message });
  if (mainWindow) {
    await mainWindow.loadFile(pagePath('error.html'));
  }
}

/**
 * Application menu. Adds a "Check for Updates…" entry (app menu on macOS,
 * Help menu elsewhere) while keeping the standard edit/view roles so the
 * console's text inputs retain copy/paste/zoom behavior.
 */
function buildMenu(): void {
  const isMac = process.platform === 'darwin';
  const checkForUpdatesItem: Electron.MenuItemConstructorOptions = {
    label: 'Check for Updates…',
    click: () => void checkForUpdates(true),
  };

  const template: Electron.MenuItemConstructorOptions[] = [
    ...(isMac
      ? [
          {
            label: app.name,
            submenu: [
              { role: 'about' },
              checkForUpdatesItem,
              { type: 'separator' },
              { role: 'services' },
              { type: 'separator' },
              { role: 'hide' },
              { role: 'hideOthers' },
              { role: 'unhide' },
              { type: 'separator' },
              { role: 'quit' },
            ],
          } as Electron.MenuItemConstructorOptions,
        ]
      : []),
    { role: 'fileMenu' },
    { role: 'editMenu' },
    { role: 'viewMenu' },
    { role: 'windowMenu' },
    { role: 'help', submenu: [checkForUpdatesItem] },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

async function launchBackend(): Promise<void> {
  if (launching) {
    return;
  }
  launching = true;
  try {
    // Dev mode: attach to an externally running backend instead of spawning.
    const externalUrl = process.env.EASYAI_BACKEND_URL;
    if (externalUrl && externalUrl.length > 0) {
      consoleUrl = externalUrl;
      sendStatus({ phase: 'ready', message: `Using external backend at ${externalUrl}` });
      await mainWindow?.loadURL(externalUrl);
      return;
    }

    sendStatus({ phase: 'starting', message: 'Choosing a free port…' });
    const port = await findFreePort();

    const config = loadConfig();
    const db = resolveDatabase(config);
    const workDir = resolveWorkDir(config);

    sendStatus({ phase: 'starting', message: 'Starting embedded backend…' });
    backendHandle = startBackend({
      port,
      dbUrl: db.url,
      dbUsername: db.username,
      dbPassword: db.password,
      workDir,
      jvmArgs: config.jvmArgs,
    });

    backendHandle.process.once('exit', (code, signal) => {
      if (shuttingDown) {
        return;
      }
      backendHandle = null;
      void showError(
        `Backend process exited unexpectedly (code=${code ?? 'null'}, signal=${signal ?? 'null'}). ` +
        'Check the logs and retry.'
      );
    });

    sendStatus({ phase: 'starting', message: 'Waiting for backend to become healthy…' });
    await waitForHealth(backendHandle, HEALTH_TIMEOUT_MS, (elapsedMs) => {
      if (Math.round(elapsedMs / 1000) % 5 === 0) {
        sendStatus({
          phase: 'starting',
          message: `Waiting for backend to become healthy… (${Math.round(elapsedMs / 1000)}s)`,
        });
      }
    });

    consoleUrl = backendUrl(port);
    sendStatus({ phase: 'ready', message: 'Loading console…' });
    await mainWindow?.loadURL(consoleUrl);
    if (!app.isPackaged) {
      mainWindow?.webContents.openDevTools({ mode: 'detach' });
    }

    // Silent startup update check (only when explicitly enabled & configured).
    if (isAutoCheckEnabled()) {
      void checkForUpdates(false);
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await showError(message);
  } finally {
    launching = false;
  }
}

async function restartBackend(): Promise<void> {
  if (backendHandle) {
    shuttingDown = true;
    await stopBackend(backendHandle);
    backendHandle = null;
    shuttingDown = false;
  }
  if (!mainWindow) {
    createWindow();
  } else {
    await mainWindow.loadFile(pagePath('loading.html'));
  }
  await launchBackend();
}

// ---- App lifecycle ----

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      }
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    ipcMain.handle('backend:restart', async () => {
      await restartBackend();
    });
    ipcMain.handle('backend:open-logs', async () => {
      const file = backendHandle?.logFile ?? path.join(logDir(), 'backend-stdout.log');
      shell.showItemInFolder(file);
    });
    ipcMain.handle('app:open-system-settings', async () => {
      if (process.platform === 'darwin') {
        // Open macOS Privacy & Security → Full Disk Access pane
        await shell.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles');
      }
    });

    // Auto-update: detection + prompt (install gated until the build is signed).
    initUpdater(() => mainWindow);
    onUpdaterStatus((status) => {
      mainWindow?.webContents.send('updater-status', status);
    });
    ipcMain.handle('updater:check', async () => {
      await checkForUpdates(true);
    });
    ipcMain.handle('updater:download-install', async () => {
      await downloadUpdate();
    });
    ipcMain.handle('updater:open-download-page', async () => {
      await openDownloadPage();
    });

    buildMenu();
    createWindow();
    void launchBackend();

    app.on('activate', () => {
      // macOS: re-create the window; the backend keeps running.
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
        if (consoleUrl) {
          void mainWindow?.loadURL(consoleUrl);
        } else {
          void launchBackend();
        }
      }
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      app.quit();
    }
  });

  app.on('will-quit', (event) => {
    if (backendHandle && !shuttingDown) {
      shuttingDown = true;
      event.preventDefault();
      const handle = backendHandle;
      backendHandle = null;
      void stopBackend(handle).finally(() => app.quit());
    }
  });
}
