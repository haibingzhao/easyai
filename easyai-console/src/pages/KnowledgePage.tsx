import React, { useEffect, useState, useCallback } from 'react';
import { BookOpen, Brain, X } from 'lucide-react';
import { KnowledgeSidebar } from '@/components/knowledge/KnowledgeSidebar';
import { KnowledgeViewer } from '@/components/knowledge/KnowledgeViewer';
import { UploadKnowledgeDialog } from '@/components/knowledge/UploadKnowledgeDialog';
import { MemoryPanel } from '@/components/knowledge/MemoryPanel';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useResizable } from '@/hooks/useResizable';
import { i18n } from '@/utils/i18n';

type TabId = 'knowledge' | 'memory';

const SIDEBAR_MIN = 240;
const SIDEBAR_MAX = 480;

export const KnowledgePage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<TabId>('knowledge');

  return (
    <div className="h-full flex flex-col bg-background min-h-0">
      {/* Tab bar */}
      <div className="flex items-center gap-0 px-4 border-b border-border shrink-0">
        <TabButton
          active={activeTab === 'knowledge'}
          icon={<BookOpen className="size-3.5" />}
          label={i18n('Knowledge Base')}
          onClick={() => setActiveTab('knowledge')}
        />
        <TabButton
          active={activeTab === 'memory'}
          icon={<Brain className="size-3.5" />}
          label={i18n('Memories')}
          onClick={() => setActiveTab('memory')}
        />
      </div>

      {/* Tab content */}
      <div className="flex-1 min-h-0">
        {activeTab === 'knowledge' ? <KnowledgePanel /> : <MemoryPanel />}
      </div>
    </div>
  );
};

/** Tab button component */
const TabButton: React.FC<{
  active: boolean;
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
}> = ({ active, icon, label, onClick }) => (
  <button
    onClick={onClick}
    className={`flex items-center gap-1.5 px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
      active
        ? 'border-primary text-primary'
        : 'border-transparent text-muted-foreground hover:text-foreground'
    }`}
  >
    {icon}
    {label}
  </button>
);

/** Knowledge panel with sidebar + viewer */
const KnowledgePanel: React.FC = () => {
  const entries = useKnowledgeStore((s) => s.entries);
  const sources = useKnowledgeStore((s) => s.sources);
  const detail = useKnowledgeStore((s) => s.detail);
  const loading = useKnowledgeStore((s) => s.loading);
  const error = useKnowledgeStore((s) => s.error);
  const loadEntries = useKnowledgeStore((s) => s.loadEntries);
  const loadSources = useKnowledgeStore((s) => s.loadSources);
  const loadDetail = useKnowledgeStore((s) => s.loadDetail);
  const deleteEntry = useKnowledgeStore((s) => s.deleteEntry);
  const clearError = useKnowledgeStore((s) => s.clearError);
  const clearDetail = useKnowledgeStore((s) => s.clearDetail);
  const stopIndexingPoll = useKnowledgeStore((s) => s.stopIndexingPoll);

  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [sidebarWidth, setSidebarWidth] = useState(288);

  const resizable = useResizable({
    minWidth: SIDEBAR_MIN,
    maxWidth: SIDEBAR_MAX,
    direction: 'right',
    onResize: setSidebarWidth,
  });

  useEffect(() => {
    loadEntries();
    loadSources();
  }, [loadEntries, loadSources]);

  // Stop indexing polling when unmounting the knowledge panel
  useEffect(() => {
    return () => stopIndexingPoll();
  }, [stopIndexingPoll]);

  const handleSelect = useCallback(
    (key: string) => {
      setSelectedKey(key);
      loadDetail(key);
    },
    [loadDetail]
  );

  const handleDelete = useCallback(
    async (key: string) => {
      await deleteEntry(key);
      setSelectedKey(null);
      clearDetail();
    },
    [deleteEntry, clearDetail]
  );

  const handleSelectKey = useCallback(
    (key: string) => {
      setSelectedKey(key);
      loadDetail(key);
    },
    [loadDetail]
  );

  return (
    <div className="h-full flex min-h-0">
      {/* Left sidebar */}
      <div style={{ width: sidebarWidth }} className="flex flex-col min-h-0 border-r border-border shrink-0">
        <KnowledgeSidebar
          entries={entries}
          sources={sources}
          selectedKey={selectedKey}
          onSelect={handleSelect}
          onUploadClick={() => setUploadOpen(true)}
        />
      </div>

      {/* Resize handle */}
      <div
        onMouseDown={(e) => {
          resizable.setCurrentWidth(sidebarWidth);
          resizable.onMouseDown(e);
        }}
        onTouchStart={(e) => {
          resizable.setCurrentWidth(sidebarWidth);
          resizable.onTouchStart(e);
        }}
        className="w-1 cursor-col-resize bg-border hover:bg-primary/60 transition-colors shrink-0"
        title={i18n('Collapse')}
      />

      {/* Right: viewer */}
      <div className="flex-1 min-w-0 flex flex-col min-h-0">
        {/* Error banner */}
        {error && (
          <div className="flex items-center justify-between px-4 py-2 border-b border-destructive/50 bg-destructive/5 text-xs text-destructive shrink-0">
            <span className="truncate">{error}</span>
            <button onClick={clearError} className="text-destructive hover:opacity-70 shrink-0">
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* Viewer */}
        <div className="flex-1 min-h-0">
          <KnowledgeViewer
            detail={detail}
            loading={loading}
            onDelete={handleDelete}
            onSelectKey={handleSelectKey}
          />
        </div>
      </div>

      {/* Upload dialog */}
      <UploadKnowledgeDialog open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </div>
  );
};
