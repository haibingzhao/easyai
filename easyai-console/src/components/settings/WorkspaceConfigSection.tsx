import React, { useState, useEffect, useCallback } from 'react';
import { ragService } from '@/services/rag-service';
import type { WorkspaceTenantConfig, WorkspaceTenantConfigUpdate } from '@/services/rag-service';
import { i18n } from '@/utils/i18n';
import {
  Loader2,
  CheckCircle2,
  AlertCircle,
  Eye,
  EyeOff,
  Brain,
  FileText,
  Scissors,
  Search,
  RotateCcw,
  Settings2,
} from 'lucide-react';

/** Returns true when [key] matches a server-masked API key pattern (ends with `****`). */
const isMaskedKey = (key: string | null | undefined): boolean =>
  !key || key === '****' || key.endsWith('****');

interface WorkspaceConfigSectionProps {
  workspace: string;
  connected: boolean;
}

export const WorkspaceConfigSection: React.FC<WorkspaceConfigSectionProps> = ({
  workspace,
  connected,
}) => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [config, setConfig] = useState<WorkspaceTenantConfig | null>(null);

  // LLM fields
  const [llmModel, setLlmModel] = useState('');
  const [llmApiKey, setLlmApiKey] = useState('');
  const [llmBaseUrl, setLlmBaseUrl] = useState('');
  const [llmTemperature, setLlmTemperature] = useState<number | ''>('');
  const [llmMaxTokens, setLlmMaxTokens] = useState<number | ''>('');
  const [showLlmKey, setShowLlmKey] = useState(false);

  // Embedding fields
  const [embeddingModel, setEmbeddingModel] = useState('');
  const [embeddingApiKey, setEmbeddingApiKey] = useState('');
  const [embeddingBaseUrl, setEmbeddingBaseUrl] = useState('');
  const [embeddingDim, setEmbeddingDim] = useState<number | ''>('');
  const [showEmbKey, setShowEmbKey] = useState(false);

  // Chunking fields
  const [chunkSize, setChunkSize] = useState<number | ''>('');
  const [chunkOverlapSize, setChunkOverlapSize] = useState<number | ''>('');

  // Query fields
  const [language, setLanguage] = useState('');
  const [defaultTopK, setDefaultTopK] = useState<number | ''>('');
  const [rerankEnabled, setRerankEnabled] = useState(false);

  const populateFromConfig = useCallback((c: WorkspaceTenantConfig) => {
    setConfig(c);
    setLlmModel(c.llmModel ?? '');
    setLlmApiKey(c.llmApiKey ?? '');
    setLlmBaseUrl(c.llmBaseUrl ?? '');
    setLlmTemperature(c.llmTemperature ?? '');
    setLlmMaxTokens(c.llmMaxTokens ?? '');
    setEmbeddingModel(c.embeddingModel ?? '');
    setEmbeddingApiKey(c.embeddingApiKey ?? '');
    setEmbeddingBaseUrl(c.embeddingBaseUrl ?? '');
    setEmbeddingDim(c.embeddingDim ?? '');
    setChunkSize(c.chunkSize ?? '');
    setChunkOverlapSize(c.chunkOverlapSize ?? '');
    setLanguage(c.language ?? '');
    setDefaultTopK(c.defaultTopK ?? '');
    setRerankEnabled(c.rerankEnabled ?? false);
  }, []);

  const resetFields = useCallback(() => {
    setLlmModel('');
    setLlmApiKey('');
    setLlmBaseUrl('');
    setLlmTemperature('');
    setLlmMaxTokens('');
    setEmbeddingModel('');
    setEmbeddingApiKey('');
    setEmbeddingBaseUrl('');
    setEmbeddingDim('');
    setChunkSize('');
    setChunkOverlapSize('');
    setLanguage('');
    setDefaultTopK('');
    setRerankEnabled(false);
  }, []);

  const loadConfig = useCallback(async () => {
    setLoading(true);
    try {
      const data = await ragService.getWorkspaceConfig(workspace);
      // If the response has a `message` field, it means no config exists (global defaults)
      if ('message' in data && !(data as WorkspaceTenantConfig).llmModel) {
        // Reset all fields to empty
        setConfig(null);
        resetFields();
      } else {
        populateFromConfig(data as WorkspaceTenantConfig);
      }
    } catch {
      // Silently handle — show empty form
    } finally {
      setLoading(false);
    }
  }, [workspace, populateFromConfig, resetFields]);

  useEffect(() => {
    if (connected && workspace) {
      loadConfig();
    }
  }, [connected, workspace, loadConfig]);

  const buildUpdate = (): WorkspaceTenantConfigUpdate => {
    const update: WorkspaceTenantConfigUpdate = { workspace };
    if (llmModel) update.llmModel = llmModel;
    if (llmApiKey && !isMaskedKey(llmApiKey)) update.llmApiKey = llmApiKey;
    if (llmBaseUrl) update.llmBaseUrl = llmBaseUrl;
    if (llmTemperature !== '') update.llmTemperature = Number(llmTemperature);
    if (llmMaxTokens !== '') update.llmMaxTokens = Number(llmMaxTokens);
    if (embeddingModel) update.embeddingModel = embeddingModel;
    if (embeddingApiKey && !isMaskedKey(embeddingApiKey)) update.embeddingApiKey = embeddingApiKey;
    if (embeddingBaseUrl) update.embeddingBaseUrl = embeddingBaseUrl;
    if (embeddingDim !== '') update.embeddingDim = Number(embeddingDim);
    if (chunkSize !== '') update.chunkSize = Number(chunkSize);
    if (chunkOverlapSize !== '') update.chunkOverlapSize = Number(chunkOverlapSize);
    if (language) update.language = language;
    if (defaultTopK !== '') update.defaultTopK = Number(defaultTopK);
    update.rerankEnabled = rerankEnabled;
    return update;
  };

  const handleSave = async () => {
    setSaving(true);
    setMessage(null);
    try {
      const result = await ragService.updateWorkspaceConfig(buildUpdate());
      populateFromConfig(result);
      setMessage({ type: 'success', text: i18n('Workspace configuration saved successfully') });
    } catch (e) {
      setMessage({ type: 'error', text: e instanceof Error ? e.message : 'Failed to save' });
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    setResetting(true);
    setMessage(null);
    try {
      await ragService.resetWorkspaceConfig(workspace);
      resetFields();
      setConfig(null);
      setMessage({ type: 'success', text: i18n('Workspace configuration reset to defaults') });
    } catch (e) {
      setMessage({ type: 'error', text: e instanceof Error ? e.message : 'Failed to reset' });
    } finally {
      setResetting(false);
    }
  };

  if (!connected) {
    return (
      <div>
        <div className="flex items-center gap-2 mb-3">
          <Settings2 className="w-5 h-5 text-muted-foreground" />
          <h2 className="text-lg font-medium">{i18n('Workspace Configuration')}</h2>
        </div>
        <p className="text-sm text-muted-foreground">
          {i18n('Connect to EasyRAG to configure workspace-level LLM, Embedding, and retrieval settings.')}
        </p>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-32">
        <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center gap-2">
        <Settings2 className="w-5 h-5 text-muted-foreground" />
        <h2 className="text-lg font-medium">{i18n('Workspace Configuration')}</h2>
        <span className="text-xs text-muted-foreground font-mono bg-muted px-1.5 py-0.5 rounded">
          {workspace}
        </span>
      </div>
      <p className="text-sm text-muted-foreground">
        {i18n('Override LLM, Embedding, chunking, and retrieval settings for this workspace. Leave fields empty to use global defaults.')}
      </p>

      {/* LLM Settings */}
      <ConfigGroup
        icon={<Brain className="w-4 h-4" />}
        title={i18n('LLM Settings')}
      >
        <div className="space-y-3">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Model')}</label>
            <input
              type="text"
              value={llmModel}
              onChange={(e) => setLlmModel(e.target.value)}
              placeholder="gpt-4o"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('API Key')}</label>
            <div className="relative">
              <input
                type={showLlmKey ? 'text' : 'password'}
                value={isMaskedKey(llmApiKey) ? '' : llmApiKey}
                onChange={(e) => setLlmApiKey(e.target.value)}
                placeholder={config?.llmApiKey || i18n('Optional')}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 pr-9 text-sm"
              />
              <button
                type="button"
                onClick={() => setShowLlmKey(!showLlmKey)}
                className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                {showLlmKey ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Base URL')}</label>
            <input
              type="text"
              value={llmBaseUrl}
              onChange={(e) => setLlmBaseUrl(e.target.value)}
              placeholder="https://api.openai.com/v1"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Temperature')}</label>
              <input
                type="number"
                min={0}
                max={2}
                step={0.1}
                value={llmTemperature}
                onChange={(e) => setLlmTemperature(e.target.value === '' ? '' : Number(e.target.value))}
                placeholder="0.0"
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Max Tokens')}</label>
              <input
                type="number"
                min={1}
                value={llmMaxTokens}
                onChange={(e) => setLlmMaxTokens(e.target.value === '' ? '' : Number(e.target.value))}
                placeholder="4096"
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
          </div>
        </div>
      </ConfigGroup>

      {/* Embedding Settings */}
      <ConfigGroup
        icon={<FileText className="w-4 h-4" />}
        title={i18n('Embedding Settings')}
      >
        <div className="space-y-3">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Model')}</label>
            <input
              type="text"
              value={embeddingModel}
              onChange={(e) => setEmbeddingModel(e.target.value)}
              placeholder="text-embedding-3-small"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('API Key')}</label>
            <div className="relative">
              <input
                type={showEmbKey ? 'text' : 'password'}
                value={isMaskedKey(embeddingApiKey) ? '' : embeddingApiKey}
                onChange={(e) => setEmbeddingApiKey(e.target.value)}
                placeholder={config?.embeddingApiKey || i18n('Optional')}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 pr-9 text-sm"
              />
              <button
                type="button"
                onClick={() => setShowEmbKey(!showEmbKey)}
                className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                {showEmbKey ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Base URL')}</label>
            <input
              type="text"
              value={embeddingBaseUrl}
              onChange={(e) => setEmbeddingBaseUrl(e.target.value)}
              placeholder="https://api.openai.com/v1"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm font-mono"
            />
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Dimensions')}</label>
            <input
              type="number"
              min={1}
              value={embeddingDim}
              onChange={(e) => setEmbeddingDim(e.target.value === '' ? '' : Number(e.target.value))}
              placeholder="1536"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
        </div>
      </ConfigGroup>

      {/* Chunking Settings */}
      <ConfigGroup
        icon={<Scissors className="w-4 h-4" />}
        title={i18n('Chunking Settings')}
      >
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Chunk Size')}</label>
            <input
              type="number"
              min={100}
              value={chunkSize}
              onChange={(e) => setChunkSize(e.target.value === '' ? '' : Number(e.target.value))}
              placeholder="1200"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Overlap')}</label>
            <input
              type="number"
              min={0}
              value={chunkOverlapSize}
              onChange={(e) => setChunkOverlapSize(e.target.value === '' ? '' : Number(e.target.value))}
              placeholder="100"
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            />
          </div>
        </div>
      </ConfigGroup>

      {/* Query Settings */}
      <ConfigGroup
        icon={<Search className="w-4 h-4" />}
        title={i18n('Query Settings')}
      >
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Language')}</label>
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value)}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              >
                <option value="">{i18n('Default')}</option>
                <option value="English">English</option>
                <option value="Chinese">中文</option>
                <option value="Japanese">日本語</option>
              </select>
            </div>
            <div>
              <label className="text-sm font-medium mb-1 block">{i18n('Top K')}</label>
              <input
                type="number"
                min={1}
                max={100}
                value={defaultTopK}
                onChange={(e) => setDefaultTopK(e.target.value === '' ? '' : Number(e.target.value))}
                placeholder="40"
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              />
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm font-medium cursor-pointer">
            <input
              type="checkbox"
              checked={rerankEnabled}
              onChange={(e) => setRerankEnabled(e.target.checked)}
              className="w-4 h-4 rounded border-input"
            />
            {i18n('Enable Reranking')}
          </label>
        </div>
      </ConfigGroup>

      {/* Actions */}
      <div className="flex gap-3 pt-2">
        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 transition-colors"
        >
          {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
          {i18n('Save Workspace Config')}
        </button>
        <button
          onClick={handleReset}
          disabled={resetting}
          className="flex items-center gap-2 px-4 py-2 rounded-md border border-input text-sm font-medium hover:bg-muted disabled:opacity-50 transition-colors"
        >
          {resetting ? <Loader2 className="w-4 h-4 animate-spin" /> : <RotateCcw className="w-4 h-4" />}
          {i18n('Reset to Defaults')}
        </button>
      </div>

      {/* Message */}
      {message && (
        <div className={`flex items-center gap-2 p-3 rounded-md text-sm ${
          message.type === 'success'
            ? 'bg-green-500/10 text-green-500'
            : 'bg-destructive/10 text-destructive'
        }`}>
          {message.type === 'success' ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
          {message.text}
        </div>
      )}
    </div>
  );
};

/** Collapsible configuration group with icon and title. */
const ConfigGroup: React.FC<{
  icon: React.ReactNode;
  title: string;
  children: React.ReactNode;
}> = ({ icon, title, children }) => (
  <div className="p-4 rounded-lg border border-border space-y-1">
    <div className="flex items-center gap-2 mb-2">
      <span className="text-muted-foreground">{icon}</span>
      <h3 className="text-sm font-medium">{title}</h3>
    </div>
    {children}
  </div>
);
