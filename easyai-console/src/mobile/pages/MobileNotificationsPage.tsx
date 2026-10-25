import React from 'react';
import { MobileHeader } from '../components/MobileHeader';
import { NotificationsPage } from '@/pages/NotificationsPage';
import { i18n } from '@/utils/i18n';

/**
 * The inbox on a phone: the same page, framed by the mobile header instead of the desktop title bar.
 *
 * Reusing the desktop implementation is deliberate. The three groups, the decision buttons and the
 * unread rules are the product; a second mobile-only copy of that logic would drift the moment a
 * rule changes.
 */
export const MobileNotificationsPage: React.FC = () => (
  <div className="flex flex-col flex-1 min-h-0">
    <MobileHeader title={i18n('Notifications')} />
    <NotificationsPage title={null} />
  </div>
);

export default MobileNotificationsPage;
