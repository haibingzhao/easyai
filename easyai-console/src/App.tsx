import { lazy, Suspense, useEffect, useState } from 'react';
import { HashRouter, Routes, Route } from 'react-router-dom';
import { AppLayout } from './components/layout/AppLayout';
import { LoginPage } from './pages/LoginPage';
import { DatabaseSetupPage } from './pages/DatabaseSetupPage';
import { ProjectSelectPage } from './components/project/ProjectSelectPage';
import { useSettingsStore } from '@/services/stores/settings-store';
import { useProjectStore } from '@/services/stores/project-store';
import { useAuthStore } from '@/services/stores/auth-store';
import { useChatStore } from '@/services/stores/chat-store';
import { setupService } from '@/services/setup-service';
import { storageService } from '@/services/storage-service';
import { setTheme } from '@/utils/theme';
import { Loader2 } from 'lucide-react';
import './index.css';

const ChatPanel = lazy(() =>
  import('./components/chat/ChatPanel').then(m => ({ default: m.ChatPanel }))
);
const WorkflowPage = lazy(() =>
  import('./pages/WorkflowPage').then(m => ({ default: m.WorkflowPage }))
);
const SettingsPage = lazy(() =>
  import('./pages/SettingsPage').then(m => ({ default: m.SettingsPage }))
);
const AgentCreatePage = lazy(() =>
  import('./pages/AgentCreatePage').then(m => ({ default: m.AgentCreatePage }))
);
const AgentsPage = lazy(() =>
  import('./pages/AgentsPage').then(m => ({ default: m.AgentsPage }))
);
const ModelsPage = lazy(() =>
  import('./pages/ModelsPage').then(m => ({ default: m.ModelsPage }))
);
const McpPage = lazy(() =>
  import('./pages/McpPage').then(m => ({ default: m.McpPage }))
);
const MemoriesPage = lazy(() =>
  import('./pages/MemoriesPage').then(m => ({ default: m.MemoriesPage }))
);
const CommandsPage = lazy(() =>
  import('./pages/CommandsPage').then(m => ({ default: m.CommandsPage }))
);
const SwarmPresetEditorPage = lazy(() =>
  import('./pages/SwarmPresetEditorPage').then(m => ({ default: m.SwarmPresetEditorPage }))
);
const WorkflowRunPage = lazy(() =>
  import('./pages/WorkflowRunPage').then(m => ({ default: m.WorkflowRunPage }))
);

const LoadingFallback: React.FC = () => (
  <div className="w-full h-full flex items-center justify-center">
    <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
  </div>
);

function App() {
  const loadSettings = useSettingsStore((state) => state.loadSettings);
  const { currentProject, loadProjects } = useProjectStore();
  const { isAuthenticated, authLoading, checkAuth } = useAuthStore();
  const [setupMode, setSetupMode] = useState<boolean | null>(null);

  useEffect(() => {
    // Apply theme immediately from localStorage so login page also respects it
    const settings = storageService.getSettings();
    setTheme(settings.theme);
  }, []);

  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      const { isStreaming } = useChatStore.getState();
      if (isStreaming) {
        e.preventDefault();
        e.returnValue = '正在流式响应中，确定要离开吗？';
      }
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
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
          }
        });
      });
  }, [checkAuth, loadSettings, loadProjects]);

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
            element={
              <Suspense fallback={<LoadingFallback />}>
                <ChatPanel />
              </Suspense>
            }
          />
          <Route
            path="/workflow"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <WorkflowPage />
              </Suspense>
            }
          />
          <Route
            path="/workflow/create"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <SwarmPresetEditorPage />
              </Suspense>
            }
          />
          <Route
            path="/workflow/edit/:name"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <SwarmPresetEditorPage />
              </Suspense>
            }
          />
          <Route
            path="/workflow/:name/run"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <WorkflowRunPage />
              </Suspense>
            }
          />
          <Route
            path="/settings"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <SettingsPage />
              </Suspense>
            }
          />
          <Route
            path="/agents"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <AgentsPage />
              </Suspense>
            }
          />
          <Route
            path="/agents/create"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <AgentCreatePage />
              </Suspense>
            }
          />
          <Route
            path="/agents/edit/:id"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <AgentCreatePage />
              </Suspense>
            }
          />
          <Route
            path="/models"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <ModelsPage />
              </Suspense>
            }
          />
          <Route
            path="/mcp"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <McpPage />
              </Suspense>
            }
          />
          <Route
            path="/memories"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <MemoriesPage />
              </Suspense>
            }
          />
          <Route
            path="/commands"
            element={
              <Suspense fallback={<LoadingFallback />}>
                <CommandsPage />
              </Suspense>
            }
          />
        </Route>
      </Routes>
    </HashRouter>
  );
}

export default App;
