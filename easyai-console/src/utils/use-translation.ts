import { useSettingsStore } from '@/services/stores/settings-store';
import { i18n } from './i18n';

/**
 * Reactive translation hook.
 * Subscribes to the current language setting so components re-render
 * whenever the language changes and pick up the latest translation.
 */
export function useTranslation(): (key: string) => string {
  // Subscribe to language changes to force re-renders
  useSettingsStore((s) => s.language);
  return i18n;
}
