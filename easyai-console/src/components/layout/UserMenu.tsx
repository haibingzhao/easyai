import React, { useRef, useState, useEffect } from 'react';
import { LogOut, ChevronDown } from 'lucide-react';
import { useAuthStore } from '@/services/stores/auth-store';
import { useTranslation } from '@/utils/use-translation';

const AVATAR_COLORS = [
  '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
  '#ec4899', '#f43f5e', '#ef4444', '#f97316',
  '#eab308', '#22c55e', '#14b8a6', '#06b6d4',
  '#3b82f6', '#2563eb',
];

function getAvatarColor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = seed.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

function getInitials(name: string): string {
  return name.slice(0, 1).toUpperCase();
}

export const UserMenu: React.FC = () => {
  const { user, logout } = useAuthStore();
  const t = useTranslation();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

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
  const avatarColor = getAvatarColor(user.id);
  const initials = getInitials(displayName);

  const handleLogout = async () => {
    setOpen(false);
    await logout();
  };

  return (
    <div ref={menuRef} className="relative">
      {/* Trigger button */}
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-2 px-2 py-1 rounded-md hover:bg-muted transition-colors"
      >
        <div
          className="w-6 h-6 rounded-full flex items-center justify-center text-white text-xs font-medium shrink-0"
          style={{ backgroundColor: avatarColor }}
        >
          {initials}
        </div>
        <span className="text-sm truncate max-w-[80px]">{displayName}</span>
        <ChevronDown className={`w-3 h-3 text-muted-foreground transition-transform shrink-0 ${open ? 'rotate-180' : ''}`} />
      </button>

      {/* Dropdown */}
      {open && (
        <div className="absolute top-full right-0 mt-1 w-64 bg-popover border border-border rounded-lg shadow-lg z-50 py-1">
          {/* Header: avatar + name */}
          <div className="px-4 py-3 border-b border-border">
            <div className="flex items-center gap-3">
              <div
                className="w-10 h-10 rounded-full flex items-center justify-center text-white text-sm font-medium shrink-0"
                style={{ backgroundColor: avatarColor }}
              >
                {initials}
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium truncate">{displayName}</div>
                <div className="text-xs text-muted-foreground truncate">@{user.username}</div>
              </div>
            </div>
          </div>

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

          {/* Logout */}
          <div className="px-1 py-1">
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
    </div>
  );
};
