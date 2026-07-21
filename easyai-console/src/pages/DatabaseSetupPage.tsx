import { useState } from 'react';
import { Database, HardDrive, Loader2, CheckCircle2, AlertCircle, RefreshCw } from 'lucide-react';
import { setupService } from '@/services/setup-service';
import type { DatabaseSetupRequest } from '@/services/setup-service';
import { ThemeToggle } from '@/components/layout/ThemeToggle';

type DbType = 'h2' | 'postgres';
type Step = 'select' | 'testing' | 'applying' | 'restarting' | 'done' | 'error';

export function DatabaseSetupPage() {
  const [dbType, setDbType] = useState<DbType>('h2');
  const [postgresUrl, setPostgresUrl] = useState('r2dbc:postgresql://localhost:5432/easyai');
  const [postgresUsername, setPostgresUsername] = useState('');
  const [postgresPassword, setPostgresPassword] = useState('');
  const [step, setStep] = useState<Step>('select');
  const [error, setError] = useState('');
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [testing, setTesting] = useState(false);

  const buildRequest = (): DatabaseSetupRequest => ({
    dbType,
    h2Dir: null,
    postgresUrl: dbType === 'postgres' ? postgresUrl : null,
    postgresUsername: dbType === 'postgres' ? postgresUsername : null,
    postgresPassword: dbType === 'postgres' ? postgresPassword : null,
  });

  const handleTestConnection = async () => {
    setError('');
    setTestResult(null);
    setTesting(true);
    try {
      const result = await setupService.testConnection(buildRequest());
      setTestResult(result);
      if (!result.success) {
        setError(result.message);
      }
    } catch (err) {
      setError((err as Error).message);
      setTestResult({ success: false, message: (err as Error).message });
    } finally {
      setTesting(false);
    }
  };

  const handleApply = async () => {
    setError('');
    setStep('applying');
    try {
      const result = await setupService.applySetup(buildRequest());
      if (result.success && result.restartRequired) {
        setStep('restarting');
        // Wait for backend to restart
        const restarted = await setupService.waitForRestart(30000);
        if (restarted) {
          setStep('done');
          // Reload the page to enter normal mode
          setTimeout(() => window.location.reload(), 1500);
        } else {
          setStep('error');
          setError('Backend restart timed out. Please restart the backend manually.');
        }
      } else if (result.success) {
        setStep('done');
        setTimeout(() => window.location.reload(), 1500);
      } else {
        setStep('error');
        setError(result.message);
      }
    } catch (err) {
      setStep('error');
      setError((err as Error).message);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4 relative">
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>

      <div className="w-full max-w-lg">
        {/* Header */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-xl bg-primary/10 mb-4">
            <Database className="w-6 h-6 text-primary" />
          </div>
          <h1 className="text-2xl font-bold text-foreground">Welcome to EasyAI</h1>
          <p className="text-muted-foreground text-sm mt-1">
            Choose a database to get started
          </p>
        </div>

        {/* Database type selection */}
        {step === 'select' && (
          <div className="space-y-4">
            {/* H2 Card */}
            <button
              type="button"
              onClick={() => { setDbType('h2'); setTestResult(null); setError(''); }}
              className={`w-full text-left p-4 rounded-xl border-2 transition-all ${
                dbType === 'h2'
                  ? 'border-primary bg-primary/5'
                  : 'border-border hover:border-muted-foreground/30'
              }`}
            >
              <div className="flex items-center gap-3">
                <HardDrive className="w-5 h-5 text-primary shrink-0" />
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-foreground">H2 (Embedded)</span>
                    <span className="text-xs px-1.5 py-0.5 rounded bg-primary/10 text-primary font-medium">
                      Recommended
                    </span>
                  </div>
                  <p className="text-sm text-muted-foreground mt-0.5">
                    Zero configuration. Data stored locally on disk.
                  </p>
                </div>
                {dbType === 'h2' && <CheckCircle2 className="w-5 h-5 text-primary shrink-0" />}
              </div>
            </button>

            {/* PostgreSQL Card */}
            <button
              type="button"
              onClick={() => { setDbType('postgres'); setTestResult(null); setError(''); }}
              className={`w-full text-left p-4 rounded-xl border-2 transition-all ${
                dbType === 'postgres'
                  ? 'border-primary bg-primary/5'
                  : 'border-border hover:border-muted-foreground/30'
              }`}
            >
              <div className="flex items-center gap-3">
                <Database className="w-5 h-5 text-primary shrink-0" />
                <div className="flex-1">
                  <span className="font-medium text-foreground">PostgreSQL</span>
                  <p className="text-sm text-muted-foreground mt-0.5">
                    For production or multi-user deployment.
                  </p>
                </div>
                {dbType === 'postgres' && <CheckCircle2 className="w-5 h-5 text-primary shrink-0" />}
              </div>
            </button>

            {/* PostgreSQL form */}
            {dbType === 'postgres' && (
              <div className="p-4 rounded-xl border border-border space-y-3">
                <div>
                  <label className="block text-sm font-medium text-foreground mb-1">
                    Connection URL
                  </label>
                  <input
                    type="text"
                    value={postgresUrl}
                    onChange={(e) => setPostgresUrl(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                      focus:outline-none focus:ring-2 focus:ring-ring text-sm font-mono"
                    placeholder="r2dbc:postgresql://localhost:5432/easyai"
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-sm font-medium text-foreground mb-1">
                      Username
                    </label>
                    <input
                      type="text"
                      value={postgresUsername}
                      onChange={(e) => setPostgresUsername(e.target.value)}
                      className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                        focus:outline-none focus:ring-2 focus:ring-ring text-sm"
                      placeholder="postgres"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-foreground mb-1">
                      Password
                    </label>
                    <input
                      type="password"
                      value={postgresPassword}
                      onChange={(e) => setPostgresPassword(e.target.value)}
                      className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                        focus:outline-none focus:ring-2 focus:ring-ring text-sm"
                      placeholder="••••••••"
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

            {/* Error */}
            {error && !testResult && (
              <p className="text-sm text-destructive">{error}</p>
            )}

            {/* Actions */}
            <div className="flex gap-3 pt-2">
              {dbType === 'postgres' && (
                <button
                  type="button"
                  onClick={handleTestConnection}
                  disabled={testing || !postgresUrl}
                  className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg
                    border border-border text-foreground font-medium
                    hover:bg-muted disabled:opacity-50 transition-colors"
                >
                  {testing ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
                  Test Connection
                </button>
              )}
              <button
                type="button"
                onClick={handleApply}
                disabled={dbType === 'postgres' && !postgresUrl}
                className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg
                  bg-foreground text-background font-medium
                  hover:opacity-90 disabled:opacity-50 transition-opacity"
              >
                Start
              </button>
            </div>
          </div>
        )}

        {/* Applying */}
        {step === 'applying' && (
          <div className="text-center py-12">
            <Loader2 className="w-8 h-8 animate-spin text-primary mx-auto mb-4" />
            <p className="text-foreground font-medium">Saving configuration...</p>
          </div>
        )}

        {/* Restarting */}
        {step === 'restarting' && (
          <div className="text-center py-12">
            <Loader2 className="w-8 h-8 animate-spin text-primary mx-auto mb-4" />
            <p className="text-foreground font-medium">Backend is restarting...</p>
            <p className="text-muted-foreground text-sm mt-1">This may take a few seconds</p>
          </div>
        )}

        {/* Done */}
        {step === 'done' && (
          <div className="text-center py-12">
            <CheckCircle2 className="w-8 h-8 text-green-500 mx-auto mb-4" />
            <p className="text-foreground font-medium">Setup complete!</p>
            <p className="text-muted-foreground text-sm mt-1">Redirecting...</p>
          </div>
        )}

        {/* Error */}
        {step === 'error' && (
          <div className="text-center py-12">
            <AlertCircle className="w-8 h-8 text-destructive mx-auto mb-4" />
            <p className="text-foreground font-medium">Something went wrong</p>
            <p className="text-muted-foreground text-sm mt-1">{error}</p>
            <button
              type="button"
              onClick={() => setStep('select')}
              className="mt-4 px-4 py-2 rounded-lg border border-border text-foreground
                hover:bg-muted transition-colors text-sm"
            >
              Try again
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
