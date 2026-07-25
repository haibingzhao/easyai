/**
 * Service for database setup and configuration management.
 *
 * Setup Mode endpoints (no auth required):
 * - GET  /api/setup/status
 * - POST /api/setup/test-connection
 * - POST /api/setup/apply
 *
 * Runtime endpoints (auth required):
 * - GET  /api/system/database
 * - POST /api/system/database/test
 * - POST /api/system/database/apply
 */

import { fetchJson, JSON_HEADERS } from '@/services/api-client';

export interface SetupStatus {
  mode: 'setup' | 'normal';
  dbType: string | null;
}

export interface DatabaseSetupRequest {
  dbType: 'h2' | 'postgres';
  h2Dir?: string | null;
  postgresUrl?: string | null;
  postgresUsername?: string | null;
  postgresPassword?: string | null;
}

export interface TestConnectionResponse {
  success: boolean;
  message: string;
}

export interface ApplyConfigResponse {
  success: boolean;
  message: string;
  restartRequired?: boolean;
}

export interface DatabaseInfo {
  configured: boolean;
  dbType: string;
  info: Record<string, string | null>;
}

export class SetupService {
  /**
   * Check if the backend is in setup mode or normal mode.
   * This endpoint is always public (no auth required).
   */
  async getStatus(): Promise<SetupStatus> {
    const response = await fetch('/api/setup/status');
    if (!response.ok) {
      throw new Error('Failed to get setup status');
    }
    return response.json();
  }

  /**
   * Test a database connection without saving.
   */
  async testConnection(request: DatabaseSetupRequest): Promise<TestConnectionResponse> {
    const response = await fetch('/api/setup/test-connection', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error('Failed to test connection');
    }
    return response.json();
  }

  /**
   * Apply database configuration (setup mode - no auth).
   */
  async applySetup(request: DatabaseSetupRequest): Promise<ApplyConfigResponse> {
    const response = await fetch('/api/setup/apply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error('Failed to apply configuration');
    }
    return response.json();
  }

  /**
   * Get current database info (normal mode - auth required).
   */
  async getDatabaseInfo(): Promise<DatabaseInfo> {
    return fetchJson<DatabaseInfo>('/api/system/database');
  }

  /**
   * Test a new database connection (normal mode - auth required).
   */
  async testConnectionAuth(request: DatabaseSetupRequest): Promise<TestConnectionResponse> {
    return fetchJson<TestConnectionResponse>('/api/system/database/test', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  /**
   * Apply new database configuration (normal mode - auth required).
   */
  async applyConfig(request: DatabaseSetupRequest): Promise<ApplyConfigResponse> {
    return fetchJson<ApplyConfigResponse>('/api/system/database/apply', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  /**
   * Wait for the backend to restart after configuration change.
   * Polls /api/setup/status until it responds with mode=normal or timeout.
   */
  async waitForRestart(timeout = 30000): Promise<boolean> {
    const start = Date.now();
    while (Date.now() - start < timeout) {
      try {
        const res = await fetch('/api/setup/status');
        if (res.ok) {
          const data: SetupStatus = await res.json();
          if (data.mode === 'normal') return true;
        }
      } catch {
        // Backend still restarting
      }
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
    return false;
  }
}

export const setupService = new SetupService();
