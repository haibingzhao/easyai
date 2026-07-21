/**
 * Artifact path resolution for dev vs packaged layouts.
 *
 * Packaged:  {app}.app/Contents/Resources/backend/{jre, easyai-desktop-server.jar}
 * Dev:       easyai-desktop/backend/{jre, easyai-desktop-server.jar}
 *            (populated by scripts/build-backend.sh and scripts/fetch-jre.sh)
 */
import { app } from 'electron';
import fs from 'fs';
import path from 'path';

/** Directory holding the backend jar (and optionally the bundled JRE). */
export function backendDir(): string {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'backend')
    : path.join(app.getAppPath(), 'backend');
}

/** Absolute path of the backend fat jar. Overridable via EASYAI_JAR_PATH. */
export function jarPath(): string {
  const override = process.env.EASYAI_JAR_PATH;
  if (override && override.length > 0) {
    return override;
  }
  return path.join(backendDir(), 'easyai-desktop-server.jar');
}

/** Bundled JRE home, or null when absent (falls back to system `java`). */
export function jreHome(): string | null {
  const candidate = path.join(backendDir(), 'jre');
  return fs.existsSync(candidate) ? candidate : null;
}

/** Java executable: bundled JRE first, then system PATH. */
export function javaExecutable(): string {
  const home = jreHome();
  if (home) {
    const exe = process.platform === 'win32'
      ? path.join(home, 'bin', 'java.exe')
      : path.join(home, 'bin', 'java');
    if (fs.existsSync(exe)) {
      return exe;
    }
  }
  return 'java';
}

/** Directory where captured backend stdout/stderr is stored. */
export function logDir(): string {
  return path.join(app.getPath('userData'), 'logs');
}
