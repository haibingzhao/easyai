import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  RefreshCw, Plus, ChevronDown, ChevronRight,
  CheckCircle, XCircle, Loader2, AlertTriangle,
  Trash2, RotateCcw, Power, X, AlertCircle, Pencil, Check,
  Eye, EyeOff
} from 'lucide-react';
import type { McpServerDto, McpBulkImportRequest, McpPromptInfo } from '@/types/mcp';
import { mcpService, mcpServerKey, mcpServerScope, changedMcpServerFields } from '@/services/mcp-service';
import { useAuthStore } from '@/services/stores/auth-store';
import { useCapabilityStore, selectCanManage } from '@/services/stores/capability-store';
import { resolveWriteScope } from '@/services/api-client';
import type { AssetScope } from '@/services/api-client';
import { i18n } from '@/utils/i18n';
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
  /** The row belongs to the active group bucket rather than the caller's personal one. */
  shared: boolean;
  /** Shared rows are toggleable only by the group owner. */
  canManage: boolean;
  onToggleEnable: (server: McpServerDto) => void;
  onReconnect: (server: McpServerDto) => void;
  onDelete: (server: McpServerDto) => void;
  onEdit: (server: McpServerDto) => void;
  onUpdate: (server: McpServerDto, updates: Partial<McpServerDto>) => Promise<void>;
  loading?: boolean;
}

