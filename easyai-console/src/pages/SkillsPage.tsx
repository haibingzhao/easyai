import React, { useEffect, useState } from 'react';
import type { SkillInfo } from '@/types/agent';
import { useSkillStore } from '@/services/stores/skill-store';
import { SHARED_OWNER_ID } from '@/services/skill-service';
import { useAuthStore } from '@/services/stores/auth-store';
import { AddSkillDialog } from '@/components/skills/AddSkillDialog';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { FilesSplitView } from '@/components/files/FilesSplitView';
import { useResizable } from '@/hooks/useResizable';
import { i18n } from '@/utils/i18n';
import { BookOpen, Loader2, Plus, Trash2, X } from 'lucide-react';

/** Stable identity for a catalog row: the same name can exist in both the own and shared layers. */
function skillKey(skill: SkillInfo): string {
  return `${skill.shared}-${skill.name}`;
}

/** One catalog row carries at most one status: disabled wins, then restore, then indexing. */
function statusPills(skill: SkillInfo): React.ReactNode {
  const pills: { key: string; label: string; className: string; spin?: boolean }[] = [];
  // A disabled row is delisted on purpose, so restore and index progress only apply to enabled ones.
  if (!skill.enabled) pills.push({ key: 'disabled', label: i18n('Disabled'), className: 'bg-muted text-muted-foreground' });
  else if (!skill.installedOnDisk) pills.push({ key: 'restoring', label: i18n('Awaiting restore'), className: 'bg-amber-500/10 text-amber-600' });
  else if (!skill.indexed) pills.push({ key: 'indexing', label: i18n('Indexing'), className: 'bg-primary/10 text-primary', spin: true });
  return (
    <>
      {pills.map((pill) => (
        <span key={pill.key} className={`flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded ${pill.className}`}>
          {pill.spin && <Loader2 className="w-2.5 h-2.5 animate-spin" />}
          {pill.label}
        </span>
      ))}
    </>
  );
}

const LIST_MIN = 280;
const LIST_MAX = 560;
const LIST_DEFAULT = 380;

