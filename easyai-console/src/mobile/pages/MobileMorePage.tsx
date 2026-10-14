import React from 'react';
import { useNavigate } from 'react-router-dom';
import { User } from 'lucide-react';
import { MobileHeader } from '../components/MobileHeader';
import { MobileIcon } from '../components/MobileIcon';
import { MOBILE_MORE_ITEMS, resolveMoreItems } from '../registries';
import { i18n } from '@/utils/i18n';

/**
 * Overflow tab for host sections.
 *
 * The tab bar holds four entries; a host with twelve desktop nav items (trading does) gets its
 * first four as tabs and the rest here, so nothing a host registers becomes unreachable.
 */
export const MobileMorePage: React.FC = () => {
  const navigate = useNavigate();
  const items = resolveMoreItems(MOBILE_MORE_ITEMS);

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <MobileHeader title={i18n('More')} />
      <div className="flex-1 min-h-0 overflow-y-auto message-list-scroll">
        {items.length === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-muted-foreground">
            {i18n('Chat and history are the only mobile sections yet.')}
          </p>
        ) : (
          items.map((item) => (
            <button key={item.id} className="mobile-row w-full text-left border-b border-border" onClick={() => void navigate(item.path)}>
              <MobileIcon name={item.icon} className="w-5 h-5 shrink-0 text-muted-foreground" />
              <span className="flex-1 min-w-0 text-sm truncate">{i18n(item.labelKey)}</span>
            </button>
          ))
        )}
        <button className="mobile-row w-full text-left border-b border-border" onClick={() => void navigate('/me')}>
          <User className="w-5 h-5 shrink-0 text-muted-foreground" />
          <span className="flex-1 min-w-0 text-sm truncate">{i18n('Config')}</span>
        </button>
      </div>
    </div>
  );
};
