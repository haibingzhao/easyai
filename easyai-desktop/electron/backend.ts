/**
 * Embedded JVM backend lifecycle: spawn the Spring Boot fat jar as a child
 * process, poll its health endpoint, and shut it down gracefully on quit.
 */
import { ChildProcess, spawn } from 'child_process';
import fs from 'fs';
import http from 'http';
import net from 'net';
import path from 'path';
import { javaExecutable, jarPath, logDir } from './paths';

export interface BackendHandle {
  port: number;
  process: ChildProcess;
  logFile: string;
}

export interface StartOptions {
  port: number;
  dbUrl: string;
  dbUsername: string;
  dbPassword: string;
  workDir: string;
  jvmArgs: string[];
}

/** Pick a free TCP port on the loopback interface. */
export function findFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (address && typeof address === 'object') {
        const port = address.port;
        server.close(() => resolve(port));
      } else {
        server.close(() => reject(new Error('Failed to determine a free port')));
      }
    });
  });
}

export function backendUrl(port: number): string {
  return `http://127.0.0.1:${port}`;
}

export function healthUrl(port: number): string {
  return `${backendUrl(port)}/api/chat/health`;
}

/**
 * Spawn the backend jar. stdout/stderr are captured to
 * {userData}/logs/backend-stdout.log for post-mortem inspection.
 */
export function startBackend(options: StartOptions): BackendHandle {
  const jar = jarPath();
  if (!fs.existsSync(jar)) {
    throw new Error(
      `Backend jar not found at ${jar}. ` +
      'Run scripts/build-backend.sh first (or set EASYAI_JAR_PATH).'
    );
  }

  const args = [
    ...options.jvmArgs,
    '-jar', jar,
    `--server.port=${options.port}`,
    `--easyai.r2dbc.url=${options.dbUrl}`,
    `--easyai.r2dbc.username=${options.dbUsername}`,
    `--easyai.r2dbc.password=${options.dbPassword}`,
    `--easyai.work-dir=${options.workDir}`,
  ];

  fs.mkdirSync(logDir(), { recursive: true });
  const logFile = path.join(logDir(), 'backend-stdout.log');
  const logStream = fs.createWriteStream(logFile, { flags: 'w' });

  const child = spawn(javaExecutable(), args, {
    cwd: path.dirname(jar),
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  child.stdout?.on('data', (chunk: Buffer) => {
    logStream.write(chunk);
    process.stdout.write(`[backend] ${chunk}`);
  });
  child.stderr?.on('data', (chunk: Buffer) => {
    logStream.write(chunk);
    process.stderr.write(`[backend] ${chunk}`);
  });
  child.once('exit', () => logStream.end());

  return { port: options.port, process: child, logFile };
}

function checkHealth(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.get(healthUrl(port), { timeout: 2000 }, (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

/**
 * Poll the health endpoint until it answers 200.
 * Rejects on timeout or when the child process exits prematurely.
 */
export function waitForHealth(
  handle: BackendHandle,
  timeoutMs: number,
  onTick?: (elapsedMs: number) => void
): Promise<void> {
  const startedAt = Date.now();
  return new Promise((resolve, reject) => {
    const interval = setInterval(async () => {
      const elapsed = Date.now() - startedAt;
      onTick?.(elapsed);

      if (handle.process.exitCode !== null) {
        clearInterval(interval);
        reject(new Error(
          `Backend process exited prematurely with code ${handle.process.exitCode}. ` +
          `See ${handle.logFile} for details.`
        ));
        return;
      }
      if (elapsed > timeoutMs) {
        clearInterval(interval);
        reject(new Error(
          `Backend did not become healthy within ${Math.round(timeoutMs / 1000)}s. ` +
          `See ${handle.logFile} for details.`
        ));
        return;
      }
      if (await checkHealth(handle.port)) {
        clearInterval(interval);
        resolve();
      }
    }, 600);
  });
}

/** SIGTERM with a grace period, then SIGKILL. */
export function stopBackend(handle: BackendHandle, graceMs = 6000): Promise<void> {
  return new Promise((resolve) => {
    const child = handle.process;
    if (child.exitCode !== null || child.killed) {
      resolve();
      return;
    }
    const forceTimer = setTimeout(() => {
      child.kill('SIGKILL');
    }, graceMs);
    child.once('exit', () => {
      clearTimeout(forceTimer);
      resolve();
    });
    child.kill('SIGTERM');
  });
}
