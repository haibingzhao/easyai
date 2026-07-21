import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  RefreshCw, Plus, ChevronDown, ChevronRight,
  CheckCircle, XCircle, Loader2, AlertTriangle,
  Trash2, RotateCcw, Power, X, AlertCircle, Pencil, Check
} from 'lucide-react';
import type { McpServerDto, McpBulkImportRequest, McpPromptInfo } from '@/types/mcp';
import { mcpService } from '@/services/mcp-service';
import { ToolTooltip } from './ToolItem';

// ─── Status Badge ─────────────────────────────────────────────────────────────

interface StatusIconProps {
  status: string;
  onClick?: () => void;
  loading?: boolean;
  className?: string;
}

const StatusIcon: React.FC<StatusIconProps> = ({ status, onClick, loading, className = 'w-4 h-4' }) => {
  const isClickable = (status === 'disabled' || status === 'failed') && !loading;

  const icon = (() => {
    switch (status) {
      case 'connected':
        return <CheckCircle className={`${className} text-green-500`} />;
      case 'failed':
        return <XCircle className={`${className} text-destructive`} />;
      case 'connecting':
        return <Loader2 className={`${className} text-blue-500 animate-spin`} />;
      default:
        return <Power className={`${className} text-muted-foreground`} />;
    }
  })();

  if (isClickable && onClick) {
    return (
      <button
        title="点击重新连接"
        onClick={onClick}
        className="p-0.5 rounded hover:bg-muted transition-colors cursor-pointer"
      >
        {icon}
      </button>
    );
  }

  return icon;
};

// ─── Avatar ───────────────────────────────────────────────────────────────────

const ServerAvatar: React.FC<{ name: string }> = ({ name }) => {
  const letter = name.charAt(0).toUpperCase();
  const colors = [
    'bg-blue-500', 'bg-green-500', 'bg-purple-500', 'bg-orange-500',
    'bg-pink-500', 'bg-teal-500', 'bg-indigo-500', 'bg-red-500'
  ];
  const color = colors[name.charCodeAt(0) % colors.length];
  return (
    <div className={`w-8 h-8 rounded-lg ${color} flex items-center justify-center text-white text-sm font-semibold flex-shrink-0`}>
      {letter}
    </div>
  );
};

// ─── Tool List ────────────────────────────────────────────────────────────────

const ToolList: React.FC<{ tools: McpServerDto['tools'] }> = ({ tools }) => {
  if (!tools || tools.length === 0) {
    return <p className="text-xs text-muted-foreground py-2 px-3">No tools available</p>;
  }
  return (
    <div className="space-y-1">
      {tools.map(tool => (
        <ToolTooltip key={tool.name} name={tool.name} description={tool.description}>
          <div className="flex items-start gap-2 px-3 py-1.5 rounded-md hover:bg-muted/50 cursor-default">
            <span className="text-xs font-medium text-foreground min-w-0 flex-shrink-0">{tool.name}</span>
            <span className="text-xs text-muted-foreground truncate">{tool.description}</span>
          </div>
        </ToolTooltip>
      ))}
    </div>
  );
};

// ─── Prompt List ──────────────────────────────────────────────────────────────

const PromptList: React.FC<{ prompts: McpPromptInfo[] }> = ({ prompts }) => {
  if (prompts.length === 0) {
    return <p className="text-xs text-muted-foreground py-2 px-3">No prompts available</p>;
  }
  return (
    <div className="space-y-1">
      {prompts.map(prompt => (
        <div key={prompt.name} className="flex items-start gap-2 px-3 py-1.5 rounded-md hover:bg-muted/50">
          <span className="text-xs font-medium text-foreground min-w-0 flex-shrink-0" title={prompt.name}>{prompt.name}</span>
          <span className="text-xs text-muted-foreground truncate" title={prompt.description ?? undefined}>{prompt.description}</span>
          {prompt.arguments.length > 0 && (
            <span className="text-[10px] text-muted-foreground/60 shrink-0">
              ({prompt.arguments.map(a => a.name).join(', ')})
            </span>
          )}
        </div>
      ))}
    </div>
  );
};

// ─── Server Row ───────────────────────────────────────────────────────────────

interface ServerRowProps {
  server: McpServerDto;
  onToggleEnable: (server: McpServerDto) => void;
  onReconnect: (server: McpServerDto) => void;
  onDelete: (server: McpServerDto) => void;
  onUpdate: (server: McpServerDto, updates: Partial<McpServerDto>) => Promise<void>;
  loading?: boolean;
}

