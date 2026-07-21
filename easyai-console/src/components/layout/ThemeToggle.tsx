import React, { useEffect, useState } from 'react';
import { Sun, Moon, Monitor } from 'lucide-react';
import { useSettingsStore } from '@/services/stores/settings-store';
import { storageService } from '@/services/storage-service';
import { setTheme } from '@/utils/theme';
import type { Theme } from '@/utils/theme';

const THEME_CYCLE: Theme[] = ['light', 'dark', 'system'];

const ICONS: Record<Theme, React.ReactNode> = {
  light: <Sun className="w-4 h-4" />,
  dark: <Moon className="w-4 h-4" />,
  system: <Monitor className="w-4 h-4" />,
};

const LABELS: Record<Theme, string> = {
  light: 'Light',
  dark: 'Dark',
  system: 'System',
};

/**
 * Theme toggle button that cycles through light → dark → system.
 * Works both inside the authenticated app (via settings store)
 * and on the login page (via direct localStorage access).
 */
export const ThemeToggle: React.FC<{ className?: string }> = ({ className = '' }) => {
  const storeTheme = useSettingsStore((s) => s.theme);
  const updateSettings = useSettingsStore((s) => s.updateSettings);
  const saveSettings = useSettingsStore((s) => s.saveSettings);

  // For login page: read theme directly from localStorage when store isn't loaded yet
  const [localTheme, setLocalTheme] = useState<Theme>(() => {
    try {
      const settings = storageService.getSettings();
      return settings.theme;
    } catch {
      return 'system';
    }
  });

  // Sync local theme when store theme changes
  useEffect(() => {
    setLocalTheme(storeTheme);
  }, [storeTheme]);

  // Listen for system theme changes when in 'system' mode
  useEffect(() => {
    if (localTheme !== 'system') return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = () => setTheme('system');
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, [localTheme]);

  const handleClick = () => {
    const currentIndex = THEME_CYCLE.indexOf(localTheme);
    const nextTheme = THEME_CYCLE[(currentIndex + 1) % THEME_CYCLE.length];

    // Always update store (it persists when auth'd)
    updateSettings({ theme: nextTheme });
    saveSettings();
    setLocalTheme(nextTheme);
  };

  return (
    <button
      onClick={handleClick}
      className={`p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground ${className}`}
      title={LABELS[localTheme]}
    >
      {ICONS[localTheme]}
    </button>
  );
};
