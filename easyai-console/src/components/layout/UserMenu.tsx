import React, { useRef, useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, LogOut, ChevronDown, Pencil, Settings } from 'lucide-react';
import { useAuthStore } from '@/services/stores/auth-store';
import { startNotificationPolling, useNotificationStore } from '@/services/stores/notification-store';
import { APP_CONFIG } from '@/constants/navigation';
import { useTranslation } from '@/utils/use-translation';
import { UserAvatar } from '@/components/user/UserAvatar';
import { ProfileEditDialog } from '@/components/user/ProfileEditDialog';

/** Above this the badge reads `99+`; nobody counts past it accurately anyway. */
const BADGE_LIMIT = 99;

export const UserMenu: React.FC = () => {
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const t = useTranslation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [editingProfile, setEditingProfile] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const notificationsPath = APP_CONFIG.notificationsPath;
  const summary = useNotificationStore((state) => state.summary);

  // The avatar is the only place the badge lives, so it is also what keeps the count fresh.
  useEffect(() => {
    if (notificationsPath) startNotificationPolling();
  }, [notificationsPath]);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  // Close on Escape
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open]);

  if (!user) return null;

  const displayName = user.displayName || user.username;
  // With auth switched off the backend reports the shared `system` identity, which owns no profile row.
  const canEditProfile = user.id !== 'system';

  const handleLogout = async () => {
    setOpen(false);
    await logout();
  };

  const openProfileEditor = () => {
    setOpen(false);
    setEditingProfile(true);
  };

  // The dot asks "does anything need me", the number answers "how much I have not opened" — they
  // are deliberately different: an item can be read and still be waiting for a decision.
  const badgeCount = summary.unread;
  const hasDot = summary.attention > 0;

  const toggleMenu = () => {
    setOpen((next) => !next);
    // Opening the menu is the moment the user asks the question, so answer it freshly instead of
    // showing whatever the last poll happened to see.
    if (!open) void useNotificationStore.getState().refreshSummary();
  };

  const openNotifications = () => {
    if (!notificationsPath) return;
    setOpen(false);
    navigate(summary.unread > 0 ? `${notificationsPath}?filter=unread` : notificationsPath);
  };

  return (
    <div ref={menuRef} className="relative">
      {/* Trigger button */}
      <button
        onClick={toggleMenu}
        className="flex items-center gap-2 px-2 py-1 rounded-md hover:bg-muted transition-colors"
      >
        <span className="relative inline-flex shrink-0">
          <UserAvatar avatar={user.avatar} name={displayName} seed={user.id} size="sm" />
          {/* Outside the avatar: `UserAvatar` clips its own box, so a dot inside it would be cut off. */}
          {hasDot && <span className="absolute -right-0.5 -top-0.5 w-2 h-2 rounded-full bg-destructive ring-2 ring-background" />}
        </span>
        <span className="text-sm truncate max-w-[80px]">{displayName}</span>
        <ChevronDown className={`w-3 h-3 text-muted-foreground transition-transform shrink-0 ${open ? 'rotate-180' : ''}`} />
      </button>

      {/* Dropdown */}
      {open && (
        <div className="absolute top-full right-0 mt-1 w-64 bg-popover border border-border rounded-lg shadow-lg z-50 py-1">
          {/* Header: avatar + name */}
          <div className="px-4 py-3 border-b border-border">
            <div className="flex items-center gap-3">
              <UserAvatar avatar={user.avatar} name={displayName} seed={user.id} size="md" />
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium truncate">{displayName}</div>
                <div className="text-xs text-muted-foreground truncate">@{user.username}</div>
              </div>
              {canEditProfile && (
                <button
                  onClick={openProfileEditor}
                  title={t('Edit profile')}
                  className="p-1.5 rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors shrink-0"
                >
                  <Pencil className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>

          {/* Notifications: an account-level inbox belongs to "me", so it sits with the identity. */}
          {notificationsPath && (
            <div className="px-1 py-1 border-b border-border">
              <button
                onClick={openNotifications}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-muted rounded-md transition-colors"
              >
                <Bell className="w-4 h-4 text-muted-foreground" />
                <span className="flex-1 text-left">{t('Notifications')}</span>
                {badgeCount > 0 && (
                  <span className="min-w-[18px] px-1.5 h-[18px] rounded-full bg-destructive text-destructive-foreground text-[11px] leading-[18px] text-center tabular-nums">
                    {badgeCount > BADGE_LIMIT ? `${BADGE_LIMIT}+` : badgeCount}
                  </span>
                )}
              </button>
            </div>
          )}

          {/* User details */}
          <div className="px-4 py-3 space-y-2 border-b border-border">
            <div className="grid grid-cols-[4.5rem_1fr] gap-4 items-start">
              <span className="text-xs text-muted-foreground text-right pt-0.5">{t('Nickname')}</span>
              <span className="text-xs truncate">{displayName}</span>
            </div>
            <div className="grid grid-cols-[4.5rem_1fr] gap-4 items-start">
              <span className="text-xs text-muted-foreground text-right pt-0.5">{t('Account')}</span>
              <span className="text-xs truncate">{user.username}</span>
            </div>
            {user.email && (
              <div className="grid grid-cols-[4.5rem_1fr] gap-4 items-start">
                <span className="text-xs text-muted-foreground text-right pt-0.5">{t('Email')}</span>
                <span className="text-xs truncate">{user.email}</span>
              </div>
            )}
          </div>

          {/* Actions */}
          <div className="px-1 py-1 space-y-0.5">
            {canEditProfile && (
              <button
                onClick={() => {
                  setOpen(false);
                  navigate('/settings?tab=account');
                }}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-muted rounded-md transition-colors"
              >
                <Settings className="w-4 h-4 text-muted-foreground" />
                {t('Account settings')}
              </button>
            )}
            <button
              onClick={handleLogout}
              className="w-full flex items-center gap-2 px-3 py-2 text-sm text-destructive hover:bg-muted rounded-md transition-colors"
            >
              <LogOut className="w-4 h-4" />
              {t('Logout')}
            </button>
          </div>
        </div>
      )}

      <ProfileEditDialog open={editingProfile} onClose={() => setEditingProfile(false)} />
    </div>
  );
};
