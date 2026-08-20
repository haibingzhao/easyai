import React, { useState, useEffect, useCallback } from 'react';
import { ragService } from '@/services/rag-service';
import type { RagStatus, RagTestResult } from '@/services/rag-service';
import { WorkspaceConfigSection } from '@/components/settings/WorkspaceConfigSection';
import { i18n } from '@/utils/i18n';
import { Loader2, CheckCircle2, AlertCircle, Eye, EyeOff, Layers } from 'lucide-react';

/** Sentinel: the password field displays the masked stored value; edits replace it entirely. */
const UNCHANGED_PASSWORD = '__unchanged__';

export const RagTab: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [status, setStatus] = useState<RagStatus | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [baseUrl, setBaseUrl] = useState('http://localhost:8020');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [workspace, setWorkspace] = useState('');
  const [topK, setTopK] = useState(5);
  const [showPassword, setShowPassword] = useState(false);
  const [testResult, setTestResult] = useState<RagTestResult | null>(null);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const loadStatus = useCallback(async () => {
    try {
      const data = await ragService.getStatus();
      setStatus(data);
      setEnabled(data.enabled);
      setBaseUrl(data.baseUrl);
      setUsername(data.username ?? '');
      setWorkspace(data.workspace ?? '');
      setPassword(data.password ? UNCHANGED_PASSWORD : '');
      setTopK(data.topK);
    } catch {
      // ignore load errors
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStatus();
  }, [loadStatus]);

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    setMessage(null);
    try {
      // Persist current connection settings first so the test uses them
      await persistSettings(false);
      const result = await ragService.testConnection();
      setTestResult(result);
    } catch (e) {
      setTestResult({ connected: false, latencyMs: 0, message: e instanceof Error ? e.message : 'Test failed' });
    } finally {
      setTesting(false);
    }
  };

  const persistSettings = async (showSavedMessage: boolean): Promise<boolean> => {
    const result = await ragService.updateSettings({
      enabled,
      baseUrl: baseUrl.trim() || 'http://localhost:8020',
      // Send real values (including empty string, which clears the stored field);
      // password stays "empty = keep existing" since the field starts blank with a masked placeholder
      username: username.trim(),
      password: password && password !== UNCHANGED_PASSWORD ? password : undefined,
      workspace: workspace.trim(),
      topK,
    });
    if (result.success && showSavedMessage) {
      setMessage({ type: 'success', text: i18n('Settings saved successfully') });
      // loadStatus resets the password field to the masked sentinel
      await loadStatus();
    } else if (!result.success) {
      setMessage({ type: 'error', text: result.message });
    }
    return result.success;
  };

  const handleSave = async () => {
    setSaving(true);
    setMessage(null);
    try {
      await persistSettings(true);
    } catch (e) {
      setMessage({ type: 'error', text: e instanceof Error ? e.message : 'Failed to save' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-48">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-lg">
      <div>
        <div className="flex items-center gap-2 mb-4">
          <Layers className="w-5 h-5 text-muted-foreground" />
          <h2 className="text-lg font-medium">{i18n('RAG Service')}</h2>
          {status?.connected ? (
            <span className="flex items-center gap-1 text-xs text-green-500 bg-green-500/10 px-2 py-0.5 rounded">
              <CheckCircle2 className="w-3 h-3" />
              {i18n('Connected')}
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-amber-500 bg-amber-500/10 px-2 py-0.5 rounded">
              <AlertCircle className="w-3 h-3" />
              {i18n('Disconnected')}
            </span>
          )}
        </div>

        <p className="text-sm text-muted-foreground mb-4">
          {i18n('Connect an EasyRAG server for semantic memory storage and retrieval. When enabled, memories are stored entirely in EasyRAG.')}
        </p>

        <div className="space-y-4">
          {/* Enabled toggle */}
          <label className="flex items-center gap-2 text-sm font-medium cursor-pointer">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
              className="w-4 h-4 rounded border-input"
            />
            {i18n('Enabled')}
          </label>

          {/* Base URL */}
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Base URL')}</label>
            <input
              type="text"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="http://localhost:8020"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
            />
          </div>

          {/* Credentials */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Username')}</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder={i18n('Optional')}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Password')}</label>
              <div className="relative">
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password === UNCHANGED_PASSWORD ? '' : password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={status?.password ? status.password : i18n('Optional')}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 pr-9 text-sm"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>
          </div>

          {/* Advanced */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Workspace (optional)')}</label>
              <input
                type="text"
                value={workspace}
                onChange={(e) => setWorkspace(e.target.value)}
                placeholder="default"
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Top K')}</label>
              <input
                type="number"
                min={1}
                max={50}
                value={topK}
                onChange={(e) => setTopK(Number(e.target.value) || 5)}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
          </div>

          {/* Actions */}
          <div className="flex gap-3 pt-2">
            <button
              onClick={handleTest}
              disabled={testing || !baseUrl}
              className="flex items-center gap-2 px-4 py-2 rounded-md border border-input text-sm font-medium hover:bg-muted disabled:opacity-50 transition-colors"
            >
              {testing ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
              {i18n('Test Connection')}
            </button>
            <button
              onClick={handleSave}
              disabled={saving}
              className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 transition-colors"
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
              {i18n('Save')}
            </button>
          </div>

          {/* Test result */}
          {testResult && (
            <div className={`flex items-center gap-2 p-3 rounded-md text-sm ${
              testResult.connected
                ? 'bg-green-500/10 text-green-500'
                : 'bg-destructive/10 text-destructive'
            }`}>
              {testResult.connected ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
              {testResult.message} ({testResult.latencyMs}ms)
            </div>
          )}

          {/* Message */}
          {message && (
            <div className={`p-3 rounded-md text-sm ${
              message.type === 'success'
                ? 'bg-green-500/10 text-green-500'
                : 'bg-destructive/10 text-destructive'
            }`}>
              {message.text}
            </div>
          )}
        </div>
      </div>

      {/* Workspace Configuration — visible only when connected with a workspace */}
      {enabled && status?.connected && workspace && (
        <div className="pt-6 border-t border-border">
          <WorkspaceConfigSection
            workspace={workspace}
            connected={status.connected}
          />
        </div>
      )}
    </div>
  );
};
