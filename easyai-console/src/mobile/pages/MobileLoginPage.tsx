import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { useAuthStore } from '@/services/stores/auth-store';
import { APP_CONFIG } from '@/constants/navigation';
import { normalizeAvatarImage } from '@/utils/avatar-image';
import { i18n } from '@/utils/i18n';

/**
 * Mobile sign-in.
 *
 * Shares `useAuthStore` with the desktop page — same endpoints, same token application — and
 * only replaces the layout: one column, 56pt primary action, 16pt inputs (iOS zooms the whole
 * page on focus when a control renders under 16px).
 */
export const MobileLoginPage: React.FC = () => {
  const { login, register } = useAuthStore();
  const [isRegister, setIsRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

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

  return (
    <div className="flex-1 min-h-0 overflow-y-auto px-6 pt-16 pb-10">
      <div className="text-center mb-8">
        <h1 className="text-2xl font-bold">{APP_CONFIG.appName}</h1>
        <p className="text-muted-foreground text-sm mt-1">
          {isRegister ? i18n('Create a new account') : i18n('Sign in to your account')}
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        {isRegister && (
          <label className="flex items-center gap-3">
            {avatarPreview ? (
              <img src={avatarPreview} alt="" className="w-12 h-12 rounded-full object-cover border border-border shrink-0" />
            ) : (
              <span className="w-12 h-12 rounded-full border border-dashed border-border shrink-0 flex items-center justify-center text-xs text-muted-foreground">
                ?
              </span>
            )}
            <span className="text-sm text-muted-foreground">{i18n('Choose an image')}</span>
            <input
              type="file"
              accept="image/*"
              className="hidden"
              onChange={(e) => {
                void pickAvatar(e.target.files?.[0]);
              }}
            />
          </label>
        )}

        <Input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder={i18n('Username')}
          autoComplete="username"
          autoCapitalize="none"
          autoCorrect="off"
          required
          className="h-12"
        />
        {isRegister && (
          <>
            <Input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder={i18n('Display Name')}
              autoComplete="nickname"
              className="h-12"
            />
            <Input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={i18n('Email')}
              autoComplete="email"
              required
              className="h-12"
            />
          </>
        )}
        <Input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder={i18n('Password')}
          autoComplete={isRegister ? 'new-password' : 'current-password'}
          required
          className="h-12"
        />

        {error && <p className="text-sm text-destructive">{error}</p>}

        <Button type="submit" disabled={loading} className="w-full h-14 text-base rounded-xl">
          {loading && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
          {isRegister ? i18n('Register') : i18n('Sign In')}
        </Button>
      </form>

      <button
        className="w-full mt-4 text-sm text-muted-foreground"
        style={{ minHeight: 44 }}
        onClick={() => {
          setIsRegister((prev) => !prev);
          setError('');
        }}
      >
        {isRegister ? i18n('Sign in to your account') : i18n('Create a new account')}
      </button>
    </div>
  );
};
