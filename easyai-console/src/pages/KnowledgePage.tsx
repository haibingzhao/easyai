import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Brain, FileText } from 'lucide-react';
import { KnowledgeTree } from '@/components/knowledge/KnowledgeTree';
import { ArticleListPanel } from '@/components/knowledge/ArticleListPanel';
import { MemoryPanel } from '@/components/knowledge/MemoryPanel';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { useResizable } from '@/hooks/useResizable';
import { i18n } from '@/utils/i18n';

type TabId = 'articles' | 'memory';

const SIDEBAR_MIN = 220;
const SIDEBAR_MAX = 420;

function isTabId(value: string | null): value is TabId {
  return value === 'articles' || value === 'memory';
}

export const KnowledgePage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const activeTab: TabId = isTabId(tabParam) ? tabParam : 'articles';

  const setActiveTab = (tab: TabId) => {
    const next = new URLSearchParams(searchParams);
    next.set('tab', tab);
    setSearchParams(next, { replace: true });
  };

  return (
    <div className="h-full flex flex-col bg-background min-h-0">
      {/* Tab bar */}
      <div className="flex items-center gap-0 px-4 border-b border-border shrink-0">
        <TabButton
          active={activeTab === 'articles'}
          icon={<FileText className="size-3.5" />}
          label={i18n('Articles')}
          onClick={() => setActiveTab('articles')}
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
        {activeTab === 'articles' ? <ArticlesPanel /> : <MemoryPanel />}
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

/** Folder tree (left) + article list (right). */
const ArticlesPanel: React.FC = () => {
  const stopIndexingPoll = useKnowledgeStore((s) => s.stopIndexingPoll);
  const [selectedFolderId, setSelectedFolderId] = useState<string | null>(null);
  const [sidebarWidth, setSidebarWidth] = useState(260);

  const resizable = useResizable({
    minWidth: SIDEBAR_MIN,
    maxWidth: SIDEBAR_MAX,
    direction: 'right',
    onResize: setSidebarWidth,
  });

  useEffect(() => {
    return () => stopIndexingPoll();
  }, [stopIndexingPoll]);

  return (
    <div className="h-full flex min-h-0">
      <div style={{ width: sidebarWidth }} className="flex flex-col min-h-0 border-r border-border shrink-0">
        <KnowledgeTree selectedFolderId={selectedFolderId} onSelect={setSelectedFolderId} />
      </div>
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
      <div className="flex-1 min-w-0 flex flex-col min-h-0">
        <ArticleListPanel folderId={selectedFolderId} />
      </div>
    </div>
  );
};
