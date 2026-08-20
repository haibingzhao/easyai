import { useEffect, useState } from 'react';
import { HashRouter, Routes, Route } from 'react-router-dom';
import { AppLayout } from './components/layout/AppLayout';
import { ChatPanel } from './components/chat/ChatPanel';
import { WorkflowPage } from './pages/WorkflowPage';
import { SettingsPage } from './pages/SettingsPage';
import { AgentCreatePage } from './pages/AgentCreatePage';
import { AgentsPage } from './pages/AgentsPage';
import { ModelsPage } from './pages/ModelsPage';
import { McpPage } from './pages/McpPage';
import { MemoriesPage } from './pages/MemoriesPage';
import { KnowledgePage } from './pages/KnowledgePage';
import { CommandsPage } from './pages/CommandsPage';
import { SwarmPresetEditorPage } from './pages/SwarmPresetEditorPage';
import { WorkflowRunPage } from './pages/WorkflowRunPage';
import { LoginPage } from './pages/LoginPage';
import { DatabaseSetupPage } from './pages/DatabaseSetupPage';
import { ProjectSelectPage } from './components/project/ProjectSelectPage';
import { useSettingsStore } from '@/services/stores/settings-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useAuthStore } from '@/services/stores/auth-store';
import { useCategoryStore } from '@/services/stores/category-store';
import { setupService } from '@/services/setup-service';
import { storageService } from '@/services/storage-service';
import { setTheme } from '@/utils/theme';
import { Loader2 } from 'lucide-react';
import './index.css';

function App() {
  const loadSettings = useSettingsStore((state) => state.loadSettings);
  const { currentProject, loadProjects } = useProjectStore();
  const { isAuthenticated, authLoading, checkAuth } = useAuthStore();
  const loadCategories = useCategoryStore((state) => state.loadCategories);
  const [setupMode, setSetupMode] = useState<boolean | null>(null);

  useEffect(() => {
    // Apply theme immediately from localStorage so login page also respects it
    const settings = storageService.getSettings();
    setTheme(settings.theme);
  }, []);

  // Check setup mode before auth
  useEffect(() => {
    setupService.getStatus()
      .then((status) => {
        if (status.mode === 'setup') {
          setSetupMode(true);
        } else {
          setSetupMode(false);
          // Proceed with auth check
          checkAuth().then((ok) => {
            if (ok) {
              loadSettings();
              loadProjects();
              loadCategories();
            }
          });
        }
      })
      .catch(() => {
        // If setup status fails, assume normal mode (backend may not have this endpoint)
        setSetupMode(false);
        checkAuth().then((ok) => {
          if (ok) {
            loadSettings();
            loadProjects();
            loadCategories();
          }
        });
      });
  }, [checkAuth, loadSettings, loadProjects, loadCategories]);

  // Loading state (checking setup mode or auth)
  if (setupMode === null || (setupMode === false && authLoading)) {
    return (
      <div className="w-full h-full flex items-center justify-center bg-background">
        <Loader2 className="w-8 h-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  // Setup mode: show database setup wizard
  if (setupMode) {
    return <DatabaseSetupPage />;
  }

  if (!isAuthenticated) {
    return <LoginPage />;
  }

  if (!currentProject) {
    return <ProjectSelectPage />;
  }

  return (
    <HashRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route
            path="/"
            element={<ChatPanel />}
          />
          <Route
            path="/workflow"
            element={<WorkflowPage />}
          />
          <Route
            path="/workflow/create"
            element={<SwarmPresetEditorPage />}
          />
          <Route
            path="/workflow/edit/:name"
            element={<SwarmPresetEditorPage />}
          />
          <Route
            path="/workflow/:name/run"
            element={<WorkflowRunPage />}
          />
          <Route
            path="/settings"
            element={<SettingsPage />}
          />
          <Route
            path="/agents"
            element={<AgentsPage />}
          />
          <Route
            path="/agents/create"
            element={<AgentCreatePage />}
          />
          <Route
            path="/agents/edit/:id"
            element={<AgentCreatePage />}
          />
          <Route
            path="/models"
            element={<ModelsPage />}
          />
          <Route
            path="/mcp"
            element={<McpPage />}
          />
          <Route
            path="/memories"
            element={<MemoriesPage />}
          />
          <Route
            path="/knowledge"
            element={<KnowledgePage />}
          />
          <Route
            path="/commands"
            element={<CommandsPage />}
          />
        </Route>
      </Routes>
    </HashRouter>
  );
}

export default App;
