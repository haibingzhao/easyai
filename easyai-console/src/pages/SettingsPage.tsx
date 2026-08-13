import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useSettingsStore } from '@/services/stores/settings-store';
import { useAuthStore } from '@/services/stores/auth-store';
import { setupService } from '@/services/setup-service';
import type { DatabaseInfo, DatabaseSetupRequest } from '@/services/setup-service';
import { IntegrationsTab } from '@/components/settings/IntegrationsTab';
import { RagTab } from '@/components/settings/RagTab';
import { i18n } from '@/utils/i18n';
import {
  User,
  Settings,
  Info,
  Database,
  Loader2,
  CheckCircle2,
  AlertCircle,
  RefreshCw,
  LogOut,
  Sparkles,
  Globe,
  Layers
} from 'lucide-react';

interface MenuItem {
  id: string;
  label: string;
  icon: React.ReactNode;
}

const menuItems: MenuItem[] = [
  { id: 'account', label: 'Account', icon: <User className="w-4 h-4" /> },
  { id: 'general', label: 'General', icon: <Settings className="w-4 h-4" /> },
  { id: 'integrations', label: 'Integrations', icon: <Globe className="w-4 h-4" /> },
  { id: 'rag', label: 'RAG', icon: <Layers className="w-4 h-4" /> },
  { id: 'database', label: 'Database', icon: <Database className="w-4 h-4" /> },
  { id: 'about', label: 'About', icon: <Info className="w-4 h-4" /> },
];

export const SettingsPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const activeTab = searchParams.get('tab') || 'general';

  // Redirect removed tabs to their new standalone pages
  useEffect(() => {
    if (activeTab === 'models') {
      navigate('/models', { replace: true });
    } else if (activeTab === 'agent') {
      navigate('/agents', { replace: true });
    } else if (activeTab === 'mcp') {
      // Defensive redirect: handles legacy /settings?tab=mcp URLs (e.g. old bookmarks/links)
      navigate('/mcp', { replace: true });
    }
  }, [activeTab, navigate]);

  const handleMenuClick = (tabId: string) => {
    navigate(`/settings?tab=${tabId}`);
  };

  return (
    <div className="flex h-full overflow-hidden">
      <aside className="w-56 border-r border-border p-4 space-y-1 overflow-y-auto shrink-0">
        {menuItems.map(item => (
          <button
            key={item.id}
            onClick={() => handleMenuClick(item.id)}
            className={`w-full flex items-center gap-2 px-3 py-2 text-sm rounded-md transition-colors ${
              activeTab === item.id
                ? 'bg-muted font-medium'
                : 'hover:bg-muted'
            }`}
          >
            {item.icon}
            <span>{i18n(item.label)}</span>
          </button>
        ))}
      </aside>

      <main className="flex-1 overflow-y-auto p-6">
        <h1 className="text-2xl font-semibold mb-6">{i18n(menuItems.find(m => m.id === activeTab)?.label || 'Settings')}</h1>

        {activeTab === 'account' && <AccountTab />}
        {activeTab === 'general' && <GeneralTab />}
        {activeTab === 'integrations' && <IntegrationsTab />}
        {activeTab === 'rag' && <RagTab />}
        {activeTab === 'database' && <DatabaseTab />}
        {activeTab === 'about' && <AboutTab />}
      </main>
    </div>
  );
};

const AccountTab: React.FC = () => {
  const { user, logout } = useAuthStore();
  const [loggingOut, setLoggingOut] = useState(false);

  const handleLogout = async () => {
    setLoggingOut(true);
    await logout();
  };

  const initial = (user?.displayName || user?.username || '?').charAt(0).toUpperCase();
  const avatarUrl = user?.avatar && /^(https?:\/\/|data:)/.test(user.avatar) ? user.avatar : null;

  return (
    <div className="space-y-6 max-w-lg">
      {/* Profile card */}
      <div className="p-5 rounded-lg border border-border">
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 rounded-full bg-primary/15 text-primary flex items-center justify-center text-xl font-semibold shrink-0">
            {avatarUrl ? (
              <img src={avatarUrl} alt="" className="w-full h-full rounded-full object-cover" />
            ) : (
              initial
            )}
          </div>
          <div className="min-w-0">
            <p className="font-medium text-lg truncate">{user?.displayName || user?.username || '-'}</p>
            <p className="text-sm text-muted-foreground truncate">@{user?.username || '-'}</p>
          </div>
        </div>
      </div>

      {/* Details */}
      <div>
        <h2 className="text-lg font-medium mb-3">{i18n('Profile')}</h2>
        <div className="rounded-lg border border-border divide-y divide-border">
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm text-muted-foreground">{i18n('Username')}</span>
            <span className="text-sm font-medium">{user?.username || '-'}</span>
          </div>
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm text-muted-foreground">{i18n('Display Name')}</span>
            <span className="text-sm font-medium">{user?.displayName || '-'}</span>
          </div>
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm text-muted-foreground">{i18n('Email')}</span>
            <span className="text-sm font-medium">{user?.email || i18n('Not set')}</span>
          </div>
        </div>
      </div>

      {/* Logout */}
      <div className="pt-2">
        <button
          onClick={handleLogout}
          disabled={loggingOut}
          className="flex items-center gap-2 px-4 py-2 rounded-lg border border-destructive/30 text-destructive
            hover:bg-destructive/10 disabled:opacity-50 transition-colors text-sm"
        >
          {loggingOut ? <Loader2 className="w-4 h-4 animate-spin" /> : <LogOut className="w-4 h-4" />}
          {i18n('Logout')}
        </button>
      </div>
    </div>
  );
};

