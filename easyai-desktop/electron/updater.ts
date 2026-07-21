/**
 * Auto-update module (detection + prompt; install gated).
 *
 * Built on electron-updater (bundled with electron-builder). The shell owns
 * the whole flow with native dialogs/notifications so the React console needs
 * zero changes (same-origin, HashRouter design is preserved).
 *
 * v1 scope — per product decision:
 *   - Detect new versions and prompt the user.
 *   - Do NOT auto-install yet: macOS auto-update requires a code-signed &
 *     notarized build (Squirrel.Mac). Until that certificate is in place,
 *     AUTO_INSTALL_ENABLED stays false and the prompt routes the user to an
 *     external download page instead of quitAndInstall().
 *   - The update feed is read from desktop-config.json (`update.feedUrl`),
 *     so the hosting location can be decided later without a rebuild.
 *
 * Flip AUTO_INSTALL_ENABLED to true once the macOS build is signed/notarized
 * to enable the full download-and-reinstall flow on every platform.
 */
import { app, BrowserWindow, dialog, Notification, shell } from 'electron';
import { autoUpdater } from 'electron-updater';
import type { ProgressInfo, UpdateInfo } from 'electron-updater';
import { loadConfig } from './config';

/** Gate for the in-app download-and-install flow (see file header). */
const AUTO_INSTALL_ENABLED = false;

export type UpdaterStatus =
  | { state: 'idle' }
  | { state: 'checking' }
  | { state: 'update-available'; version: string; releaseNotes: string }
  | { state: 'update-not-available' }
  | { state: 'downloading'; percent: number }
  | { state: 'downloaded'; version: string }
  | { state: 'not-configured' }
  | { state: 'error'; message: string };

type StatusListener = (status: UpdaterStatus) => void;

const listeners = new Set<StatusListener>();
let lastStatus: UpdaterStatus = { state: 'idle' };
let mainWindowGetter: () => BrowserWindow | null = () => null;
let downloadNotified = false;

function emit(status: UpdaterStatus): void {
  lastStatus = status;
  for (const listener of listeners) {
    listener(status);
  }
}

export function onUpdaterStatus(listener: StatusListener): void {
  listeners.add(listener);
}

export function lastUpdaterStatus(): UpdaterStatus {
  return lastStatus;
}

function normalizeReleaseNotes(info: UpdateInfo): string {
  const notes = info.releaseNotes;
  if (!notes) {
    return '';
  }
  if (typeof notes === 'string') {
    return notes;
  }
  // electron-updater may return an array of { version, note } blocks.
  return notes
    .map((block) => (typeof block === 'string' ? block : block.note ?? ''))
    .filter((text) => text.length > 0)
    .join('\n');
}

function parentWindow(): BrowserWindow | null {
  return mainWindowGetter() ?? BrowserWindow.getFocusedWindow();
}

/** Show a message box parented to the main window when available. */
function messageBox(options: Electron.MessageBoxOptions): Promise<Electron.MessageBoxReturnValue> {
  const win = parentWindow();
  return win ? dialog.showMessageBox(win, options) : dialog.showMessageBox(options);
}

async function promptUpdateAvailable(info: UpdateInfo): Promise<void> {
  const notes = normalizeReleaseNotes(info);
  const detail =
    `Current version: ${app.getVersion()}\n` +
    (notes.length > 0 ? `\nRelease notes:\n${notes.slice(0, 800)}` : '');

  if (AUTO_INSTALL_ENABLED) {
    const { response } = await messageBox({
      type: 'info',
      title: 'Update available',
      message: `EasyAI v${info.version} is available.`,
      detail,
      buttons: ['Download && Install', 'Open Download Page', 'Later'],
      defaultId: 0,
      cancelId: 2,
    });
    if (response === 0) {
      void downloadUpdate();
    } else if (response === 1) {
      void openDownloadPage();
    }
  } else {
    // Install gated: route to the external download page.
    const { response } = await messageBox({
      type: 'info',
      title: 'Update available',
      message: `EasyAI v${info.version} is available.`,
      detail: detail + '\n\nIn-app install is not enabled for this build; download the new version from the website.',
      buttons: ['Open Download Page', 'Later'],
      defaultId: 0,
      cancelId: 1,
    });
    if (response === 0) {
      void openDownloadPage();
    }
  }
}

