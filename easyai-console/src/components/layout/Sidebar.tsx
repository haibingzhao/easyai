import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { MessageSquare, GitBranch, Settings, Bot, Database, Blocks, Brain, Terminal, Cpu, PanelLeftClose, PanelLeft, BookOpen, Sparkles } from 'lucide-react';
import { NAV_ITEMS, EXTRA_NAV_ITEMS, APP_CONFIG, ICON_REGISTRY, resolveActiveNavItem } from '@/constants/navigation';
import { useNavStore } from '@/services/stores/nav-store';
import { SIDEBAR_COLLAPSED_WIDTH } from './AppLayout';
import { i18n } from '@/utils/i18n';

const ICON_MAP: Record<string, React.ComponentType<{ className?: string }>> = {
  MessageSquare,
  GitBranch,
  Bot,
  Terminal,
  Blocks,
  Brain,
  Cpu,
  Database,
  Settings,
  BookOpen,
  Sparkles,
};

/** Resolve icon by name: built-in map first, then external registry. */
function resolveIcon(name: string): React.ComponentType<{ className?: string }> | undefined {
  return ICON_MAP[name] ?? ICON_REGISTRY[name];
}

export const Sidebar: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { sidebarCollapsed, mobileSidebarOpen, sidebarWidth, toggleSidebar, setMobileSidebarOpen } = useNavStore();

  /**
   * Rendered items, and which one of them is "current".
   *
   * Only the **most specific** match counts (see `resolveActiveNavItem`): with prefix
   * matching alone, `/home/family` would also light up on `/home/family/permissions`,
   * and the sidebar would look like two entries are selected at once. Comparing by
   * `id` keeps exactly one winner even if two consumers register the same path.
   */
  const visibleItems = [...EXTRA_NAV_ITEMS, ...NAV_ITEMS].filter((item) => item.visible?.() !== false);
  const activeItem = resolveActiveNavItem(visibleItems, location.pathname);

  const handleNavClick = (path: string) => {
    navigate(path);
    setMobileSidebarOpen(false);
  };

  const sidebarClass = [
    'app-sidebar',
    'h-full flex flex-col bg-background',
    mobileSidebarOpen ? 'open' : '',
  ].join(' ');

  return (
    <>
      {/* Mobile backdrop */}
      {mobileSidebarOpen && (
        <div
          className="app-sidebar-backdrop md:hidden"
          onClick={() => setMobileSidebarOpen(false)}
        />
      )}

      <div className={sidebarClass} style={{ width: sidebarCollapsed ? SIDEBAR_COLLAPSED_WIDTH : sidebarWidth }}>
        {/* Logo area */}
        <div className={`h-12 flex items-center ${sidebarCollapsed ? 'justify-center px-1' : 'justify-between px-3'} border-b border-border shrink-0`}>
          {!sidebarCollapsed && (
            <span className="font-semibold text-sm truncate">{APP_CONFIG.appName}</span>
          )}
          <button
            onClick={toggleSidebar}
            className={`rounded-md hover:bg-muted transition-colors shrink-0 ${sidebarCollapsed ? 'p-1' : 'p-1.5'}`}
            title={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {sidebarCollapsed
              ? <PanelLeft className="w-4 h-4" />
              : <PanelLeftClose className="w-4 h-4" />
            }
          </button>
        </div>

        {/* Navigation items */}
        <nav className={`flex-1 py-2 ${sidebarCollapsed ? 'px-1' : 'px-2'} space-y-1 overflow-y-auto`}>
          {visibleItems.map((item) => {
            const Icon = resolveIcon(item.icon);
            const active = item.id === activeItem?.id;
            return (
              <button
                key={item.id}
                onClick={() => handleNavClick(item.path)}
                className={[
                  'w-full flex items-center gap-3 py-2 text-sm rounded-md transition-colors',
                  // Keyboard focus gets the themed ring; the mouse does not leave the
                  // browser's default outline behind, which is easy to mistake for a
                  // second selection.
                  'outline-none focus-visible:ring-2 focus-visible:ring-ring',
                  sidebarCollapsed ? 'justify-center px-1' : 'px-2',
                  active ? 'bg-muted font-medium' : 'hover:bg-muted',
                ].join(' ')}
                title={sidebarCollapsed ? i18n(item.labelKey) : undefined}
              >
                {Icon && <Icon className="w-4 h-4 shrink-0" />}
                {!sidebarCollapsed && <span className="truncate">{i18n(item.labelKey)}</span>}
              </button>
            );
          })}
        </nav>
      </div>
    </>
  );
};
