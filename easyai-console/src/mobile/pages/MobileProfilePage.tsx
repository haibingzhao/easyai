import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, Globe, LogOut, Monitor, X } from 'lucide-react';
import { UserAvatar } from '@/components/user/UserAvatar';
import { ProfileEditDialog } from '@/components/user/ProfileEditDialog';
import { useAuthStore } from '@/services/stores/auth-store';
import { useSettingsStore } from '@/services/stores/settings-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useMobileUiStore } from '../stores/mobile-ui-store';
import { MOBILE_MORE_ITEMS, resolveMoreItems } from '../registries';
import { MobileHeader } from '../components/MobileHeader';
import { MobileIcon } from '../components/MobileIcon';
import { setupService } from '@/services/setup-service';
import { i18n } from '@/utils/i18n';
import type { Theme } from '@/utils/theme';

const THEMES: { value: Theme; labelKey: string }[] = [
  { value: 'system', labelKey: 'System' },
  { value: 'light', labelKey: 'Light' },
  { value: 'dark', labelKey: 'Dark' },
];

const LANGUAGES: { value: string; label: string }[] = [
  { value: 'zh', label: '中文' },
  { value: 'en', label: 'English' },
];

/**
 * Settings that survive being squeezed onto a phone: identity, workspace, appearance, language,
 * connection, sign out. Everything the desktop puts behind a form (model providers, MCP, storage,
 * RAG, permissions) is intentionally absent — see the feature table in
 * `.doc/mobile-ios-design.md`.
 */
export const MobileProfilePage: React.FC = () => {
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const theme = useSettingsStore((s) => s.theme);
  const language = useSettingsStore((s) => s.language);
  const updateSettings = useSettingsStore((s) => s.updateSettings);
  const saveSettings = useSettingsStore((s) => s.saveSettings);
  const currentProject = useProjectStore((s) => s.currentProject);
  const workspaceOnly = useProjectStore((s) => s.workspaceOnly);
  const setCurrentProject = useProjectStore((s) => s.setCurrentProject);
  const hintDismissed = useMobileUiStore((s) => s.homeScreenHintDismissed);
  const dismissHomeScreenHint = useMobileUiStore((s) => s.dismissHomeScreenHint);
  const resetMobileUi = useMobileUiStore((s) => s.resetMobileUi);
  const [profileOpen, setProfileOpen] = useState(false);
  const [connection, setConnection] = useState<'unknown' | 'ok' | 'down'>('unknown');

  const hostItems = resolveMoreItems(MOBILE_MORE_ITEMS);

  const probe = async () => {
    setConnection('unknown');
    try {
      await setupService.getStatus();
      setConnection('ok');
    } catch {
      setConnection('down');
    }
  };

  const chooseProject = () => {
    // Clearing the active project lets `MobileGate` show the picker again — one source of truth
    // for "which workspace am I in", instead of a second mobile-only route.
    setCurrentProject(null);
  };

  /** Sign-out also drops the mobile panel state, so the next account never inherits an open sheet. */
  const signOut = () => {
    resetMobileUi();
    void logout();
  };

  return (
    <>
      <div className="flex flex-col flex-1 min-h-0">
        <MobileHeader title={i18n('Config')} />

        <div className="flex-1 min-h-0 overflow-y-auto message-list-scroll">
          <button className="mobile-row w-full text-left" onClick={() => setProfileOpen(true)}>
            <UserAvatar
              avatar={user?.avatar}
              name={user?.displayName || user?.username || '?'}
              seed={user?.username ?? 'anonymous'}
              size="md"
            />
            <span className="flex-1 min-w-0">
              <span className="block text-sm truncate">{user?.displayName || user?.username || i18n('Account')}</span>
              <span className="block text-[11px] text-muted-foreground truncate">@{user?.username}</span>
            </span>
          </button>

          <div className="mobile-section-title">{i18n('Workspace')}</div>
          <button className="mobile-row w-full text-left border-b border-border" onClick={chooseProject}>
            <Monitor className="w-5 h-5 shrink-0 text-muted-foreground" />
            <span className="flex-1 min-w-0 text-sm truncate">
              {currentProject?.name ?? (workspaceOnly ? i18n('Start without a project') : i18n('Select a Project'))}
            </span>
            <span className="text-xs text-muted-foreground">{i18n('Change')}</span>
          </button>

          <div className="mobile-section-title">{i18n('Appearance')}</div>
          {THEMES.map((item) => (
            <button
              key={item.value}
              className="mobile-row w-full text-left border-b border-border"
              onClick={() => {
                updateSettings({ theme: item.value });
                saveSettings();
              }}
            >
              <span className="flex-1 text-sm">{i18n(item.labelKey)}</span>
              {theme === item.value && <Check className="w-4 h-4 text-primary" />}
            </button>
          ))}

          <div className="mobile-section-title">{i18n('Language')}</div>
          {LANGUAGES.map((item) => (
            <button
              key={item.value}
              className="mobile-row w-full text-left border-b border-border"
              onClick={() => {
                updateSettings({ language: item.value });
                saveSettings();
              }}
            >
              <span className="flex-1 text-sm">{item.label}</span>
              {language === item.value && <Check className="w-4 h-4 text-primary" />}
            </button>
          ))}

          {hostItems.length > 0 && (
            <>
              <div className="mobile-section-title">{i18n('More')}</div>
              {hostItems.map((item) => (
                <button key={item.id} className="mobile-row w-full text-left border-b border-border" onClick={() => void navigate(item.path)}>
                  <MobileIcon name={item.icon} className="w-5 h-5 shrink-0 text-muted-foreground" />
                  <span className="flex-1 min-w-0 text-sm truncate">{i18n(item.labelKey)}</span>
                </button>
              ))}
            </>
          )}

          <div className="mobile-section-title">{i18n('Connection')}</div>
          <button className="mobile-row w-full text-left border-b border-border" onClick={() => void probe()}>
            <Globe className="w-5 h-5 shrink-0 text-muted-foreground" />
            <span className="flex-1 min-w-0 text-sm truncate">{window.location.host}</span>
            <span
              className={`w-2 h-2 rounded-full shrink-0 ${
                connection === 'ok' ? 'bg-green-500' : connection === 'down' ? 'bg-destructive' : 'bg-muted-foreground/40'
              }`}
            />
          </button>

          <p className="px-3 py-3 text-[11px] leading-snug text-muted-foreground">
            {i18n('Agent, skill, MCP and workflow configuration is done on the desktop console.')}
          </p>

          <button className="mobile-row w-full text-left text-destructive" onClick={signOut}>
            <LogOut className="w-5 h-5 shrink-0" />
            <span className="text-sm">{i18n('Sign Out')}</span>
          </button>

          {!hintDismissed && (
            <div className="flex items-start gap-2 px-3 pb-6 pt-2">
              <p className="flex-1 min-w-0 text-[11px] leading-snug text-muted-foreground">
                {i18n('Add this page to your Home Screen so your sign-in stays put.')}
              </p>
              <button
                className="mobile-hit !min-w-0 !min-h-0 shrink-0 p-2 -mr-2 text-muted-foreground"
                onClick={dismissHomeScreenHint}
                aria-label={i18n('Close')}
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          )}
        </div>
      </div>

      <ProfileEditDialog open={profileOpen} onClose={() => setProfileOpen(false)} />
    </>
  );
};
