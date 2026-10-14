import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { resolveActiveNavItem } from '@/constants/navigation';
import { i18n } from '@/utils/i18n';
import { MOBILE_TAB_ITEMS, resolveMobileTabs, type MobileTabItem } from '../registries';
import { MobileIcon } from './MobileIcon';

const MORE_TAB: MobileTabItem = {
  id: 'mobile-more',
  label: 'More',
  labelKey: 'More',
  icon: 'MoreHorizontal',
  path: '/more',
};

/**
 * Bottom tab bar: host tabs (plus the framework's Chat / History) and a fixed "More" slot.
 *
 * The host registry alone decides what appears, so a section a host hides with
 * `visible: () => false` disappears from the bar without the framework knowing its name.
 */
export const MobileTabBar: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();

  const tabs = [...resolveMobileTabs(MOBILE_TAB_ITEMS), MORE_TAB];
  const active = resolveActiveNavItem(tabs, location.pathname);

  return (
    <nav className="mobile-tabbar">
      {tabs.map((tab) => (
        <button
          key={tab.id}
          className="mobile-tabbar-item"
          data-active={active?.id === tab.id ? 'true' : 'false'}
          onClick={() => navigate(tab.path)}
        >
          <MobileIcon name={tab.icon} className="w-5 h-5" />
          <span className="truncate max-w-full px-1">{i18n(tab.labelKey)}</span>
        </button>
      ))}
    </nav>
  );
};
