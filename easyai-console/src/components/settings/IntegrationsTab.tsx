import React, { useState, useEffect, useCallback } from 'react';
import { integrationService } from '@/services/integration-service';
import type { WebSearchStatus } from '@/services/integration-service';
import { i18n } from '@/utils/i18n';
import { Loader2, CheckCircle2, AlertCircle, Eye, EyeOff, Globe } from 'lucide-react';

export const IntegrationsTab: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<WebSearchStatus | null>(null);
  const [exaKey, setExaKey] = useState('');
  const [parallelKey, setParallelKey] = useState('');
  const [provider, setProvider] = useState('exa');
  const [showExa, setShowExa] = useState(false);
  const [showParallel, setShowParallel] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const loadStatus = useCallback(async () => {
    try {
      const data = await integrationService.getStatus();
      setStatus(data.webSearch);
      setProvider(data.webSearch.provider);
    } catch {
      // ignore load errors
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStatus();
  }, [loadStatus]);

  const handleSave = async () => {
    setSaving(true);
    setMessage(null);
    try {
      const request: Record<string, string> = {};
      if (exaKey) request.exaApiKey = exaKey;
      if (parallelKey) request.parallelApiKey = parallelKey;
      request.websearchProvider = provider;

      const result = await integrationService.updateSettings(request);
      if (result.success) {
        setMessage({ type: 'success', text: i18n('Settings saved successfully') });
        setExaKey('');
        setParallelKey('');
        await loadStatus();
      } else {
        setMessage({ type: 'error', text: result.message });
      }
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
      {/* Web Search Section */}
      <div>
        <div className="flex items-center gap-2 mb-4">
          <Globe className="w-5 h-5 text-muted-foreground" />
          <h2 className="text-lg font-medium">{i18n('Web Search')}</h2>
          {status?.configured ? (
            <span className="flex items-center gap-1 text-xs text-green-500 bg-green-500/10 px-2 py-0.5 rounded">
              <CheckCircle2 className="w-3 h-3" />
              {i18n('Configured')}
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-amber-500 bg-amber-500/10 px-2 py-0.5 rounded">
              <AlertCircle className="w-3 h-3" />
              {i18n('Not configured')}
            </span>
          )}
        </div>

        <p className="text-sm text-muted-foreground mb-4">
          {i18n('Configure API keys for web search providers. At least one key is required for the websearch tool to function.')}
        </p>

        <div className="space-y-4">
          {/* Provider Selection */}
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Default Provider')}</label>
            <select
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="exa">Exa (exa.ai)</option>
              <option value="parallel">Parallel (parallel.ai)</option>
            </select>
          </div>

          {/* Exa API Key */}
          <div>
            <label className="text-sm font-medium mb-1 block">Exa API Key</label>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <input
                  type={showExa ? 'text' : 'password'}
                  value={exaKey}
                  onChange={(e) => setExaKey(e.target.value)}
                  placeholder={status?.exaConfigured ? status.exaApiKey ?? '••••••••' : 'Enter Exa API key...'}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 pr-9 text-sm"
                />
                <button
                  type="button"
                  onClick={() => setShowExa(!showExa)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showExa ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
              {status?.exaConfigured && (
                <span className="flex items-center text-green-500">
                  <CheckCircle2 className="w-4 h-4" />
                </span>
              )}
            </div>
            <p className="text-xs text-muted-foreground mt-1">
              {i18n('Obtain from')} <a href="https://exa.ai" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">exa.ai</a>
            </p>
          </div>

          {/* Parallel API Key */}
          <div>
            <label className="text-sm font-medium mb-1 block">Parallel API Key</label>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <input
                  type={showParallel ? 'text' : 'password'}
                  value={parallelKey}
                  onChange={(e) => setParallelKey(e.target.value)}
                  placeholder={status?.parallelConfigured ? status.parallelApiKey ?? '••••••••' : 'Enter Parallel API key...'}
                  className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 pr-9 text-sm"
                />
                <button
                  type="button"
                  onClick={() => setShowParallel(!showParallel)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showParallel ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
              {status?.parallelConfigured && (
                <span className="flex items-center text-green-500">
                  <CheckCircle2 className="w-4 h-4" />
                </span>
              )}
            </div>
            <p className="text-xs text-muted-foreground mt-1">
              {i18n('Obtain from')} <a href="https://parallel.ai" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">parallel.ai</a>
            </p>
          </div>

          {/* Save Button */}
          <div className="pt-2">
            <button
              onClick={handleSave}
              disabled={saving || (!exaKey && !parallelKey && provider === (status?.provider ?? 'exa'))}
              className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 transition-colors"
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
              {i18n('Save')}
            </button>
          </div>

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
    </div>
  );
};
