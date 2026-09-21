import React, { useState, useEffect, useCallback } from 'react';
import { storageConfigService } from '@/services/storage-config-service';
import type { StorageConfig, StorageBackendType, SaveStorageConfigRequest } from '@/types/settings';
import { i18n } from '@/utils/i18n';
import { HardDrive, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';

/** Which layer is in force, rendered as the top status badge. */
const SOURCE_BADGE: Record<StorageConfig['effectiveSource'], { label: string; tone: string }> = {
  user: { label: 'User settings', tone: 'text-green-500 bg-green-500/10' },
  system: { label: 'Shared system settings', tone: 'text-blue-500 bg-blue-500/10' },
  none: { label: 'Not configured', tone: 'text-muted-foreground bg-muted' },
};

export const StorageTab: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [config, setConfig] = useState<StorageConfig | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [type, setType] = useState<StorageBackendType>('aliyun');
  const [endpoint, setEndpoint] = useState('');
  const [bucket, setBucket] = useState('');
  const [accessKeyId, setAccessKeyId] = useState('');
  // Always start blank: the server only ever returns a mask, and blank on save means "keep stored"
  const [accessKeySecret, setAccessKeySecret] = useState('');
  const [localDir, setLocalDir] = useState('');
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [loadError, setLoadError] = useState('');

  const loadConfig = useCallback(async () => {
    try {
      const data = await storageConfigService.getConfig();
      setConfig(data);
      setEnabled(data.enabled);
      setType(data.type);
      setEndpoint(data.endpoint);
      setBucket(data.bucket);
      setAccessKeyId(data.accessKeyId);
      setAccessKeySecret('');
      setLocalDir(data.localDir);
      setLoadError('');
    } catch (e) {
      // 503 when persistence is off, 401/403 otherwise — the message comes from the server
      setLoadError(e instanceof Error ? e.message : 'Failed to load storage settings');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadConfig();
  }, [loadConfig]);

  const buildRequest = (): SaveStorageConfigRequest => ({
    enabled,
    type,
    endpoint: type === 'aliyun' ? endpoint.trim() : undefined,
    bucket: type === 'aliyun' ? bucket.trim() : undefined,
    accessKeyId: type === 'aliyun' ? accessKeyId.trim() : undefined,
    // undefined (not "") keeps the stored credential; the field only ever holds a newly typed value
    accessKeySecret: accessKeySecret.trim() ? accessKeySecret.trim() : undefined,
    localDir: type === 'local' ? localDir.trim() : undefined,
  });

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    setMessage(null);
    try {
      const result = await storageConfigService.testConfig(buildRequest());
      setTestResult(result);
    } catch (e) {
      setTestResult({ success: false, message: e instanceof Error ? e.message : 'Test failed' });
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setMessage(null);
    setTestResult(null);
    try {
      await storageConfigService.saveConfig(buildRequest());
      setMessage({ type: 'success', text: i18n('Settings saved successfully') });
      // Re-read so the badge and the masked field reflect what is now live — no restart needed
      await loadConfig();
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

  if (loadError) {
    return (
      <div className="flex items-start gap-2 p-3 rounded-lg bg-destructive/10 text-destructive text-sm max-w-lg">
        <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
        <span>{loadError}</span>
      </div>
    );
  }

  const badge = SOURCE_BADGE[config?.effectiveSource ?? 'none'];

  return (
    <div className="space-y-6 max-w-lg">
      <div>
        <div className="flex items-center gap-2 mb-4">
          <HardDrive className="w-5 h-5 text-muted-foreground" />
          <h2 className="text-lg font-medium">{i18n('Object Storage')}</h2>
          <span className={`flex items-center gap-1 text-xs px-2 py-0.5 rounded ${badge.tone}`}>
            {badge.label}
          </span>
        </div>

        <p className="text-sm text-muted-foreground mb-4">
          {i18n('Store for published skill packages, configured per account in the database. Saved settings take effect immediately; type=local keeps everything on this machine.')}
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

          {/* Backend type */}
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Storage Type')}</label>
            <select
              value={type}
              onChange={(e) => setType(e.target.value as StorageBackendType)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="aliyun">{i18n('Aliyun OSS')}</option>
              <option value="local">{i18n('Local Directory')}</option>
            </select>
          </div>

          {type === 'aliyun' ? (
            <>
              <div>
                <label className="text-sm font-medium mb-1 block">{i18n('Endpoint')}</label>
                <input
                  type="text"
                  value={endpoint}
                  onChange={(e) => setEndpoint(e.target.value)}
                  placeholder="https://oss-cn-hangzhou.aliyuncs.com"
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
                />
              </div>
              <div>
                <label className="text-sm font-medium mb-1 block">{i18n('Bucket')}</label>
                <input
                  type="text"
                  value={bucket}
                  onChange={(e) => setBucket(e.target.value)}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-sm font-medium mb-1 block">{i18n('Access Key ID')}</label>
                  <input
                    type="text"
                    value={accessKeyId}
                    onChange={(e) => setAccessKeyId(e.target.value)}
                    className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                  />
                </div>
                <div>
                  <label className="text-sm font-medium mb-1 block">{i18n('Access Key Secret')}</label>
                  <input
                    type="password"
                    value={accessKeySecret}
                    onChange={(e) => setAccessKeySecret(e.target.value)}
                    placeholder={config?.accessKeySecret ? `${config.accessKeySecret} · ${i18n('leave blank to keep unchanged')}` : i18n('Required')}
                    className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
                  />
                </div>
              </div>
            </>
          ) : (
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Local Directory')}</label>
              <input
                type="text"
                value={localDir}
                onChange={(e) => setLocalDir(e.target.value)}
                placeholder="~/.easyai/storage"
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
              />
              <p className="text-xs text-muted-foreground mt-1">
                {i18n('Leave empty to use the default ~/.easyai/storage')}
              </p>
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-3 pt-2">
            <button
              onClick={handleTest}
              disabled={testing}
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
              testResult.success
                ? 'bg-green-500/10 text-green-500'
                : 'bg-destructive/10 text-destructive'
            }`}>
              {testResult.success ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
              {testResult.message}
            </div>
          )}

          {/* Save / error message */}
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
    </div>
  );
};
