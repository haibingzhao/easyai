/**
 * User-editable desktop configuration stored at {userData}/desktop-config.json.
 *
 * Defaults are chosen per runtime:
 * - Dev (unpackaged): H2 database next to the backend jar — easy to inspect/wipe.
 * - Packaged: H2 database under {userData}, since the app bundle / install
 *   directory is read-only (macOS code signing, Windows Program Files).
 *
 * Switch to PostgreSQL by setting dbMode to "postgres" and filling in the
 * connection block, e.g.:
 * {
 *   "dbMode": "postgres",
 *   "postgres": {
 *     "url": "r2dbc:postgresql://localhost:5432/easyai",
 *     "username": "easyai",
 *     "password": "secret"
 *   }
 * }
 */
import { app } from 'electron';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { backendDir } from './paths';

export type DbMode = 'h2' | 'postgres';

export interface PostgresConfig {
  url: string;
  username: string;
  password: string;
}

/**
 * Auto-update settings. v1 ships detection + prompt only; actual install is
 * gated until the macOS build is code-signed & notarized (see updater.ts
 * AUTO_INSTALL_ENABLED). Point `feedUrl` at a generic update server that
 * hosts the electron-builder `latest*.yml` manifests plus the installers, e.g.:
 * {
 *   "update": {
 *     "enabled": true,
 *     "feedUrl": "https://updates.example.com/easyai-desktop",
 *     "downloadPageUrl": "https://example.com/easyai-desktop/download"
 *   }
 * }
 */
export interface UpdateConfig {
  /** Master switch for the startup auto-check. Manual "Check for Updates" always works. */
  enabled: boolean;
  /** Generic update feed base URL (serves latest.yml / latest-mac.yml). null = not configured. */
  feedUrl: string | null;
  /** External download page opened when in-app install is not available. */
  downloadPageUrl: string | null;
}

export interface DesktopConfig {
  dbMode: DbMode;
  /** H2 database directory (without file extension). null = runtime default. */
  h2Dir: string | null;
  postgres: PostgresConfig | null;
  /** Agent working directory. null = user home. */
  workDir: string | null;
  /** Extra JVM arguments passed before -jar. */
  jvmArgs: string[];
  /** Auto-update settings. */
  update: UpdateConfig;
}

export function configPath(): string {
  return path.join(app.getPath('userData'), 'desktop-config.json');
}

export function defaultH2Dir(): string {
  return app.isPackaged
    ? path.join(app.getPath('userData'), 'db')
    : path.join(backendDir(), 'db');
}

export function loadConfig(): DesktopConfig {
  const defaults: DesktopConfig = {
    dbMode: 'h2',
    h2Dir: null,
    postgres: null,
    workDir: null,
    jvmArgs: [],
    update: { enabled: false, feedUrl: null, downloadPageUrl: null },
  };
  try {
    const raw = fs.readFileSync(configPath(), 'utf-8');
    const parsed = JSON.parse(raw) as Partial<DesktopConfig>;
    const update: Partial<UpdateConfig> = parsed.update ?? {};
    return {
      dbMode: parsed.dbMode === 'postgres' ? 'postgres' : 'h2',
      h2Dir: typeof parsed.h2Dir === 'string' && parsed.h2Dir.length > 0 ? parsed.h2Dir : null,
      postgres: parsed.postgres ?? null,
      workDir: typeof parsed.workDir === 'string' && parsed.workDir.length > 0 ? parsed.workDir : null,
      jvmArgs: Array.isArray(parsed.jvmArgs) ? parsed.jvmArgs.filter((a) => typeof a === 'string') : [],
      update: {
        enabled: update.enabled === true,
        feedUrl: typeof update.feedUrl === 'string' && update.feedUrl.length > 0 ? update.feedUrl : null,
        downloadPageUrl:
          typeof update.downloadPageUrl === 'string' && update.downloadPageUrl.length > 0
            ? update.downloadPageUrl
            : null,
      },
    };
  } catch {
    return defaults;
  }
}

/**
 * Build an r2dbc H2 file URL for an absolute directory path.
 *
 * Two parser quirks shape the format (verified against r2dbc-spi 1.0.0 /
 * r2dbc-h2 1.0.1 sources):
 * 1. ConnectionUrlParser rewrites the URL to `r2dbc:<scheme-specific-part>`
 *    and calls java.net.URI.create on it, so the path must only contain
 *    characters that are legal inside a URI path. Spaces etc. are
 *    percent-encoded; URI.getPath() decodes them back before the database
 *    segment reaches the H2 driver, so the file lands at the real path.
 * 2. The parser strips one leading slash from the database segment, so Unix
 *    absolute paths need an extra slash to survive:
 *      /a/b    -> r2dbc:h2:file:////a/b
 *      C:/a/b  -> r2dbc:h2:file:///C:/a/b
 */
export function h2DatabaseUrl(dir: string): string {
  const normalized = dir.replace(/\\/g, '/');
  // Encode characters that are illegal in a URI path (space first-class
  // offender: {userData} is "~/Library/Application Support/..." on macOS).
  const encoded = normalized.replace(/[ %<>"{}|\\^`#]/g, (c) =>
    '%' + c.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')
  );
  return `r2dbc:h2:file:///${encoded};MODE=MYSQL`;
}

export interface ResolvedDb {
  url: string;
  username: string;
  password: string;
}

export function resolveDatabase(config: DesktopConfig): ResolvedDb {
  if (config.dbMode === 'postgres') {
    if (!config.postgres || config.postgres.url.length === 0) {
      throw new Error(
        'desktop-config.json sets dbMode=postgres but the postgres connection block is missing or empty.'
      );
    }
    return {
      url: config.postgres.url,
      username: config.postgres.username,
      password: config.postgres.password,
    };
  }
  const dir = config.h2Dir ?? defaultH2Dir();
  const absDir = path.resolve(dir);
  fs.mkdirSync(absDir, { recursive: true });
  return {
    url: h2DatabaseUrl(path.join(absDir, 'easyai')),
    username: 'sa',
    password: '',
  };
}

export function resolveWorkDir(config: DesktopConfig): string {
  const dir = config.workDir ?? os.homedir();
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}