const AboutTab: React.FC = () => {
  return (
    <div className="space-y-6 max-w-lg">
      {/* App identity */}
      <div className="p-6 rounded-lg border border-border text-center">
        <div className="w-14 h-14 rounded-2xl bg-primary/15 text-primary flex items-center justify-center mx-auto mb-4">
          <Sparkles className="w-7 h-7" />
        </div>
        <h2 className="text-xl font-semibold">EasyAI</h2>
        <p className="text-sm text-muted-foreground mt-1">
          {i18n('Your AI-powered coding companion')}
        </p>
        <span className="inline-block mt-3 px-2.5 py-0.5 rounded-full bg-muted text-xs font-medium text-muted-foreground">
          v0.1.0
        </span>
      </div>

      {/* Tech stack */}
      <div>
        <h2 className="text-lg font-medium mb-3">{i18n('Tech Stack')}</h2>
        <div className="rounded-lg border border-border divide-y divide-border">
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm text-muted-foreground">{i18n('Backend')}</span>
            <span className="text-sm font-medium">Kotlin · Spring Boot · Spring AI</span>
          </div>
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm text-muted-foreground">{i18n('Frontend')}</span>
            <span className="text-sm font-medium">React · TypeScript · Tailwind CSS</span>
          </div>
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm text-muted-foreground">{i18n('Database')}</span>
            <span className="text-sm font-medium">H2 · PostgreSQL (R2DBC)</span>
          </div>
        </div>
      </div>

      {/* Copyright */}
      <p className="text-xs text-muted-foreground text-center">
        © {new Date().getFullYear()} EasyAI. All rights reserved.
      </p>
    </div>
  );
};

const GeneralTab: React.FC = () => {
  const settings = useSettingsStore();

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-medium mb-4">{i18n('Basic Settings')}</h2>
        <div className="space-y-4">
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Theme')}</label>
            <select
              value={settings.theme}
              onChange={(e) => {
                settings.updateSettings({ theme: e.target.value as 'light' | 'dark' | 'system' });
                settings.saveSettings();
              }}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="light">{i18n('Light')}</option>
              <option value="dark">{i18n('Dark')}</option>
              <option value="system">{i18n('System')}</option>
            </select>
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">{i18n('Language')}</label>
            <select
              value={settings.language}
              onChange={(e) => {
                settings.updateSettings({ language: e.target.value });
                settings.saveSettings();
              }}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="en">English</option>
              <option value="zh">中文</option>
            </select>
          </div>
        </div>
      </div>
    </div>
  );
};

