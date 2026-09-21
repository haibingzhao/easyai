import React, { useState, useEffect, useCallback } from 'react';
import { mediaProviderService } from '@/services/media-provider-service';
import type {
  MediaProviderConfig,
  MediaProviderEffectiveSource,
  MediaServiceKind,
  SaveMediaProviderRequest,
} from '@/types/settings';
import { i18n } from '@/utils/i18n';
import { AudioLines, Image as ImageIcon, Video, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';

/** Which layer is in force, rendered as the per-card status badge. */
const SOURCE_BADGE: Record<MediaProviderEffectiveSource, { label: string; tone: string }> = {
  user: { label: 'User settings', tone: 'text-green-500 bg-green-500/10' },
  system: { label: 'Shared system settings', tone: 'text-blue-500 bg-blue-500/10' },
  none: { label: 'Not configured', tone: 'text-muted-foreground bg-muted' },
};

const KIND_META: Record<MediaServiceKind, { title: string; desc: string; icon: React.ReactNode }> = {
  speech: {
    title: i18n('Speech'),
    desc: i18n('Text-to-speech provider used by the generate_speech tool.'),
    icon: <AudioLines className="w-5 h-5 text-muted-foreground" />,
  },
  image: {
    title: i18n('Image'),
    desc: i18n('Text-to-image provider used by the generate_image tool.'),
    icon: <ImageIcon className="w-5 h-5 text-muted-foreground" />,
  },
  video: {
    title: i18n('Video'),
    desc: i18n('Text-to-video provider used by the generate_video tool.'),
    icon: <Video className="w-5 h-5 text-muted-foreground" />,
  },
};

const PROVIDER_OPTIONS = ['openai', 'dashscope', 'kling'];

const KIND_ORDER: MediaServiceKind[] = ['speech', 'image', 'video'];

export const MediaTab: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [configs, setConfigs] = useState<Record<MediaServiceKind, MediaProviderConfig | null>>({
    speech: null,
    image: null,
    video: null,
  });
  const [loadError, setLoadError] = useState('');

  const loadAll = useCallback(async () => {
    try {
      const list = await mediaProviderService.list();
      const next: Record<MediaServiceKind, MediaProviderConfig | null> = { speech: null, image: null, video: null };
      list.forEach((c) => { next[c.serviceKind] = c; });
      setConfigs(next);
      setLoadError('');
    } catch (e) {
      // 503 when persistence is off, 401/403 otherwise — the message comes from the server
      setLoadError(e instanceof Error ? e.message : 'Failed to load media provider settings');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

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

  return (
    <div className="space-y-8 max-w-2xl">
      <p className="text-sm text-muted-foreground">
        {i18n('Media-generation credentials are stored per account in the database and take effect immediately, no restart. Each kind is used only by its generation tool.')}
      </p>
      {KIND_ORDER.map((kind) => (
        <MediaProviderCard key={kind} kind={kind} config={configs[kind]} onSaved={loadAll} />
      ))}
    </div>
  );
};

interface CardProps {
  kind: MediaServiceKind;
  config: MediaProviderConfig | null;
  onSaved: () => Promise<void> | void;
}

const MediaProviderCard: React.FC<CardProps> = ({ kind, config, onSaved }) => {
  const [enabled, setEnabled] = useState(false);
  const [providerType, setProviderType] = useState('openai');
  const [baseUrl, setBaseUrl] = useState('');
  const [region, setRegion] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [accessKeyId, setAccessKeyId] = useState('');
  // Always start blank: the server only returns a mask, and blank on save means "keep stored"
  const [accessKeySecret, setAccessKeySecret] = useState('');
  const [defaultModel, setDefaultModel] = useState('');
  const [options, setOptions] = useState('');
  const [timeoutSeconds, setTimeoutSeconds] = useState(600);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Sync form from the loaded row; secrets intentionally stay blank (masked server-side).
  useEffect(() => {
    setEnabled(config?.enabled ?? false);
    setProviderType(config?.providerType ?? 'openai');
    setBaseUrl(config?.baseUrl ?? '');
    setRegion(config?.region ?? '');
    setApiKey('');
    setAccessKeyId(config?.accessKeyId ?? '');
    setAccessKeySecret('');
    setDefaultModel(config?.defaultModel ?? '');
    setOptions(config?.options ?? '');
    setTimeoutSeconds(config?.timeoutSeconds ?? 600);
  }, [config]);

  const buildRequest = (): SaveMediaProviderRequest => ({
    enabled,
    providerType,
    baseUrl: baseUrl.trim(),
    region: region.trim(),
    // undefined (not "") keeps the stored credential; the field only ever holds a newly typed value
    apiKey: apiKey.trim() ? apiKey.trim() : undefined,
    accessKeyId: accessKeyId.trim(),
    accessKeySecret: accessKeySecret.trim() ? accessKeySecret.trim() : undefined,
    defaultModel: defaultModel.trim(),
    options: options.trim(),
    timeoutSeconds,
  });

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    setMessage(null);
    try {
      const result = await mediaProviderService.testConfig(kind, buildRequest());
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
      await mediaProviderService.saveConfig(kind, buildRequest());
      setMessage({ type: 'success', text: i18n('Settings saved successfully') });
      await onSaved();
    } catch (e) {
      setMessage({ type: 'error', text: e instanceof Error ? e.message : 'Failed to save' });
    } finally {
      setSaving(false);
    }
  };

  const badge = SOURCE_BADGE[config?.effectiveSource ?? 'none'];
  const meta = KIND_META[kind];

  return (
    <div className="rounded-lg border border-border p-5">
      <div className="flex items-center gap-2 mb-1">
        {meta.icon}
        <h2 className="text-lg font-medium">{meta.title}</h2>
        <span className={`flex items-center gap-1 text-xs px-2 py-0.5 rounded ${badge.tone}`}>
          {badge.label}
        </span>
      </div>
      <p className="text-sm text-muted-foreground mb-4">{meta.desc}</p>

      <div className="space-y-4">
        <label className="flex items-center gap-2 text-sm font-medium cursor-pointer">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            className="w-4 h-4 rounded border-input"
          />
          {i18n('Enabled')}
        </label>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Provider')}</label>
            <select
              value={providerType}
              onChange={(e) => setProviderType(e.target.value)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              {PROVIDER_OPTIONS.map((p) => (
                <option key={p} value={p}>{p}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Default Model')}</label>
            <input
              type="text"
              value={defaultModel}
              onChange={(e) => setDefaultModel(e.target.value)}
              placeholder="gpt-image-1"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
            />
          </div>
        </div>

        <div>
          <label className="text-sm font-medium mb-1 block">{i18n('Base URL')}</label>
          <input
            type="text"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="https://api.openai.com/v1"
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
          />
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Region')}</label>
            <input
              type="text"
              value={region}
              onChange={(e) => setRegion(e.target.value)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
            />
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Timeout (seconds)')}</label>
            <input
              type="number"
              value={timeoutSeconds}
              onChange={(e) => setTimeoutSeconds(Number(e.target.value) || 600)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
        </div>

        <div>
          <label className="text-sm font-medium mb-1 block">{i18n('API Key')}</label>
          <input
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder={config?.apiKey ? `${config.apiKey} · ${i18n('leave blank to keep unchanged')}` : i18n('OpenAI-style single key')}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
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
              placeholder={config?.accessKeySecret ? `${config.accessKeySecret} · ${i18n('leave blank to keep unchanged')}` : i18n('AK/SK-style (optional)')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
        </div>

        <div>
          <label className="text-sm font-medium mb-1 block">{i18n('Options (JSON)')}</label>
          <textarea
            value={options}
            onChange={(e) => setOptions(e.target.value)}
            placeholder='{"size":"1024x1024"}'
            rows={2}
            className="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm font-mono"
          />
          <p className="text-xs text-muted-foreground mt-1">
            {i18n('Provider-specific parameters such as size or voice.')}
          </p>
        </div>

        <div className="flex gap-3 pt-2">
          <button
            onClick={handleTest}
            disabled={testing}
            className="flex items-center gap-2 px-4 py-2 rounded-md border border-input text-sm font-medium hover:bg-muted disabled:opacity-50 transition-colors"
          >
            {testing ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
            {i18n('Test')}
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

        {testResult && (
          <div className={`flex items-center gap-2 p-3 rounded-md text-sm ${
            testResult.success ? 'bg-green-500/10 text-green-500' : 'bg-destructive/10 text-destructive'
          }`}>
            {testResult.success ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
            {testResult.message}
          </div>
        )}

        {message && (
          <div className={`p-3 rounded-md text-sm ${
            message.type === 'success' ? 'bg-green-500/10 text-green-500' : 'bg-destructive/10 text-destructive'
          }`}>
            {message.text}
          </div>
        )}
      </div>
    </div>
  );
};
