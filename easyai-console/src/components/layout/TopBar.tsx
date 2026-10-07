import React from 'react';
import { useLocation } from 'react-router-dom';
import { Menu } from 'lucide-react';
import { NAV_ITEMS, EXTRA_NAV_ITEMS, TOPBAR_EXTRA_ITEMS, resolveActiveNavItem } from '@/constants/navigation';
import { useNavStore } from '@/services/stores/nav-store';
import { useFeatureStore } from '@/services/stores/feature-store';
import { i18n } from '@/utils/i18n';
import { UserMenu } from './UserMenu';
import { ThemeToggle } from './ThemeToggle';
import { ProjectSelector } from '@/components/project/ProjectSelector';

export const TopBar: React.FC = () => {
  const location = useLocation();
  const setMobileSidebarOpen = useNavStore((s) => s.setMobileSidebarOpen);
  const projectSelectionEnabled = useFeatureStore((s) => s.projectSelectionEnabled);

  // Title follows the *most specific* matching entry, so a nested page (`/home/family/permissions`)
  // is titled after itself rather than after its parent section (`/home/family`).
  const currentNav = resolveActiveNavItem([...EXTRA_NAV_ITEMS, ...NAV_ITEMS], location.pathname);

  const title = currentNav ? i18n(currentNav.labelKey) : '';

  return (
    <div className="h-full flex items-center justify-between gap-3 px-4">
      <div className="flex items-center gap-3 min-w-0">
        {/* Mobile hamburger */}
        <button
          onClick={() => setMobileSidebarOpen(true)}
          className="p-1.5 rounded-md hover:bg-muted transition-colors md:hidden shrink-0"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Page title */}
        <h2 className="text-base font-medium truncate">{title}</h2>
      </div>

      {/* User menu + Theme toggle (right side) */}
      <div className="flex items-center gap-1">
        {TOPBAR_EXTRA_ITEMS.map((Extra, index) => (
          <Extra key={index} />
        ))}
        <ProjectSelector selectable={projectSelectionEnabled} />
        <UserMenu />
        <ThemeToggle />
      </div>
    </div>
  );
};