export const SkillsPage: React.FC = () => {
  const userId = useAuthStore((state) => state.user?.id);
  const {
    skills,
    loading,
    busyName,
    error,
    notice,
    indexing,
    load,
    clearError,
    clearNotice,
    setEnabled,
    remove,
    stopIndexPolling,
  } = useSkillStore();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<SkillInfo | null>(null);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [listWidth, setListWidth] = useState(LIST_DEFAULT);
  const [resizing, setResizing] = useState(false);

  const listResizer = useResizable({
    minWidth: LIST_MIN,
    maxWidth: LIST_MAX,
    onResize: (w) => setListWidth(Math.round(w)),
    direction: 'right',
    onResizeStart: () => setResizing(true),
    onResizeEnd: () => setResizing(false),
  });

  // The shared layer is writable only by the identity that owns it; everyone else reads it.
  const canManageShared = userId === SHARED_OWNER_ID;
  const own = skills.filter((skill) => !skill.shared);
  const shared = skills.filter((skill) => skill.shared);

  // Derive the selected row from the live list so index polling never leaves a stale object behind.
  const selected = selectedKey ? skills.find((skill) => skillKey(skill) === selectedKey) ?? null : null;

  useEffect(() => {
    load();
    return () => stopIndexPolling();
  }, [load, stopIndexPolling]);

  // Opening a skill defaults the viewer to its SKILL.md; the tree reveals and selects it.
  useEffect(() => {
    if (selected && selected.installedOnDisk && selected.installPath) {
      setSelectedFile(`${selected.installPath}/SKILL.md`);
    } else {
      setSelectedFile(null);
    }
  }, [selectedKey, selected?.installPath, selected?.installedOnDisk]);

  const openSkill = (skill: SkillInfo) => {
    if (!skill.installedOnDisk) return;
    setSelectedKey(skillKey(skill));
  };

  const renderRow = (skill: SkillInfo, writable: boolean) => {
    const isSelected = selectedKey === skillKey(skill);
    const clickable = skill.installedOnDisk;
    return (
      <div
        key={skillKey(skill)}
        onClick={() => openSkill(skill)}
        title={clickable ? skill.name : i18n('Awaiting restore')}
        className={`rounded-lg border p-4 transition-colors ${
          isSelected ? 'border-primary bg-primary/5' : 'border-border bg-card'
        } ${clickable ? 'cursor-pointer hover:bg-muted/50' : 'opacity-70 cursor-not-allowed'}`}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1 flex-wrap">
              <span className="font-mono text-sm font-semibold">{skill.name}</span>
              <span className="text-xs text-muted-foreground">v{skill.version}</span>
              {statusPills(skill)}
            </div>
            {skill.description && <p className="text-sm text-muted-foreground mb-1">{skill.description}</p>}
            {skill.tags.length > 0 && (
              <div className="flex gap-1 mb-1 flex-wrap">
                {skill.tags.map((tag) => (
                  <span key={tag} className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">{tag}</span>
                ))}
              </div>
            )}
            <p className="text-xs text-muted-foreground/70 font-mono truncate max-w-lg" title={skill.installPath}>
              {skill.installPath}
            </p>
          </div>

          <div className="flex items-center gap-2 shrink-0" onClick={(e) => e.stopPropagation()}>
            {writable ? (
              <>
                <button
                  onClick={() => setEnabled(skill.name, !skill.enabled)}
                  disabled={busyName === skill.name}
                  title={skill.enabled ? i18n('Disable') : i18n('Enable')}
                  className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors ${
                    skill.enabled ? 'bg-green-500' : 'bg-muted-foreground/30'
                  } disabled:opacity-50`}
                >
                  <span className={`inline-block h-3.5 w-3.5 rounded-full bg-white transition-transform ${
                    skill.enabled ? 'translate-x-[18px]' : 'translate-x-0.5'
                  }`} />
                </button>
                <button
                  onClick={() => setPendingDelete(skill)}
                  disabled={busyName === skill.name}
                  className="p-1.5 rounded-md hover:bg-destructive/10 transition-colors text-muted-foreground hover:text-destructive disabled:opacity-50"
                  title={i18n('Delete')}
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </>
            ) : (
              <span className="text-xs text-muted-foreground">{i18n('Read-only')}</span>
            )}
          </div>
        </div>
      </div>
    );
  };

  const section = (title: string, hint: string, rows: SkillInfo[], writable: boolean) => (
    <section className="space-y-2">
      <div className="flex items-baseline justify-between">
        <h2 className="text-sm font-semibold">{title}</h2>
        <span className="text-xs text-muted-foreground">{hint}</span>
      </div>
      {rows.length === 0
        ? <p className="text-xs text-muted-foreground italic py-3">{i18n('Nothing here yet.')}</p>
        : rows.map((skill) => renderRow(skill, writable))}
    </section>
  );

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
        <div>
          <h1 className="text-2xl font-bold">{i18n('Skills')}</h1>
          <p className="text-sm text-muted-foreground mt-1">
            {i18n('Install a skill from a server directory or an upload, and keep it in sync across devices.')}
          </p>
        </div>
        <button
          onClick={() => setDialogOpen(true)}
          className="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors text-sm shrink-0"
        >
          <Plus className="w-4 h-4" />
          {i18n('Add Skill')}
        </button>
      </div>

      {/* Master-detail split: skill list (left) + file browser (right) */}
      <div className={`flex-1 flex overflow-hidden ${resizing ? 'resizing' : ''}`}>
        {/* Left: skill list */}
        <div className="shrink-0 overflow-y-auto p-4 space-y-4" style={{ width: listWidth }}>
          {error && (
            <div className="p-3 bg-destructive/10 text-destructive rounded-md text-sm flex items-center justify-between">
              <span>{error}</span>
              <button onClick={clearError}><X className="w-4 h-4" /></button>
            </div>
          )}

          {notice && (
            <div className="p-3 bg-amber-500/10 text-amber-600 rounded-md text-sm flex items-center justify-between gap-3">
              <span>{notice}</span>
              <button onClick={clearNotice} className="shrink-0"><X className="w-4 h-4" /></button>
            </div>
          )}

          {indexing && (
            <div className="flex items-center gap-2 p-3 bg-primary/5 text-primary rounded-md text-sm">
              <Loader2 className="w-4 h-4 animate-spin" />
              {i18n('Restoring packages and updating the skill index...')}
            </div>
          )}

          {loading && skills.length === 0 ? (
            <div className="flex flex-col items-center gap-2 text-center py-12 text-muted-foreground">
              <BookOpen className="w-12 h-12" />
              <span>{i18n('Loading...')}</span>
            </div>
          ) : (
            <>
              {section(i18n('My Skills'), i18n('Only you can see and change these'), own, true)}
              {section(
                i18n('Shared Skills'),
                canManageShared ? i18n('Every user can load these') : i18n('Published by the system owner'),
                shared,
                canManageShared,
              )}
            </>
          )}
        </div>

        {/* Drag handle between list and file browser */}
        <div
          className={`resize-handle ${resizing ? 'active' : ''}`}
          onMouseDown={(e) => {
            listResizer.setCurrentWidth(listWidth);
            listResizer.onMouseDown(e);
          }}
          onTouchStart={(e) => {
            listResizer.setCurrentWidth(listWidth);
            listResizer.onTouchStart(e);
          }}
        />

        {/* Right: file browser for the selected skill */}
        <div className="flex-1 overflow-hidden">
          <FilesSplitView
            rootPath={selected?.installedOnDisk ? selected.installPath : ''}
            projectId=""
            selectedFile={selectedFile}
            onFileSelect={setSelectedFile}
            readOnly
            noRootHint="Select a skill"
          />
        </div>
      </div>

      <AddSkillDialog open={dialogOpen} onClose={() => setDialogOpen(false)} canPublishShared={canManageShared} />

      <ConfirmDialog
        open={pendingDelete !== null}
        message={pendingDelete
          ? `${i18n('Delete this skill')} “${pendingDelete.name}”? ${i18n('Its package, local files and index entry all go away.')}`
          : ''}
        confirmLabel={i18n('Delete')}
        danger
        onConfirm={() => {
          const skill = pendingDelete;
          setPendingDelete(null);
          if (skill) remove(skill.name);
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
