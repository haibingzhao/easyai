import { useEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/services/stores/auth-store';
import { normalizeAvatarImage } from '@/utils/avatar-image';
import { LogIn, UserPlus, Loader2 } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { ThemeToggle } from '@/components/layout/ThemeToggle';

export function LoginPage() {
  const { login, register } = useAuthStore();
  const [isRegister, setIsRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => () => {
    if (avatarPreview) URL.revokeObjectURL(avatarPreview);
  }, [avatarPreview]);

  const pickAvatar = async (file: File | undefined) => {
    if (!file) return;
    setError('');
    try {
      const normalized = await normalizeAvatarImage(file);
      setAvatarFile(normalized);
      setAvatarPreview(URL.createObjectURL(normalized));
    } catch (err) {
      setError((err as Error).message);
    }
  };

  const clearAvatar = () => {
    setAvatarPreview(null);
    setAvatarFile(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      if (isRegister) {
        await register(username, password, email, displayName || undefined, avatarFile ?? undefined);
      } else {
        await login(username, password);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4 relative">
      {/* Theme toggle - top right corner */}
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-foreground">EasyAI</h1>
          <p className="text-muted-foreground text-sm mt-1">
            {isRegister ? i18n('Create a new account') : i18n('Sign in to your account')}
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {isRegister && (
            <div>
              <span className="block text-sm font-medium text-foreground mb-1">
                {i18n('Avatar')}
              </span>
              <div className="flex items-center gap-3">
                {avatarPreview ? (
                  <div className="w-12 h-12 rounded-full overflow-hidden border border-border shrink-0">
                    <img src={avatarPreview} alt="" className="w-full h-full object-cover" />
                  </div>
                ) : (
                  <div className="w-12 h-12 rounded-full border border-dashed border-border shrink-0 flex items-center justify-center text-xs text-muted-foreground">
                    ?
                  </div>
                )}
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => fileInput.current?.click()}
                      className="px-3 py-1.5 text-sm rounded-lg border border-border hover:bg-muted transition-colors"
                    >
                      {i18n('Choose an image')}
                    </button>
                    {avatarFile && (
                      <button
                        type="button"
                        onClick={clearAvatar}
                        className="text-sm text-muted-foreground hover:underline"
                      >
                        {i18n('Remove')}
                      </button>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">
                    {i18n('Optional — cropped to a square automatically')}
                  </p>
                </div>
              </div>
              <input
                ref={fileInput}
                type="file"
                accept="image/png,image/jpeg,image/webp,image/gif"
                className="hidden"
                onChange={(e) => {
                  void pickAvatar(e.target.files?.[0]);
                  e.target.value = '';
                }}
              />
            </div>
          )}

          {isRegister && (
            <div>
              <label htmlFor="displayName" className="block text-sm font-medium text-foreground mb-1">
                {i18n('Display Name')}
              </label>
              <input
                id="displayName"
                type="text"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                  focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder={i18n('Your nickname')}
              />
            </div>
          )}

          <div>
            <label htmlFor="username" className="block text-sm font-medium text-foreground mb-1">
              {i18n('Username')}
            </label>
            <input
              id="username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              minLength={3}
              className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder={i18n('At least 3 characters')}
            />
          </div>

          {isRegister && (
            <div>
              <label htmlFor="email" className="block text-sm font-medium text-foreground mb-1">
                {i18n('Email')}
              </label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                  focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="you@example.com"
              />
            </div>
          )}

          <div>
            <label htmlFor="password" className="block text-sm font-medium text-foreground mb-1">
              {i18n('Password')}
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={6}
              className="w-full px-3 py-2 rounded-lg border border-border bg-background text-foreground
                focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder={i18n('At least 6 characters')}
            />
          </div>

          {error && (
            <p className="text-sm text-destructive">{error}</p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full flex items-center justify-center gap-2 px-4 py-2 rounded-lg
              bg-foreground text-background font-medium
              hover:opacity-90 disabled:opacity-50 transition-opacity"
          >
            {loading ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : isRegister ? (
              <UserPlus className="w-4 h-4" />
            ) : (
              <LogIn className="w-4 h-4" />
            )}
            {isRegister ? i18n('Register') : i18n('Login')}
          </button>
        </form>

        <p className="text-center text-sm text-muted-foreground mt-6">
          {isRegister ? i18n('Already have an account?') : i18n("Don't have an account?")}{' '}
          <button
            type="button"
            onClick={() => { setIsRegister(!isRegister); setError(''); }}
            className="text-foreground underline hover:no-underline"
          >
            {isRegister ? i18n('Go to login') : i18n('Register')}
          </button>
        </p>
      </div>
    </div>
  );
}