async function promptRestart(info: UpdateInfo): Promise<void> {
  const { response } = await messageBox({
    type: 'info',
    title: 'Update ready',
    message: `EasyAI v${info.version} has been downloaded.`,
    detail: 'Restart the application to install the update. The embedded backend will be restarted as well.',
    buttons: ['Restart && Install', 'Later'],
    defaultId: 0,
    cancelId: 1,
  });
  if (response === 0) {
    setImmediate(() => autoUpdater.quitAndInstall());
  }
}

/**
 * Wire autoUpdater events. Call once after app.whenReady().
 * @param getWindow returns the main window (used as dialog parent).
 */
export function initUpdater(getWindow: () => BrowserWindow | null): void {
  mainWindowGetter = getWindow;
  autoUpdater.logger = console;
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = false;

  autoUpdater.on('checking-for-update', () => emit({ state: 'checking' }));

  autoUpdater.on('update-available', (info: UpdateInfo) => {
    emit({ state: 'update-available', version: info.version, releaseNotes: normalizeReleaseNotes(info) });
    void promptUpdateAvailable(info);
  });

  autoUpdater.on('update-not-available', () => emit({ state: 'update-not-available' }));

  autoUpdater.on('download-progress', (progress: ProgressInfo) => {
    emit({ state: 'downloading', percent: Math.round(progress.percent) });
  });

  autoUpdater.on('update-downloaded', (info: UpdateInfo) => {
    emit({ state: 'downloaded', version: info.version });
    if (Notification.isSupported() && !downloadNotified) {
      downloadNotified = true;
      new Notification({
        title: 'EasyAI update ready',
        body: `v${info.version} downloaded — restart to install.`,
      }).show();
    }
    void promptRestart(info);
  });

  autoUpdater.on('error', (error: Error) => {
    emit({ state: 'error', message: error.message });
  });
}

/**
 * Check for updates.
 * @param manual true when triggered by the user (shows "up to date" / error
 *               dialogs); false for the silent startup check.
 */
export async function checkForUpdates(manual: boolean): Promise<void> {
  const config = loadConfig();
  const feedUrl = config.update.feedUrl;
  if (!feedUrl) {
    emit({ state: 'not-configured' });
    if (manual) {
      await messageBox({
        type: 'info',
        title: 'Updates',
        message: 'Update source is not configured.',
        detail:
          'Set "update.feedUrl" in desktop-config.json to enable update checks.\n' +
          `Current version: ${app.getVersion()}`,
        buttons: ['OK'],
      });
    }
    return;
  }

  try {
    autoUpdater.setFeedURL({ provider: 'generic', url: feedUrl });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    emit({ state: 'error', message });
    if (manual) {
      await messageBox({
        type: 'error',
        title: 'Update check failed',
        message: 'Could not configure the update source.',
        detail: message,
        buttons: ['OK'],
      });
    }
    return;
  }

  downloadNotified = false;
  try {
    const result = await autoUpdater.checkForUpdates();
    // In dev builds checkForUpdates resolves to null; surface "up to date"
    // for manual checks so the user gets feedback.
    if (manual && result == null) {
      emit({ state: 'update-not-available' });
      await messageBox({
        type: 'info',
        title: 'Updates',
        message: 'You are on the latest version.',
        detail: `Current version: ${app.getVersion()}`,
        buttons: ['OK'],
      });
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    emit({ state: 'error', message });
    if (manual) {
      await messageBox({
        type: 'error',
        title: 'Update check failed',
        message: 'Could not check for updates.',
        detail: `${message}\n\nFeed URL: ${feedUrl}`,
        buttons: ['OK'],
      });
    }
  }
}

/** Download the pending update and install on quit (gated by AUTO_INSTALL_ENABLED). */
export async function downloadUpdate(): Promise<void> {
  if (!AUTO_INSTALL_ENABLED) {
    await openDownloadPage();
    return;
  }
  try {
    await autoUpdater.downloadUpdate();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    emit({ state: 'error', message });
  }
}

/** Open the external download page (fallback while in-app install is gated). */
export async function openDownloadPage(): Promise<void> {
  const config = loadConfig();
  const url = config.update.downloadPageUrl;
  if (url) {
    await shell.openExternal(url);
    return;
  }
  await messageBox({
    type: 'info',
    title: 'Download',
    message: 'No download page configured.',
    detail: 'Set "update.downloadPageUrl" in desktop-config.json.',
    buttons: ['OK'],
  });
}

/** True when the silent startup auto-check should run. */
export function isAutoCheckEnabled(): boolean {
  const config = loadConfig();
  return config.update.enabled && config.update.feedUrl != null;
}
