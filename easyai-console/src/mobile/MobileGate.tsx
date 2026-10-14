import React, { useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Loader2, Monitor } from 'lucide-react';
import { storageService } from '@/services/storage-service';
import { setupService } from '@/services/setup-service';
import { useAuthStore } from '@/services/stores/auth-store';
import { useSettingsStore } from '@/services/stores/settings-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useFeatureStore } from '@/services/stores/feature-store';
import { useCategoryStore } from '@/services/stores/category-store';
import { setTheme } from '@/utils/theme';
import { i18n } from '@/utils/i18n';
import { MobileLoginPage } from './pages/MobileLoginPage';
import { MobileProjectPage } from './pages/MobileProjectPage';

/**
 * Auth / setup / project gate for the mobile shell — the mobile counterpart of the checks in
 * `App.tsx`, kept as a route element so a host wires it as `<Route element={<MobileGate />}>`
 * and never re-implements the sequence.
 *
 * Login state survives a cold start the same way desktop does: `checkAuth()` reads
 * `/api/auth/me`, and on 401 mints a fresh access token from the HttpOnly refresh cookie.
 */
export const MobileGate: React.FC = () => {
  const loadSettings = useSettingsStore((state) => state.loadSettings);
  const { currentProject, workspaceOnly, loadProjects } = useProjectStore();
  const projectSelectionEnabled = useFeatureStore((state) => state.projectSelectionEnabled);
  const { isAuthenticated, authLoading, checkAuth } = useAuthStore();
  const loadCategories = useCategoryStore((state) => state.loadCategories);
  const [setupMode, setSetupMode] = useState<boolean | null>(null);

  useEffect(() => {
    setTheme(storageService.getSettings().theme);
  }, []);

  useEffect(() => {
    const afterAuth = (ok: boolean) => {
      if (!ok) return;
      loadSettings();
      loadProjects();
      loadCategories();
    };

    setupService
      .getStatus()
      .then((status) => {
        useFeatureStore.getState().setProjectSelectionEnabled(status.projectSelectionEnabled !== false);
        if (status.mode === 'setup') {
          setSetupMode(true);
          return;
        }
        setSetupMode(false);
        void checkAuth().then(afterAuth);
      })
      .catch(() => {
        setSetupMode(false);
        void checkAuth().then(afterAuth);
      });
  }, [checkAuth, loadSettings, loadProjects, loadCategories]);

  if (setupMode === null || (setupMode === false && authLoading)) {
    return (
      <div className="mobile-app">
        <div className="flex-1 flex items-center justify-center">
          <Loader2 className="w-8 h-8 animate-spin text-muted-foreground" />
        </div>
      </div>
    );
  }

  if (setupMode) {
    return (
      <div className="mobile-app">
        <div className="flex-1 flex flex-col items-center justify-center gap-3 px-8 text-center">
          <Monitor className="w-8 h-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">{i18n('Finish the database setup on a desktop browser first.')}</p>
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="mobile-app">
        <MobileLoginPage />
      </div>
    );
  }

  if (projectSelectionEnabled && !currentProject && !workspaceOnly) {
    return (
      <div className="mobile-app">
        <MobileProjectPage />
      </div>
    );
  }

  return <Outlet />;
};