const ServerRow: React.FC<ServerRowProps> = ({ server, onToggleEnable, onReconnect, onDelete, onUpdate, loading }) => {
  const [expanded, setExpanded] = useState(false);
  const [editingTimeout, setEditingTimeout] = useState(false);
  const [timeoutValue, setTimeoutValue] = useState(String(server.timeoutSeconds ?? 120));
  const [savingTimeout, setSavingTimeout] = useState(false);

  const handleSaveTimeout = async () => {
    const parsed = parseInt(timeoutValue, 10);
    if (isNaN(parsed) || parsed < 10 || parsed > 3600) {
      return;
    }
    setSavingTimeout(true);
    try {
      await onUpdate(server, { timeoutSeconds: parsed });
      setEditingTimeout(false);
    } finally {
      setSavingTimeout(false);
    }
  };

  return (
    <div className="border border-border rounded-lg overflow-hidden">
      {/* Header Row */}
      <div className="flex items-center gap-3 px-3 py-2.5 hover:bg-muted/30 transition-colors">
        <ServerAvatar name={server.name} />

        <button
          className="flex items-center gap-1.5 flex-1 min-w-0 text-left"
          onClick={() => setExpanded(e => !e)}
        >
          <span className="text-sm font-medium truncate">{server.name}</span>
          <span className="text-xs text-muted-foreground flex-shrink-0 capitalize">({server.type})</span>
          {expanded
            ? <ChevronDown className="w-3.5 h-3.5 text-muted-foreground ml-auto flex-shrink-0" />
            : <ChevronRight className="w-3.5 h-3.5 text-muted-foreground ml-auto flex-shrink-0" />}
        </button>

        {/* Status */}
        <StatusIcon
          status={server.status}
          onClick={() => onReconnect(server)}
          loading={loading}
        />

        {/* Tool count badge */}
        {server.status === 'connected' && server.tools?.length > 0 && (
          <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded-full flex-shrink-0">
            {server.tools.length}
          </span>
        )}

        {/* Actions */}
        <div className="flex items-center gap-1 flex-shrink-0">
          {server.status === 'failed' && (
            <button
              title="Reconnect"
              onClick={() => onReconnect(server)}
              disabled={loading}
              className="p-1 rounded hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
            >
              <RotateCcw className="w-3.5 h-3.5" />
            </button>
          )}
          <button
            title="Delete"
            onClick={() => onDelete(server)}
            disabled={loading}
            className="p-1 rounded hover:bg-muted transition-colors text-muted-foreground hover:text-destructive"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
          {/* Toggle */}
          <button
            title={server.enabled ? 'Disable' : 'Enable'}
            onClick={() => onToggleEnable(server)}
            disabled={loading}
            className={`relative w-9 h-5 rounded-full transition-colors flex-shrink-0 ${
              server.enabled ? 'bg-green-500' : 'bg-input'
            }`}
          >
            <span className={`absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${
              server.enabled ? 'translate-x-4' : 'translate-x-0'
            }`} />
          </button>
        </div>
      </div>

      {/* Error message */}
      {server.status === 'failed' && server.error && (
        <div className="px-3 py-2 bg-destructive/10 border-t border-border flex items-start gap-2">
          <AlertTriangle className="w-3.5 h-3.5 text-destructive mt-0.5 flex-shrink-0" />
          <p className="text-xs text-destructive break-all">{server.error}</p>
        </div>
      )}

      {/* Tool List (expanded) */}
      {expanded && (
        <div className="border-t border-border bg-muted/20 py-1">
          {/* Config info */}
          <div className="px-3 py-1.5 space-y-1 text-xs text-muted-foreground">
            {server.command && server.command.length > 0 && (
              <div className="flex items-center gap-2">
                <span className="flex-shrink-0">Command:</span>
                <code className="text-foreground/80 font-mono truncate">{server.command.join(' ')}</code>
              </div>
            )}
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-1.5">
              <span>Timeout:</span>
              {editingTimeout ? (
                <div className="flex items-center gap-1">
                  <input
                    type="number"
                    min={10}
                    max={3600}
                    value={timeoutValue}
                    onChange={e => setTimeoutValue(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter') handleSaveTimeout();
                      if (e.key === 'Escape') { setEditingTimeout(false); setTimeoutValue(String(server.timeoutSeconds ?? 120)); }
                    }}
                    className="w-16 px-1.5 py-0.5 text-xs bg-background border border-input rounded focus:outline-none focus:ring-1 focus:ring-ring"
                    autoFocus
                    disabled={savingTimeout}
                  />
                  <span>s</span>
                  <button
                    title="Save"
                    onClick={handleSaveTimeout}
                    disabled={savingTimeout}
                    className="p-0.5 rounded hover:bg-muted text-green-500"
                  >
                    {savingTimeout ? <Loader2 className="w-3 h-3 animate-spin" /> : <Check className="w-3 h-3" />}
                  </button>
                  <button
                    title="Cancel"
                    onClick={() => { setEditingTimeout(false); setTimeoutValue(String(server.timeoutSeconds ?? 120)); }}
                    className="p-0.5 rounded hover:bg-muted text-muted-foreground"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
              ) : (
                <div className="flex items-center gap-1">
                  <span>{server.timeoutSeconds ?? 120}s</span>
                  <button
                    title="Edit timeout"
                    onClick={() => { setTimeoutValue(String(server.timeoutSeconds ?? 120)); setEditingTimeout(true); }}
                    className="p-0.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
                  >
                    <Pencil className="w-3 h-3" />
                  </button>
                </div>
              )}
            </div>
            {server.url && <span className="truncate">{server.url}</span>}
            </div>
          </div>
          <ToolList tools={server.tools ?? []} />
          {server.prompts && server.prompts.length > 0 && (
            <>
              <div className="px-3 pt-2 pb-1">
                <span className="text-[10px] uppercase tracking-wider text-muted-foreground font-medium">Prompts</span>
              </div>
              <PromptList prompts={server.prompts} />
            </>
          )}
        </div>
      )}
    </div>
  );
};

