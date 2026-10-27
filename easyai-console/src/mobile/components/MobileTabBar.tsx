import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { resolveActiveNavItem } from '@/constants/navigation';
import { i18n } from '@/utils/i18n';
import { MOBILE_MORE_TAB_ID, MOBILE_TAB_ITEMS, resolveMobileTabs, type MobileTabItem } from '../registries';
import { useMobileUiStore } from '../stores/mobile-ui-store';
import { MobileIcon } from './MobileIcon';

const MORE_TAB: MobileTabItem = {
  id: MOBILE_MORE_TAB_ID,
  label: 'More',
  labelKey: 'More',
  icon: 'MoreHorizontal',
  path: '/more',
};

/**
 * Bottom tab bar: the framework's Chat slot, host tabs, and a fixed "More" slot.
 *
 * The host registry alone decides what appears, so a section a host hides with
 * `visible: () => false` disappears from the bar without the framework knowing its name.
 */
export const MobileTabBar: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const badges = useMobileUiStore((s) => s.tabBadges);

  const tabs = [...resolveMobileTabs(MOBILE_TAB_ITEMS), MORE_TAB];
  const active = resolveActiveNavItem(tabs, location.pathname);

  return (
    <nav className="mobile-tabbar">
      {tabs.map((tab) => {
        const count = badges[tab.id] ?? 0;
        return (
          <button
            key={tab.id}
            className="mobile-tabbar-item"
            data-active={active?.id === tab.id ? 'true' : 'false'}
            onClick={() => navigate(tab.path)}
          >
            <span className="relative inline-flex">
              <MobileIcon name={tab.icon} className="w-5 h-5" />
              {count > 0 && (
                <span className="absolute -right-2.5 -top-1 min-w-[15px] h-[15px] px-1 rounded-full bg-destructive text-destructive-foreground text-[10px] leading-[15px] text-center tabular-nums">
                  {count > 99 ? '99+' : count}
                </span>
              )}
            </span>
            <span className="truncate max-w-full px-1">{i18n(tab.labelKey)}</span>
          </button>
        );
      })}
    </nav>
  );
};
