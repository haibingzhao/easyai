import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  FolderOpen,
  FolderCog,
  TerminalSquare,
  Globe,
  Plug,
  Loader2,
} from 'lucide-react';
import { useProjectStore } from '@/services/stores/project-store';
import {
  fetchPermissionSettings,
  updatePermissionSetting,
} from '@/services/permission-service';
import type { PermissionSettingsDto } from '@/types/permission';
import { MultiSelectDropdown } from './MultiSelectDropdown';
import { FileBrowserDropdown } from './FileBrowserDropdown';
import { i18n } from '@/utils/i18n';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Debounce delay for API calls when toggling checkboxes. */
const DEBOUNCE_MS = 300;

/** Common command prefixes suggested in the "other commands" dropdown. */
const COMMON_COMMANDS: string[] = [
  'npm install',
  'npm run',
  'npm test',
  'npm run build',
  'npm run lint',
  'npm run format',
  'yarn install',
  'yarn add',
  'pnpm install',
  'pnpm add',
  'mvn compile',
  'mvn test',
  'mvn package',
  'mvn install',
  'gradle build',
  'gradle test',
  'pip install',
  'pip install -r',
  'python setup.py',
  'cargo build',
  'cargo test',
  'go build',
  'go test',
  'docker build',
  'docker run',
  'docker compose',
  'git commit',
  'git push',
  'git pull',
  'git merge',
  'git rebase',
  'git checkout',
  'git switch',
  'make',
  'cmake',
  'gcc',
  'g++',
  'rustc',
  'javac',
  'curl',
  'wget',
];

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface AutoApprovePanelProps {
  onClose: () => void;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export const AutoApprovePanel: React.FC<AutoApprovePanelProps> = ({ onClose }) => {
  const currentProjectId = useProjectStore((s) => s.currentProject?.id);

  const [settings, setSettings] = useState<PermissionSettingsDto | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [savingKeys, setSavingKeys] = useState<Set<string>>(new Set());
  /** Tracks which list sections are expanded (checkbox toggles expand/collapse). */
  const [expandedLists, setExpandedLists] = useState<Set<string>>(new Set());

  const panelRef = useRef<HTMLDivElement>(null);
  const debounceTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  // ---- Load settings on mount ----
  useEffect(() => {
    if (!currentProjectId) {
      setIsLoading(false);
      return;
    }
    (async () => {
      try {
        setIsLoading(true);
        const s = await fetchPermissionSettings(currentProjectId);
        setSettings(s);
        // Auto-expand list sections that already have values
        setExpandedLists((prev) => {
          const next = new Set(prev);
          if (s.readOtherPaths.length > 0) next.add('readOtherPaths');
          if (s.writeOtherPaths.length > 0) next.add('writeOtherPaths');
          if (s.otherCommands.length > 0) next.add('otherCommands');
          return next;
        });
      } catch (err) {
        setError((err as Error).message);
      } finally {
        setIsLoading(false);
      }
    })();
  }, [currentProjectId]);

  // ---- Close on outside click / escape ----
  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [onClose]);

  // ---- Cleanup debounce timers on unmount ----
  useEffect(() => {
    return () => {
      debounceTimers.current.forEach((t) => clearTimeout(t));
    };
  }, []);

  // ---- Debounced API call ----
  const debouncedUpdate = useCallback(
    (key: string, value: boolean | string[]) => {
      console.log('[AutoApprovePanel] debouncedUpdate called:', { key, value, currentProjectId });
      if (!currentProjectId) {
        console.warn('[AutoApprovePanel] debouncedUpdate: currentProjectId is null, skipping');
        return;
      }
      // Clear existing timer for this key
      const existing = debounceTimers.current.get(key);
      if (existing) clearTimeout(existing);

      setSavingKeys((prev) => new Set(prev).add(key));
      debounceTimers.current.set(
        key,
        setTimeout(async () => {
          try {
            await updatePermissionSetting(currentProjectId, { key, value });
          } catch (err) {
            setError((err as Error).message);
          } finally {
            setSavingKeys((prev) => {
              const next = new Set(prev);
              next.delete(key);
              return next;
            });
            debounceTimers.current.delete(key);
          }
        }, DEBOUNCE_MS),
      );
    },
    [currentProjectId],
  );

  // ---- Compute disabled states (based on backend priority: all > project > other) ----
  // Must be computed before callbacks that reference them.
  const readFileAllDisabled = settings ? !settings.readFileProject : false;
  const readOtherPathsDisabled = settings ? (!settings.readFileProject || settings.readFileAll) : false;
  const writeFileAllDisabled = settings ? !settings.writeFileProject : false;
  const writeOtherPathsDisabled = settings ? (!settings.writeFileProject || settings.writeFileAll) : false;
  const executeAllDisabled = settings ? !settings.executeSafeCommands : false;
  const otherCommandsDisabled = settings ? (!settings.executeSafeCommands || settings.executeAllCommands) : false;

  // ---- Toggle a boolean setting (with cascade) ----
  const handleToggle = useCallback(
    (key: keyof PermissionSettingsDto, checked: boolean) => {
      console.log('[AutoApprovePanel] handleToggle called:', { key, checked, hasSettings: !!settings });
      if (!settings) return;

      // Cascade: when a parent is unchecked, force children off
      const updates: Partial<PermissionSettingsDto> = { [key]: checked };

      if (key === 'readFileProject' && !checked) {
        updates.readFileAll = false;
        updates.readOtherPaths = [];
        setExpandedLists((prev) => { const n = new Set(prev); n.delete('readOtherPaths'); return n; });
      }
      if (key === 'writeFileProject' && !checked) {
        updates.writeFileAll = false;
        updates.writeOtherPaths = [];
        setExpandedLists((prev) => { const n = new Set(prev); n.delete('writeOtherPaths'); return n; });
      }
      if (key === 'executeSafeCommands' && !checked) {
        updates.executeAllCommands = false;
        updates.otherCommands = [];
        setExpandedLists((prev) => { const n = new Set(prev); n.delete('otherCommands'); return n; });
      }

      // Collapse child "other" section when parent "all" is checked (it becomes redundant)
      if (key === 'readFileAll' && checked) {
        updates.readOtherPaths = [];
        setExpandedLists((prev) => { const n = new Set(prev); n.delete('readOtherPaths'); return n; });
      }
      if (key === 'writeFileAll' && checked) {
        updates.writeOtherPaths = [];
        setExpandedLists((prev) => { const n = new Set(prev); n.delete('writeOtherPaths'); return n; });
      }
      if (key === 'executeAllCommands' && checked) {
        updates.otherCommands = [];
        setExpandedLists((prev) => { const n = new Set(prev); n.delete('otherCommands'); return n; });
      }

      setSettings((prev) => (prev ? { ...prev, ...updates } : prev));

      // Persist each changed key
      for (const [k, v] of Object.entries(updates)) {
        debouncedUpdate(k as keyof PermissionSettingsDto, v as boolean | string[]);
      }
    },
    [settings, debouncedUpdate],
  );

  // ---- Toggle a list section's expand/collapse ----
  const handleListToggle = useCallback(
    (key: keyof PermissionSettingsDto, checked: boolean) => {
      if (!settings) return;

      // Guard: don't allow toggling when disabled by parent
      if (key === 'readOtherPaths' && readOtherPathsDisabled) return;
      if (key === 'writeOtherPaths' && writeOtherPathsDisabled) return;
      if (key === 'otherCommands' && otherCommandsDisabled) return;

      setExpandedLists((prev) => {
        const next = new Set(prev);
        if (checked) {
          next.add(key);
        } else {
          next.delete(key);
          // Collapse → clear list & persist
          const emptyList: string[] = [];
          setSettings((p) => (p ? { ...p, [key]: emptyList } : p));
          debouncedUpdate(key, []);
        }
        return next;
      });
    },
    [settings, debouncedUpdate, readOtherPathsDisabled, writeOtherPathsDisabled, otherCommandsDisabled],
  );

  // ---- Update a list setting (paths / commands) ----
  const handleListChange = useCallback(
    (key: keyof PermissionSettingsDto, list: string[]) => {
      if (!settings) return;
      setSettings((prev) => (prev ? { ...prev, [key]: list } : prev));
      debouncedUpdate(key, list);
    },
    [settings, debouncedUpdate],
  );

  // ---- Render helpers ----
  const isSaving = (key: string) => savingKeys.has(key);

  const BoolCheckbox: React.FC<{
    settingKey: keyof PermissionSettingsDto;
    checked: boolean;
    label: string;
    disabled?: boolean;
  }> = ({ settingKey, checked, label, disabled }) => (
    <label className={`flex items-center gap-2 select-none py-1 ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => {
          console.log('[AutoApprovePanel] BoolCheckbox onChange:', { settingKey, checked: e.target.checked, disabled });
          handleToggle(settingKey, e.target.checked);
        }}
        className="rounded border-border accent-primary"
      />
      <span className="text-sm">{label}</span>
      {isSaving(settingKey as string) && (
        <Loader2 className="w-3 h-3 animate-spin text-muted-foreground" />
      )}
    </label>
  );

  const ListCheckbox: React.FC<{
    settingKey: keyof PermissionSettingsDto;
    expanded: boolean;
    label: string;
    disabled?: boolean;
  }> = ({ settingKey, expanded, label, disabled }) => (
    <label className={`flex items-center gap-2 select-none py-1 ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}>
      <input
        type="checkbox"
        checked={expanded}
        disabled={disabled}
        onChange={(e) => handleListToggle(settingKey, e.target.checked)}
        className="rounded border-border accent-primary"
      />
      <span className="text-sm">{label}</span>
      {isSaving(settingKey as string) && (
        <Loader2 className="w-3 h-3 animate-spin text-muted-foreground" />
      )}
    </label>
  );

  // ---- Render ----
  if (isLoading) {
    return (
      <div className="w-full p-4 bg-background border border-border rounded-lg shadow-lg">
        <div className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" />
          {i18n('加载中...')}
        </div>
      </div>
    );
  }

  if (!settings) {
    return (
      <div className="w-full p-4 bg-background border border-border rounded-lg shadow-lg">
        <div className="text-sm text-muted-foreground text-center">
          {error || i18n('无法加载权限设置')}
        </div>
      </div>
    );
  }

  return (
    <div
      ref={panelRef}
      className="w-full bg-background border border-border rounded-lg shadow-lg overflow-hidden"
    >
      {/* Error banner */}
      {error && (
        <div className="px-4 py-2 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-xs">
          {error}
        </div>
      )}

      {/* Settings body */}
      <div className="p-4 max-h-[420px] overflow-y-auto">
        <div className="flex flex-col gap-4">
          {/* ---- 1. Read Files ---- */}
          <section>
          <div className="flex items-center gap-2 text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-1">
            <FolderOpen className="w-4 h-4" />
            {i18n('读取文件')}
          </div>
          <BoolCheckbox settingKey="readFileProject" checked={settings.readFileProject} label={i18n('读取项目文件')} />
          <BoolCheckbox settingKey="readFileAll" checked={settings.readFileAll} label={i18n('读取所有文件')} disabled={readFileAllDisabled} />
          <ListCheckbox settingKey="readOtherPaths" expanded={expandedLists.has('readOtherPaths')} label={i18n('读取其它路径')} disabled={readOtherPathsDisabled} />
          {expandedLists.has('readOtherPaths') && (
            <div className="mt-1 ml-6">
              <FileBrowserDropdown
                values={settings.readOtherPaths}
                onChange={(v) => handleListChange('readOtherPaths', v)}
                initialPath={settings.projectPath}
                placeholder={i18n('浏览并选择路径...')}
              />
            </div>
          )}
        </section>

        {/* ---- 2. Write Files ---- */}
        <section>
          <div className="flex items-center gap-2 text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-1">
            <FolderCog className="w-4 h-4" />
            {i18n('写入文件')}
          </div>
          <BoolCheckbox settingKey="writeFileProject" checked={settings.writeFileProject} label={i18n('写入项目文件')} />
          <BoolCheckbox settingKey="writeFileAll" checked={settings.writeFileAll} label={i18n('写入所有文件')} disabled={writeFileAllDisabled} />
          <ListCheckbox settingKey="writeOtherPaths" expanded={expandedLists.has('writeOtherPaths')} label={i18n('写入其它路径')} disabled={writeOtherPathsDisabled} />
          {expandedLists.has('writeOtherPaths') && (
            <div className="mt-1 ml-6">
              <FileBrowserDropdown
                values={settings.writeOtherPaths}
                onChange={(v) => handleListChange('writeOtherPaths', v)}
                initialPath={settings.projectPath}
                placeholder={i18n('浏览并选择路径...')}
              />
            </div>
          )}
        </section>

        {/* ---- 3. Execute Commands ---- */}
        <section>
          <div className="flex items-center gap-2 text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-1">
            <TerminalSquare className="w-4 h-4" />
            {i18n('执行命令')}
          </div>
          <BoolCheckbox
            settingKey="executeSafeCommands"
            checked={settings.executeSafeCommands}
            label={i18n('执行安全命令')}
          />
          <BoolCheckbox
            settingKey="executeAllCommands"
            checked={settings.executeAllCommands}
            label={i18n('执行所有命令')}
            disabled={executeAllDisabled}
          />
          <ListCheckbox
            settingKey="otherCommands"
            expanded={expandedLists.has('otherCommands')}
            label={i18n('执行其它命令')}
            disabled={otherCommandsDisabled}
          />
          {expandedLists.has('otherCommands') && (
            <div className="mt-1 ml-6">
              <MultiSelectDropdown
                values={settings.otherCommands}
                onChange={(v) => handleListChange('otherCommands', v)}
                options={COMMON_COMMANDS}
                placeholder={i18n('选择或输入命令前缀...')}
                allowCustom
                variant="compact"
              />
            </div>
          )}
        </section>

        {/* ---- 4. Browser ---- */}
        <section>
          <div className="flex items-center gap-2 text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-1">
            <Globe className="w-4 h-4" />
            {i18n('浏览器')}
          </div>
          <BoolCheckbox settingKey="useBrowser" checked={settings.useBrowser} label={i18n('允许使用浏览器')} />
        </section>

        {/* ---- 5. MCP Services ---- */}
        <section>
          <div className="flex items-center gap-2 text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-1">
            <Plug className="w-4 h-4" />
            {i18n('MCP 服务')}
          </div>
          <BoolCheckbox settingKey="useMcp" checked={settings.useMcp} label={i18n('允许使用 MCP 服务')} />
          </section>
        </div>
      </div>
    </div>
  );
};

export default AutoApprovePanel;
