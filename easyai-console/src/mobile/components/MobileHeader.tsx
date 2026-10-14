import React from 'react';
import { ChevronLeft } from 'lucide-react';
import { i18n } from '@/utils/i18n';

interface MobileHeaderProps {
  left?: React.ReactNode;
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  right?: React.ReactNode;
  /** Renders a back chevron as the leading element. */
  onBack?: () => void;
}

/**
 * Page header. The status-bar inset is carried by `.mobile-app` itself, not by this component, so a
 * reachability banner rendered above the page still clears the notch.
 */
export const MobileHeader: React.FC<MobileHeaderProps> = ({ left, title, subtitle, right, onBack }) => (
  <header className="mobile-app-header">
    <div className="mobile-app-header-bar">
      {onBack && (
        <button className="mobile-hit !min-w-0 p-1.5 -ml-1.5 rounded-md" onClick={onBack} aria-label={i18n('Back')}>
          <ChevronLeft className="w-6 h-6" />
        </button>
      )}
      {left}
      <div className="flex-1 min-w-0">
        {title && <div className="text-sm font-medium truncate">{title}</div>}
        {subtitle && <div className="text-[11px] text-muted-foreground truncate">{subtitle}</div>}
      </div>
      {right}
    </div>
  </header>
);