const DatabaseTab: React.FC = () => {
  const [dbInfo, setDbInfo] = useState<DatabaseInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [dbType, setDbType] = useState<'h2' | 'postgres'>('h2');
  const [postgresUrl, setPostgresUrl] = useState('r2dbc:postgresql://localhost:5432/easyai');
  const [postgresUsername, setPostgresUsername] = useState('');
  const [postgresPassword, setPostgresPassword] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [applying, setApplying] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setupService.getDatabaseInfo()
      .then((info) => {
        setDbInfo(info);
        setDbType(info.dbType === 'postgres' ? 'postgres' : 'h2');
      })
      .catch(() => { /* ignore */ })
      .finally(() => setLoading(false));
  }, []);

  const buildRequest = (): DatabaseSetupRequest => ({
    dbType,
    h2Dir: null,
    postgresUrl: dbType === 'postgres' ? postgresUrl : null,
    postgresUsername: dbType === 'postgres' ? postgresUsername : null,
    postgresPassword: dbType === 'postgres' ? postgresPassword : null,
  });

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    setError('');
    try {
      const result = await setupService.testConnectionAuth(buildRequest());
      setTestResult(result);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setTesting(false);
    }
  };

  const handleApply = async () => {
    setApplying(true);
    setError('');
    try {
      const result = await setupService.applyConfig(buildRequest());
      if (result.success && result.restartRequired) {
        setRestarting(true);
        const restarted = await setupService.waitForRestart(30000);
        if (restarted) {
          window.location.reload();
        } else {
          setRestarting(false);
          setError('Backend restart timed out. Please restart manually.');
        }
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setApplying(false);
    }
  };

  if (loading) {
    return <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />;
  }

  if (restarting) {
    return (
      <div className="text-center py-12">
        <Loader2 className="w-8 h-8 animate-spin text-primary mx-auto mb-4" />
        <p className="font-medium">{i18n('Backend is restarting...')}</p>
        <p className="text-muted-foreground text-sm mt-1">{i18n('This may take a few seconds')}</p>
      </div>
    );
  }

  const dbTypeLabel = dbInfo?.dbType === 'postgres' ? 'PostgreSQL' : 'H2 (Embedded)';

  return (
    <div className="space-y-6">
      {/* Current database info */}
      <div>
        <h2 className="text-lg font-medium mb-4">{i18n('Current Database')}</h2>
        <div className="p-4 rounded-lg border border-border space-y-2">
          <div className="flex items-center gap-2">
            <Database className="w-4 h-4 text-primary" />
            <span className="font-medium">{dbTypeLabel}</span>
          </div>
          {dbInfo?.info?.url && (
            <p className="text-sm text-muted-foreground font-mono">{dbInfo.info.url}</p>
          )}
        </div>
      </div>

      {/* Change database */}
      {!editing ? (
        <button
          onClick={() => setEditing(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-lg border border-border
            text-foreground hover:bg-muted transition-colors text-sm"
        >
          <RefreshCw className="w-4 h-4" />
          {i18n('Change Database')}
        </button>
      ) : (
        <div className="space-y-4">
          <h2 className="text-lg font-medium">{i18n('Change Database')}</h2>

          {/* Warning */}
          <div className="flex items-start gap-2 p-3 rounded-lg bg-yellow-500/10 text-yellow-700 dark:text-yellow-400 text-sm">
            <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
            <span>
              {i18n('Changing database will restart the backend. All active sessions will be lost. You will need to login again.')}
            </span>
          </div>

          {/* DB type selection */}
          <div className="space-y-2">
            <button
              type="button"
              onClick={() => { setDbType('h2'); setTestResult(null); }}
              className={`w-full text-left p-3 rounded-lg border-2 transition-all ${
                dbType === 'h2' ? 'border-primary bg-primary/5' : 'border-border'
              }`}
            >
              <span className="font-medium text-sm">H2 (Embedded)</span>
              <p className="text-xs text-muted-foreground">{i18n('Zero configuration, data stored locally')}</p>
            </button>
            <button
              type="button"
              onClick={() => { setDbType('postgres'); setTestResult(null); }}
              className={`w-full text-left p-3 rounded-lg border-2 transition-all ${
                dbType === 'postgres' ? 'border-primary bg-primary/5' : 'border-border'
              }`}
            >
              <span className="font-medium text-sm">PostgreSQL</span>
              <p className="text-xs text-muted-foreground">{i18n('For production or multi-user deployment')}</p>
            </button>
          </div>

          {/* PostgreSQL form */}
          {dbType === 'postgres' && (
            <div className="space-y-3 p-4 rounded-lg border border-border">
              <div>
                <label className="text-sm font-medium mb-1 block">{i18n('Connection URL')}</label>
                <input
                  type="text"
                  value={postgresUrl}
                  onChange={(e) => setPostgresUrl(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                    focus:outline-none focus:ring-2 focus:ring-ring text-sm font-mono"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-sm font-medium mb-1 block">{i18n('Username')}</label>
                  <input
                    type="text"
                    value={postgresUsername}
                    onChange={(e) => setPostgresUsername(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                      focus:outline-none focus:ring-2 focus:ring-ring text-sm"
                  />
                </div>
                <div>
                  <label className="text-sm font-medium mb-1 block">{i18n('Password')}</label>
                  <input
                    type="password"
                    value={postgresPassword}
                    onChange={(e) => setPostgresPassword(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                      focus:outline-none focus:ring-2 focus:ring-ring text-sm"
                  />
                </div>
              </div>
            </div>
          )}

          {/* Test result */}
          {testResult && (
            <div className={`flex items-center gap-2 p-3 rounded-lg text-sm ${
              testResult.success
                ? 'bg-green-500/10 text-green-600 dark:text-green-400'
                : 'bg-destructive/10 text-destructive'
            }`}>
              {testResult.success ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
              {testResult.message}
            </div>
          )}

          {error && <p className="text-sm text-destructive">{error}</p>}

          {/* Actions */}
          <div className="flex gap-3">
            {dbType === 'postgres' && (
              <button
                onClick={handleTest}
                disabled={testing || !postgresUrl}
                className="flex items-center gap-2 px-4 py-2 rounded-lg border border-border
                  text-foreground hover:bg-muted disabled:opacity-50 transition-colors text-sm"
              >
                {testing ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
                {i18n('Test Connection')}
              </button>
            )}
            <button
              onClick={handleApply}
              disabled={applying || (dbType === 'postgres' && !postgresUrl)}
              className="flex items-center gap-2 px-4 py-2 rounded-lg bg-foreground text-background
                font-medium hover:opacity-90 disabled:opacity-50 transition-opacity text-sm"
            >
              {applying && <Loader2 className="w-4 h-4 animate-spin" />}
              {i18n('Apply & Restart')}
            </button>
            <button
              onClick={() => { setEditing(false); setTestResult(null); setError(''); }}
              className="px-4 py-2 rounded-lg border border-border text-foreground
                hover:bg-muted transition-colors text-sm"
            >
              {i18n('Cancel')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