const ServerRow: React.FC<ServerRowProps> = ({
  server, shared, canManage, onToggleEnable, onReconnect, onDelete, onEdit, onUpdate, loading
}) => {
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
    } catch {
      // Keep the editor open on failure; the page-level banner already shows why the save failed.
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
          {shared && (
            <span
              className="text-[10px] px-1.5 py-0.5 rounded-full bg-primary/10 text-primary flex-shrink-0"
              title={i18n('Only the group owner can manage a shared server')}
            >
              {i18n('Group server')}
            </span>
          )}
          {expanded
            ? <ChevronDown className="w-3.5 h-3.5 text-muted-foreground ml-auto flex-shrink-0" />
            : <ChevronRight className="w-3.5 h-3.5 text-muted-foreground ml-auto flex-shrink-0" />}
        </button>

        {/* Status */}
        <StatusIcon
          status={server.status}
          onClick={canManage ? () => onReconnect(server) : undefined}
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
          {server.status === 'failed' && canManage && (
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
            title={canManage ? 'Edit' : i18n('Only the group owner can manage a shared server')}
            onClick={() => onEdit(server)}
            disabled={loading || !canManage}
            className="p-1 rounded hover:bg-muted transition-colors text-muted-foreground hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <Pencil className="w-3.5 h-3.5" />
          </button>
          <button
            title={canManage ? 'Delete' : i18n('Only the group owner can manage a shared server')}
            onClick={() => onDelete(server)}
            disabled={loading || !canManage}
            className="p-1 rounded hover:bg-muted transition-colors text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
          {/* Toggle */}
          <button
            title={!canManage
              ? i18n('Only the group owner can manage a shared server')
              : server.enabled ? 'Disable' : 'Enable'}
            onClick={() => onToggleEnable(server)}
            disabled={loading || !canManage}
            className={`relative w-9 h-5 rounded-full transition-colors flex-shrink-0 disabled:opacity-40 disabled:cursor-not-allowed ${
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
                  {canManage && (
                    <button
                      title="Edit timeout"
                      onClick={() => { setTimeoutValue(String(server.timeoutSeconds ?? 120)); setEditingTimeout(true); }}
                      className="p-0.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
                    >
                      <Pencil className="w-3 h-3" />
                    </button>
                  )}
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

// ─── Key/Value Editor ─────────────────────────────────────────────────────────

interface KeyValueRow {
  key: string;
  value: string;
}

const toKeyValueRows = (map?: Record<string, string>): KeyValueRow[] =>
  Object.entries(map ?? {}).map(([key, value]) => ({ key, value }));

const rowsToMap = (rows: KeyValueRow[]): Record<string, string> => {
  const map: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key) map[key] = row.value;
  }
  return map;
};


interface KeyValueEditorProps {
  rows: KeyValueRow[];
  onChange: (rows: KeyValueRow[]) => void;
  keyPlaceholder: string;
  valuePlaceholder: string;
  emptyText: string;
  disabled?: boolean;
}

const KeyValueEditor: React.FC<KeyValueEditorProps> = ({
  rows, onChange, keyPlaceholder, valuePlaceholder, emptyText, disabled
}) => {
  const [showSecrets, setShowSecrets] = useState(false);

  const updateRow = (index: number, field: keyof KeyValueRow, value: string) => {
    onChange(rows.map((row, i) => (i === index ? { ...row, [field]: value } : row)));
  };

  return (
    <div className="space-y-1.5">
      {rows.length === 0 ? (
        <p className="text-xs text-muted-foreground">{emptyText}</p>
      ) : (
        <div className="space-y-1.5 max-h-40 overflow-y-auto">
          {rows.map((row, index) => (
            <div key={index} className="flex items-center gap-1.5">
              <input
                value={row.key}
                onChange={e => updateRow(index, 'key', e.target.value)}
                placeholder={keyPlaceholder}
                className="w-40 px-2 py-1.5 text-xs font-mono bg-muted/30 border border-input rounded-md focus:outline-none focus:ring-1 focus:ring-ring"
                spellCheck={false}
                disabled={disabled}
              />
              <input
                value={row.value}
                onChange={e => updateRow(index, 'value', e.target.value)}
                placeholder={valuePlaceholder}
                type={showSecrets ? 'text' : 'password'}
                autoComplete="off"
                className="flex-1 min-w-0 px-2 py-1.5 text-xs font-mono bg-muted/30 border border-input rounded-md focus:outline-none focus:ring-1 focus:ring-ring"
                spellCheck={false}
                disabled={disabled}
              />
              <button
                title="Remove"
                onClick={() => onChange(rows.filter((_, i) => i !== index))}
                disabled={disabled}
                className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-destructive flex-shrink-0 disabled:opacity-50"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}
      {rows.length > 0 && (
        <button
          onClick={() => setShowSecrets(s => !s)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          {showSecrets ? <EyeOff className="w-3 h-3" /> : <Eye className="w-3 h-3" />}
          {showSecrets ? '隐藏' : '显示'}内容
        </button>
      )}
    </div>
  );
};

// ─── Add Modal ────────────────────────────────────────────────────────────────

type AddMode = 'form' | 'json';
type ServerType = 'remote' | 'local';

const EXAMPLE_JSON = JSON.stringify({
  mcpServers: {
    "example-server": {
      command: ["npx", "-y", "mcp-server-example"]
    },
    "dashscope-search": {
      url: "https://dashscope.aliyuncs.com/api/v1/mcps/EnhancedSearch/mcp",
      headers: { "Authorization": "Bearer sk-xxx" },
      timeoutSeconds: 120
    }
  }
}, null, 2);

interface RawImportEntry {
  command?: unknown;
  args?: unknown;
  env?: unknown;
  headers?: unknown;
  url?: unknown;
  timeoutSeconds?: unknown;
}

const toStringMap = (value: unknown): Record<string, string> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const map: Record<string, string> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (typeof entry === 'string') map[key] = entry;
  }
  return map;
};

const toCommandLines = (entry: RawImportEntry): string[] => {
  const base = typeof entry.command === 'string'
    ? [entry.command]
    : Array.isArray(entry.command)
      ? entry.command.filter((part): part is string => typeof part === 'string')
      : [];
  const args = Array.isArray(entry.args)
    ? entry.args.filter((part): part is string => typeof part === 'string')
    : [];
  return [...base, ...args];
};

interface ParsedSingleServer {
  name: string;
  entry: RawImportEntry;
}

const parseSingleServer = (raw: string): ParsedSingleServer | string => {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return 'JSON 解析失败';
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return 'JSON 必须是一个对象';
  }
  const root = parsed as Record<string, unknown>;
  const rawMap = root.mcpServers;
  const map: Record<string, unknown> =
    rawMap && typeof rawMap === 'object' && !Array.isArray(rawMap)
      ? rawMap as Record<string, unknown>
      : (root.command !== undefined || root.url !== undefined)
        ? { 'imported-server': root }
        : root;
  const names = Object.keys(map);
  if (names.length === 0) return 'JSON 中没有服务器配置';
  if (names.length > 1) return 'JSON 中有多个服务器，表单模式仅支持单个；请直接在 JSON 模式导入';
  const rawEntry = map[names[0]];
  if (!rawEntry || typeof rawEntry !== 'object' || Array.isArray(rawEntry)) {
    return '服务器配置必须是一个对象';
  }
  return { name: names[0], entry: rawEntry as RawImportEntry };
};

interface AddServerModalProps {
  open: boolean;
  onClose: () => void;
  /** `scope` names the bucket explicitly instead of relying on the host's default write scope. */
  onImport: (json: string, scope: AssetScope) => Promise<void>;
  importing: boolean;
  /** Whether this login may create servers in the group bucket. */
  groupAvailable: boolean;
  defaultScope: AssetScope;
}

const AddServerModalForm: React.FC<Omit<AddServerModalProps, 'open'>> = ({
  onClose, onImport, importing, groupAvailable, defaultScope
}) => {
  const [mode, setMode] = useState<AddMode>('form');
  const [scope, setScope] = useState<AssetScope>(defaultScope);
  const [name, setName] = useState('');
  const [type, setType] = useState<ServerType>('remote');
  const [url, setUrl] = useState('');
  const [headerRows, setHeaderRows] = useState<KeyValueRow[]>([]);
  const [commandLines, setCommandLines] = useState<string[]>(['']);
  const [envRows, setEnvRows] = useState<KeyValueRow[]>([]);
  const [timeoutValue, setTimeoutValue] = useState('120');
  const [json, setJson] = useState(EXAMPLE_JSON);
  const [error, setError] = useState('');

  const isRemote = type === 'remote';

  const updateCommandLine = (index: number, value: string) => {
    setCommandLines(lines => lines.map((line, i) => (i === index ? value : line)));
  };

  const buildEntry = () => {
    const parsed = parseInt(timeoutValue, 10);
    const timeout = Number.isNaN(parsed) ? 120 : parsed;
    return isRemote
      ? { url: url.trim(), headers: rowsToMap(headerRows), timeoutSeconds: timeout }
      : {
          command: commandLines.map(line => line.trim()).filter(line => line.length > 0),
          env: rowsToMap(envRows),
          timeoutSeconds: timeout,
        };
  };

  const applyParsed = (parsed: ParsedSingleServer) => {
    setName(parsed.name);
    const remote = parsed.entry.url !== undefined;
    setType(remote ? 'remote' : 'local');
    setUrl(typeof parsed.entry.url === 'string' ? parsed.entry.url : '');
    setHeaderRows(toKeyValueRows(toStringMap(parsed.entry.headers)));
    setEnvRows(toKeyValueRows(toStringMap(parsed.entry.env)));
    const lines = toCommandLines(parsed.entry);
    setCommandLines(lines.length > 0 ? lines : ['']);
    setTimeoutValue(typeof parsed.entry.timeoutSeconds === 'number' ? String(parsed.entry.timeoutSeconds) : '120');
  };

  const formDirty =
    name.trim().length > 0 ||
    url.trim().length > 0 ||
    commandLines.some(line => line.trim().length > 0) ||
    headerRows.length > 0 ||
    envRows.length > 0;

  const switchMode = (next: AddMode) => {
    if (next === mode) return;
    setError('');
    if (next === 'json') {
      // Regenerate from the form unless the user already typed a JSON draft. Keeping the untouched
      // two-server example would make the next JSON → form switch fail on its own sample config.
      if (formDirty || json === EXAMPLE_JSON) {
        setJson(JSON.stringify({ mcpServers: { [name.trim() || 'new-server']: buildEntry() } }, null, 2));
      }
      setMode('json');
      return;
    }
    const parsed = parseSingleServer(json);
    if (typeof parsed === 'string') {
      setError(parsed);
      return;
    }
    applyParsed(parsed);
    setMode('form');
  };

  const handleSubmit = async () => {
    setError('');
    if (mode === 'json') {
      try {
        JSON.parse(json);
      } catch {
        setError('JSON 解析失败');
        return;
      }
      try {
        await onImport(json, scope);
        onClose();
      } catch (e: unknown) {
        setError(e instanceof Error ? e.message : '导入失败');
      }
      return;
    }

    const trimmedName = name.trim();
    if (!trimmedName) {
      setError('服务器名称不能为空');
      return;
    }
    const timeout = parseInt(timeoutValue, 10);
    if (isNaN(timeout) || timeout < 10 || timeout > 3600) {
      setError('超时时间必须在 10–3600 秒之间');
      return;
    }
    if (isRemote && !url.trim()) {
      setError('URL 不能为空');
      return;
    }
    if (!isRemote && commandLines.every(line => line.trim().length === 0)) {
      setError('命令不能为空');
      return;
    }

    try {
      await onImport(JSON.stringify({ mcpServers: { [trimmedName]: buildEntry() } }), scope);
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '添加失败');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={importing ? undefined : onClose} />
      <div className="relative bg-background border border-border rounded-xl shadow-xl w-full max-w-lg mx-4 p-5 space-y-4 max-h-[calc(100vh-2rem)] overflow-y-auto">
        <div className="flex items-center justify-between">
          <h3 className="text-base font-semibold">添加 MCP 服务器</h3>
          <button onClick={onClose} disabled={importing} className="p-1 rounded hover:bg-muted disabled:opacity-50">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="flex items-center gap-1 p-0.5 bg-muted rounded-lg w-fit">
          {(['form', 'json'] as const).map(m => (
            <button
              key={m}
              onClick={() => switchMode(m)}
              disabled={importing}
              className={`px-3 py-1 text-xs rounded-md transition-colors disabled:opacity-50 ${
                mode === m
                  ? 'bg-background text-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              {m === 'form' ? '表单' : 'JSON'}
            </button>
          ))}
        </div>

        {groupAvailable && (
          <div className="space-y-1.5">
            <label className="text-xs font-medium">保存到</label>
            <div className="flex items-center gap-1 p-0.5 bg-muted rounded-lg w-fit">
              {(['personal', 'group'] as const).map(option => (
                <button
                  key={option}
                  onClick={() => setScope(option)}
                  disabled={importing}
                  className={`px-3 py-1 text-xs rounded-md transition-colors disabled:opacity-50 ${
                    scope === option
                      ? 'bg-background text-foreground shadow-sm'
                      : 'text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {option === 'personal' ? '个人' : '群组共享'}
                </button>
              ))}
            </div>
            <p className="text-[11px] text-muted-foreground">
              {scope === 'group'
                ? '群组服务器对所有成员可见，仅群组拥有者可修改。'
                : '个人服务器仅自己可见。'}
            </p>
          </div>
        )}

        {mode === 'form' ? (
          <>
            <div className="space-y-1.5">
              <label className="text-xs font-medium">名称</label>
              <input
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="例如 dashscope-search"
                className="w-full px-3 py-2 text-sm bg-muted/30 border border-input rounded-lg focus:outline-none focus:ring-1 focus:ring-ring"
                spellCheck={false}
                disabled={importing}
              />
              <p className="text-[11px] text-muted-foreground">同名服务器会被覆盖并立即重连</p>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium">类型</label>
              <div className="flex items-center gap-1 p-0.5 bg-muted rounded-lg w-fit">
                {(['remote', 'local'] as const).map(t => (
                  <button
                    key={t}
                    onClick={() => setType(t)}
                    disabled={importing}
                    className={`px-3 py-1 text-xs rounded-md transition-colors disabled:opacity-50 ${
                      type === t
                        ? 'bg-background text-foreground shadow-sm'
                        : 'text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    {t === 'remote' ? 'Remote' : 'Local'}
                  </button>
                ))}
              </div>
            </div>

            {isRemote && (
              <div className="space-y-1.5">
                <label className="text-xs font-medium">URL</label>
                <input
                  value={url}
                  onChange={e => setUrl(e.target.value)}
                  placeholder="https://example.com/mcp"
                  className="w-full px-3 py-2 text-sm font-mono bg-muted/30 border border-input rounded-lg focus:outline-none focus:ring-1 focus:ring-ring"
                  spellCheck={false}
                  disabled={importing}
                />
              </div>
            )}

            {isRemote && (
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-medium">请求头</label>
                  <button
                    onClick={() => setHeaderRows(rows => [...rows, { key: '', value: '' }])}
                    disabled={importing}
                    className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
                  >
                    <Plus className="w-3 h-3" /> 添加
                  </button>
                </div>
                <KeyValueEditor
                  rows={headerRows}
                  onChange={setHeaderRows}
                  keyPlaceholder="Header"
                  valuePlaceholder="Value"
                  emptyText="未设置请求头"
                  disabled={importing}
                />
              </div>
            )}

            {!isRemote && (
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-medium">命令</label>
                  <button
                    onClick={() => setCommandLines(lines => [...lines, ''])}
                    disabled={importing}
                    className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
                  >
                    <Plus className="w-3 h-3" /> 添加参数
                  </button>
                </div>
                {commandLines.map((line, index) => (
                  <div key={index} className="flex items-center gap-1.5">
                    <input
                      value={line}
                      onChange={e => updateCommandLine(index, e.target.value)}
                      placeholder={index === 0 ? '可执行文件（如 npx）' : '参数'}
                      className="flex-1 min-w-0 px-2 py-1.5 text-xs font-mono bg-muted/30 border border-input rounded-md focus:outline-none focus:ring-1 focus:ring-ring"
                      spellCheck={false}
                      disabled={importing}
                    />
                    <button
                      title="Remove"
                      onClick={() => setCommandLines(lines => lines.filter((_, i) => i !== index))}
                      disabled={importing}
                      className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-destructive flex-shrink-0 disabled:opacity-50"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
                <p className="text-[11px] text-muted-foreground">每个参数一行，第一行为可执行文件</p>
              </div>
            )}

            {!isRemote && (
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-medium">环境变量</label>
                  <button
                    onClick={() => setEnvRows(rows => [...rows, { key: '', value: '' }])}
                    disabled={importing}
                    className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
                  >
                    <Plus className="w-3 h-3" /> 添加
                  </button>
                </div>
                <KeyValueEditor
                  rows={envRows}
                  onChange={setEnvRows}
                  keyPlaceholder="NAME"
                  valuePlaceholder="Value"
                  emptyText="未设置环境变量"
                  disabled={importing}
                />
              </div>
            )}

            <div className="space-y-1.5">
              <label className="text-xs font-medium">超时时间（秒）</label>
              <input
                type="number"
                min={10}
                max={3600}
                value={timeoutValue}
                onChange={e => setTimeoutValue(e.target.value)}
                className="w-28 px-2 py-1.5 text-sm bg-muted/30 border border-input rounded-md focus:outline-none focus:ring-1 focus:ring-ring"
                disabled={importing}
              />
            </div>
          </>
        ) : (
          <>
            <p className="text-xs text-muted-foreground">
              粘贴 MCP 服务器 JSON 配置（兼容 Claude Desktop / Cursor 格式，支持多个服务器）
            </p>
            <textarea
              value={json}
              onChange={e => setJson(e.target.value)}
              className="w-full h-64 p-3 text-xs font-mono bg-muted/30 border border-input rounded-lg resize-none focus:outline-none focus:ring-1 focus:ring-ring"
              spellCheck={false}
              disabled={importing}
            />
          </>
        )}

        {error && (
          <div className="flex items-center gap-2 text-destructive text-xs">
            <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
            <span className="break-all">{error}</span>
          </div>
        )}

        <div className="flex items-center gap-2 p-2.5 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-600 flex-shrink-0" />
          <p className="text-xs text-amber-700 dark:text-amber-400">
            配置前请确认来源，甄别风险。{mode === 'form' ? (isRemote ? '请求头' : '环境变量') : '请求头与环境变量'}将以明文保存，添加后立即尝试连接。
          </p>
        </div>

        <div className="flex justify-end gap-2">
          <button
            onClick={onClose}
            disabled={importing}
            className="px-3 py-1.5 text-sm rounded-md hover:bg-muted border border-input disabled:opacity-50"
          >
            取消
          </button>
          <button
            onClick={handleSubmit}
            disabled={importing}
            className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 flex items-center gap-1.5"
          >
            {importing && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            {mode === 'form' ? '添加' : '导入'}
          </button>
        </div>
      </div>
    </div>
  );
};

const AddServerModal: React.FC<AddServerModalProps> = ({ open, ...props }) => {
  if (!open) return null;
  return <AddServerModalForm {...props} />;
};

// ─── Edit Modal ───────────────────────────────────────────────────────────────

interface EditModalFormProps {
  server: McpServerDto;
  onClose: () => void;
  onSave: (server: McpServerDto, updates: Partial<McpServerDto>) => Promise<void>;
}

const EditModalForm: React.FC<EditModalFormProps> = ({ server, onClose, onSave }) => {
  const isRemote = server.type === 'remote';
  const [url, setUrl] = useState(server.url ?? '');
  const [commandLines, setCommandLines] = useState<string[]>(server.command ?? []);
  const [headerRows, setHeaderRows] = useState<KeyValueRow[]>(toKeyValueRows(server.headers));
  const [envRows, setEnvRows] = useState<KeyValueRow[]>(toKeyValueRows(server.env));
  const [timeoutValue, setTimeoutValue] = useState(String(server.timeoutSeconds ?? 120));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const updateCommandLine = (index: number, value: string) => {
    setCommandLines(lines => lines.map((line, i) => (i === index ? value : line)));
  };

  const handleSave = async () => {
    setError('');
    const parsedTimeout = parseInt(timeoutValue, 10);
    if (isNaN(parsedTimeout) || parsedTimeout < 10 || parsedTimeout > 3600) {
      setError('超时时间必须在 10–3600 秒之间');
      return;
    }
    const command = commandLines.map(line => line.trim()).filter(line => line.length > 0);
    if (!isRemote && command.length === 0) {
      setError('命令不能为空');
      return;
    }
    if (isRemote && !url.trim()) {
      setError('URL 不能为空');
      return;
    }

    // Send only fields the user actually changed. A full-row write would overwrite values another
    // client refreshed while this modal was open, even though the user never touched those fields.
    const changes = changedMcpServerFields(server, {
      url,
      headers: rowsToMap(headerRows),
      command,
      env: rowsToMap(envRows),
      timeoutSeconds: parsedTimeout,
    });

    if (Object.keys(changes).length === 0) {
      onClose();
      return;
    }

    setSaving(true);
    try {
      await onSave(server, changes);
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={saving ? undefined : onClose} />
      <div className="relative bg-background border border-border rounded-xl shadow-xl w-full max-w-lg mx-4 p-5 space-y-4 max-h-[calc(100vh-2rem)] overflow-y-auto">
        <div className="flex items-center justify-between">
          <h3 className="text-base font-semibold">编辑 MCP 服务器</h3>
          <button onClick={onClose} disabled={saving} className="p-1 rounded hover:bg-muted disabled:opacity-50">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="text-sm">
          <span className="font-medium">{server.name}</span>
          <span className="text-xs text-muted-foreground ml-2">({server.type})</span>
        </div>

        {isRemote && (
          <div className="space-y-1.5">
            <label className="text-xs font-medium">URL</label>
            <input
              value={url}
              onChange={e => setUrl(e.target.value)}
              className="w-full px-3 py-2 text-sm font-mono bg-muted/30 border border-input rounded-lg focus:outline-none focus:ring-1 focus:ring-ring"
              spellCheck={false}
              disabled={saving}
            />
          </div>
        )}

        {isRemote && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium">请求头</label>
              <button
                onClick={() => setHeaderRows(rows => [...rows, { key: '', value: '' }])}
                disabled={saving}
                className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
              >
                <Plus className="w-3 h-3" /> 添加
              </button>
            </div>
            <KeyValueEditor
              rows={headerRows}
              onChange={setHeaderRows}
              keyPlaceholder="Header"
              valuePlaceholder="Value"
              emptyText="未设置请求头"
              disabled={saving}
            />
          </div>
        )}

        {!isRemote && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium">命令</label>
              <button
                onClick={() => setCommandLines(lines => [...lines, ''])}
                disabled={saving}
                className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
              >
                <Plus className="w-3 h-3" /> 添加参数
              </button>
            </div>
            {commandLines.length === 0 ? (
              <p className="text-xs text-muted-foreground">未设置命令</p>
            ) : (
              <div className="space-y-1.5 max-h-40 overflow-y-auto">
                {commandLines.map((line, index) => (
                  <div key={index} className="flex items-center gap-1.5">
                    <input
                      value={line}
                      onChange={e => updateCommandLine(index, e.target.value)}
                      placeholder={index === 0 ? '可执行文件（如 npx）' : '参数'}
                      className="flex-1 min-w-0 px-2 py-1.5 text-xs font-mono bg-muted/30 border border-input rounded-md focus:outline-none focus:ring-1 focus:ring-ring"
                      spellCheck={false}
                      disabled={saving}
                    />
                    <button
                      title="Remove"
                      onClick={() => setCommandLines(lines => lines.filter((_, i) => i !== index))}
                      disabled={saving}
                      className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-destructive flex-shrink-0 disabled:opacity-50"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}
            <p className="text-[11px] text-muted-foreground">每个参数一行，第一行为可执行文件</p>
          </div>
        )}

        {!isRemote && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium">环境变量</label>
              <button
                onClick={() => setEnvRows(rows => [...rows, { key: '', value: '' }])}
                disabled={saving}
                className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
              >
                <Plus className="w-3 h-3" /> 添加
              </button>
            </div>
            <KeyValueEditor
              rows={envRows}
              onChange={setEnvRows}
              keyPlaceholder="NAME"
              valuePlaceholder="Value"
              emptyText="未设置环境变量"
              disabled={saving}
            />
          </div>
        )}

        <div className="space-y-1.5">
          <label className="text-xs font-medium">超时时间（秒）</label>
          <input
            type="number"
            min={10}
            max={3600}
            value={timeoutValue}
            onChange={e => setTimeoutValue(e.target.value)}
            className="w-28 px-2 py-1.5 text-sm bg-muted/30 border border-input rounded-md focus:outline-none focus:ring-1 focus:ring-ring"
            disabled={saving}
          />
        </div>

        <div className="flex items-center gap-2 p-2.5 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-600 flex-shrink-0" />
          <p className="text-xs text-amber-700 dark:text-amber-400">
            {isRemote ? '请求头（如 Authorization）' : '环境变量（如密钥）'}将以明文保存，保存后立即用新配置重连该服务器。
          </p>
        </div>

        {error && (
          <div className="flex items-center gap-2 text-destructive text-xs">
            <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
            <span className="break-all">{error}</span>
          </div>
        )}

        <div className="flex justify-end gap-2">
          <button
            onClick={onClose}
            disabled={saving}
            className="px-3 py-1.5 text-sm rounded-md hover:bg-muted border border-input disabled:opacity-50"
          >
            取消
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 flex items-center gap-1.5"
          >
            {saving && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            保存
          </button>
        </div>
      </div>
    </div>
  );
};

interface EditModalProps {
  server: McpServerDto | null;
  onClose: () => void;
  onSave: EditModalFormProps['onSave'];
}

const EditModal: React.FC<EditModalProps> = ({ server, onClose, onSave }) => {
  if (!server) return null;
  return <EditModalForm key={mcpServerKey(server)} server={server} onClose={onClose} onSave={onSave} />;
};

// ─── Main Component ───────────────────────────────────────────────────────────

export const McpServerManager: React.FC = () => {
  const [servers, setServers] = useState<McpServerDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingKey, setLoadingKey] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [legacyOwnership, setLegacyOwnership] = useState(false);
  const [showAdd, setShowAdd] = useState(false);
  const [importing, setImporting] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServerDto | null>(null);

  const currentUserId = useAuthStore(state => state.user?.id);
  const canManageShared = useCapabilityStore(selectCanManage('mcp'));
  const groupUserId = useCapabilityStore(state => state.capabilities?.groupUserId ?? null);
  // A member may read the group bucket but not create in it, so only owners get the group target.
  // An older backend omits ownerId, so no row can be attributed to the group bucket either.
  const groupAvailable = groupUserId != null && canManageShared && !legacyOwnership;

  // A server name repeats across the personal and group buckets, so rows are addressed by
  // (ownerId, name) and every write names the row's own bucket instead of relying on a lookup.
  const scopeOf = (server: McpServerDto) => mcpServerScope(server, currentUserId);
  const isShared = (server: McpServerDto) => scopeOf(server) === 'group';
  // Without ownerId, a same-named row cannot be attributed to a bucket, so any write would be a
  // guess — and the key-based list update would touch both rows. Keep those rows read-only.
  const legacyDuplicateNames = useMemo(() => {
    if (!legacyOwnership) return new Set<string>();
    const counts = new Map<string, number>();
    for (const server of servers) counts.set(server.name, (counts.get(server.name) ?? 0) + 1);
    return new Set([...counts].filter(([, count]) => count > 1).map(([name]) => name));
  }, [legacyOwnership, servers]);
  const canManageServer = (server: McpServerDto) => {
    if (legacyDuplicateNames.has(server.name)) return false;
    return !isShared(server) || canManageShared;
  };
  // Follow the host default when it points at a writable group bucket; otherwise personal.
  const addDefaultScope: AssetScope =
    groupAvailable && resolveWriteScope() === 'group' ? 'group' : 'personal';

  // Auto-refresh every 30 seconds
  const refreshTimerRef = useRef<ReturnType<typeof setInterval>>();

  const loadServers = useCallback(async (options?: { silent?: boolean }) => {
    // The 30s tick must not clear an error the user is reading or flicker the whole list.
    const silent = options?.silent === true;
    try {
      if (!silent) {
        setLoading(true);
        setError('');
      }
      const data = await mcpService.listServers();
      setLegacyOwnership(data.some(server => !server.ownerId));
      setServers(data);
    } catch (e: unknown) {
      if (!silent) setError(e instanceof Error ? e.message : 'Failed to load MCP servers');
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadServers();
    refreshTimerRef.current = setInterval(() => { void loadServers({ silent: true }); }, 30_000);
    return () => clearInterval(refreshTimerRef.current);
  }, [loadServers]);

  const handleToggleEnable = async (server: McpServerDto) => {
    const key = mcpServerKey(server);
    setLoadingKey(key);
    try {
      const updated = await mcpService.updateServer(server.name, {
        ...server,
        enabled: !server.enabled,
      }, scopeOf(server));
      setServers(prev => prev.map(s => mcpServerKey(s) === key ? updated : s));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to update server');
    } finally {
      setLoadingKey(null);
    }
  };

  const handleReconnect = async (server: McpServerDto) => {
    const key = mcpServerKey(server);
    setLoadingKey(key);
    try {
      const updated = await mcpService.connectServer(server.name, scopeOf(server));
      setServers(prev => prev.map(s => mcpServerKey(s) === key ? updated : s));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to reconnect');
    } finally {
      setLoadingKey(null);
    }
  };

  const handleDelete = async (server: McpServerDto) => {
    if (!window.confirm(`删除 MCP 服务器 "${server.name}"？`)) return;
    const key = mcpServerKey(server);
    setLoadingKey(key);
    try {
      await mcpService.deleteServer(server.name, scopeOf(server));
      setServers(prev => prev.filter(s => mcpServerKey(s) !== key));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to delete server');
    } finally {
      setLoadingKey(null);
    }
  };

  /**
   * Merge into the freshest row rather than the caller's opening snapshot: the 30s refresh can bring
   * a newer config while a modal is open, and PUT replaces the whole row. The Edit modal sends only
   * fields the user changed, so refreshed values for untouched fields survive here.
   */
  const latestServer = (server: McpServerDto): McpServerDto => {
    // Prefer the exact row: a legacy response omits ownerId, so two same-named rows share a key.
    const key = mcpServerKey(server);
    return servers.find(s => s === server)
      ?? servers.find(s => mcpServerKey(s) === key)
      ?? server;
  };

  const handleUpdate = async (server: McpServerDto, updates: Partial<McpServerDto>) => {
    const latest = latestServer(server);
    const key = mcpServerKey(latest);
    setLoadingKey(key);
    try {
      const updated = await mcpService.updateServer(latest.name, {
        ...latest,
        ...updates,
      }, scopeOf(latest));
      setServers(prev => prev.map(s => mcpServerKey(s) === key ? updated : s));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to update server');
      throw e;
    } finally {
      setLoadingKey(null);
    }
  };

  const handleSaveEdit = async (server: McpServerDto, updates: Partial<McpServerDto>) => {
    const latest = latestServer(server);
    const key = mcpServerKey(latest);
    setLoadingKey(key);
    try {
      const updated = await mcpService.updateServer(latest.name, {
        ...latest,
        ...updates,
      }, scopeOf(latest));
      setServers(prev => prev.map(s => mcpServerKey(s) === key ? updated : s));
    } finally {
      setLoadingKey(null);
    }
  };

  const handleImport = async (jsonStr: string, scope: AssetScope) => {
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
      const results = await mcpService.bulkImport(req, scope);
      // Reload full list to reflect state
      await loadServers();
      // Per-entry failures come back as 200 + status "failed"; surface them instead of closing the
      // dialog as if every server was added.
      const failures = results.filter(result => result.status === 'failed');
      if (failures.length > 0) {
        throw new Error(failures.map(result => result.error ? `${result.name}: ${result.error}` : result.name).join('; '));
      }
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
            onClick={() => loadServers()}
            disabled={loading}
            title="Refresh"
            className="p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={() => setShowAdd(true)}
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

      {/* An older backend lists rows without ownerId; there is no way to tell a personal row from a group one. */}
      {legacyOwnership && (
        <div className="flex items-start gap-2 p-3 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 text-amber-700 dark:text-amber-400 text-sm">
          <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" />
          <span>
            {i18n('This server does not expose MCP server ownership; group management is unavailable until the backend is upgraded. Same-named rows stay read-only because their bucket cannot be resolved.')}
          </span>
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
          {servers.map((server, index) => (
            <ServerRow
              key={legacyOwnership ? `${mcpServerKey(server)}#${index}` : mcpServerKey(server)}
              server={server}
              shared={isShared(server)}
              canManage={canManageServer(server)}
              onToggleEnable={handleToggleEnable}
              onReconnect={handleReconnect}
              onDelete={handleDelete}
              onEdit={setEditingServer}
              onUpdate={handleUpdate}
              loading={loadingKey === mcpServerKey(server)}
            />
          ))}
        </div>
      )}

      {/* Add Modal */}
      <AddServerModal
        open={showAdd}
        onClose={() => setShowAdd(false)}
        onImport={handleImport}
        importing={importing}
        groupAvailable={groupAvailable}
        defaultScope={addDefaultScope}
      />

      {/* Edit Modal */}
      <EditModal
        server={editingServer}
        onClose={() => setEditingServer(null)}
        onSave={handleSaveEdit}
      />
    </div>
  );
};

// Keep backward-compatible export
export const McpServerPlaceholder = McpServerManager;