// ─── Import Modal ─────────────────────────────────────────────────────────────

interface ImportModalProps {
  open: boolean;
  onClose: () => void;
  onImport: (json: string) => Promise<void>;
  importing: boolean;
}

const EXAMPLE_JSON = JSON.stringify({
  mcpServers: {
    "example-server": {
      command: ["npx", "-y", "mcp-server-example"]
    },
    "github": {
      command: ["npx", "-y", "@modelcontextprotocol/server-github"],
      env: { "GITHUB_PERSONAL_ACCESS_TOKEN": "your-token-here" },
      timeoutSeconds: 120
    }
  }
}, null, 2);

const ImportModal: React.FC<ImportModalProps> = ({ open, onClose, onImport, importing }) => {
  const [json, setJson] = useState(EXAMPLE_JSON);
  const [error, setError] = useState('');

  const handleImport = async () => {
    setError('');
    try {
      JSON.parse(json); // validate first
      await onImport(json);
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Invalid JSON');
    }
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative bg-background border border-border rounded-xl shadow-xl w-full max-w-lg mx-4 p-5 space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-base font-semibold">手动配置 MCP 服务器</h3>
          <button onClick={onClose} className="p-1 rounded hover:bg-muted">
            <X className="w-4 h-4" />
          </button>
        </div>

        <p className="text-xs text-muted-foreground">
          粘贴 MCP 服务器 JSON 配置（兼容 Claude Desktop / Cursor 格式）
        </p>

        <textarea
          value={json}
          onChange={e => setJson(e.target.value)}
          className="w-full h-56 p-3 text-xs font-mono bg-muted/30 border border-input rounded-lg resize-none focus:outline-none focus:ring-1 focus:ring-ring"
          spellCheck={false}
        />

        {error && (
          <div className="flex items-center gap-2 text-destructive text-xs">
            <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <div className="flex items-center gap-2 p-2.5 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-600 flex-shrink-0" />
          <p className="text-xs text-amber-700 dark:text-amber-400">
            配置前请确认来源，甄别风险。MCP 服务器可以访问您的文件和网络。
          </p>
        </div>

        <div className="flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-3 py-1.5 text-sm rounded-md hover:bg-muted border border-input"
          >
            取消
          </button>
          <button
            onClick={handleImport}
            disabled={importing}
            className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 flex items-center gap-1.5"
          >
            {importing && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            导入
          </button>
        </div>
      </div>
    </div>
  );
};

// ─── Main Component ───────────────────────────────────────────────────────────

export const McpServerManager: React.FC = () => {
  const [servers, setServers] = useState<McpServerDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingName, setLoadingName] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [showImport, setShowImport] = useState(false);
  const [importing, setImporting] = useState(false);

  // Auto-refresh every 30 seconds
  const refreshTimerRef = useRef<ReturnType<typeof setInterval>>();

  const loadServers = useCallback(async () => {
    try {
      setLoading(true);
      setError('');
      const data = await mcpService.listServers();
      setServers(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load MCP servers');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadServers();
    refreshTimerRef.current = setInterval(loadServers, 30_000);
    return () => clearInterval(refreshTimerRef.current);
  }, [loadServers]);

  const handleToggleEnable = async (server: McpServerDto) => {
    setLoadingName(server.name);
    try {
      const updated = await mcpService.updateServer(server.name, {
        ...server,
        enabled: !server.enabled,
      });
      setServers(prev => prev.map(s => s.name === server.name ? updated : s));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to update server');
    } finally {
      setLoadingName(null);
    }
  };

  const handleReconnect = async (server: McpServerDto) => {
    setLoadingName(server.name);
    try {
      const updated = await mcpService.connectServer(server.name);
      setServers(prev => prev.map(s => s.name === server.name ? updated : s));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to reconnect');
    } finally {
      setLoadingName(null);
    }
  };

  const handleDelete = async (server: McpServerDto) => {
    if (!window.confirm(`删除 MCP 服务器 "${server.name}"？`)) return;
    setLoadingName(server.name);
    try {
      await mcpService.deleteServer(server.name);
      setServers(prev => prev.filter(s => s.name !== server.name));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to delete server');
    } finally {
      setLoadingName(null);
    }
  };

  const handleUpdate = async (server: McpServerDto, updates: Partial<McpServerDto>) => {
    setLoadingName(server.name);
    try {
      const updated = await mcpService.updateServer(server.name, {
        ...server,
        ...updates,
      });
      setServers(prev => prev.map(s => s.name === server.name ? updated : s));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to update server');
    } finally {
      setLoadingName(null);
    }
  };

  const handleImport = async (jsonStr: string) => {
    setImporting(true);
    try {
      const parsed = JSON.parse(jsonStr);
      // Smart-detect format: wrap bare server configs in mcpServers if needed
      let req: McpBulkImportRequest;
      if (parsed.mcpServers && typeof parsed.mcpServers === 'object') {
        req = parsed as McpBulkImportRequest;
      } else {
        // User pasted a single server config or a flat map of servers
        // Check if it looks like a single server (has command/args/url)
        if (parsed.command || parsed.args || parsed.url) {
          req = { mcpServers: { 'imported-server': parsed } };
        } else {
          // Assume it's a flat map of server name -> config
          req = { mcpServers: parsed };
        }
      }
      await mcpService.bulkImport(req);
      // Reload full list to reflect state
      await loadServers();
    } finally {
      setImporting(false);
    }
  };

  const connectedCount = servers.filter(s => s.status === 'connected').length;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          {loading && <Loader2 className="w-4 h-4 animate-spin text-muted-foreground" />}
          {connectedCount > 0 && (
            <span className="text-xs text-muted-foreground">
              {connectedCount} connected
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={loadServers}
            disabled={loading}
            title="Refresh"
            className="p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={() => setShowImport(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            添加
          </button>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="flex items-center gap-2 p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          <span>{error}</span>
          <button onClick={() => setError('')} className="ml-auto">
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}

      {/* Server List */}
      {servers.length === 0 && !loading ? (
        <div className="flex flex-col items-center justify-center py-12 text-center">
          <div className="w-12 h-12 rounded-full bg-muted flex items-center justify-center mb-3">
            <Plus className="w-6 h-6 text-muted-foreground" />
          </div>
          <p className="text-sm font-medium">还没有 MCP 服务器</p>
          <p className="text-xs text-muted-foreground mt-1">点击「添加」配置 MCP 服务器以扩展 Agent 能力</p>
        </div>
      ) : (
        <div className="space-y-2">
          {servers.map(server => (
            <ServerRow
              key={server.name}
              server={server}
              onToggleEnable={handleToggleEnable}
              onReconnect={handleReconnect}
              onDelete={handleDelete}
              onUpdate={handleUpdate}
              loading={loadingName === server.name}
            />
          ))}
        </div>
      )}

      {/* Import Modal */}
      <ImportModal
        open={showImport}
        onClose={() => setShowImport(false)}
        onImport={handleImport}
        importing={importing}
      />
    </div>
  );
};

// Keep backward-compatible export
export const McpServerPlaceholder = McpServerManager;