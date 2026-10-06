import React, { useEffect, useRef, useState } from 'react';
import { Image as ImageIcon, Link2, Loader2, Trash2 } from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { UserAvatar } from './UserAvatar';
import { useAuthStore } from '@/services/stores/auth-store';
import { readErrorMessage } from '@/services/api-client';
import { removeAvatar, setAvatarUrl, updateProfile, uploadAvatar } from '@/services/user-service';
import { normalizeAvatarImage } from '@/utils/avatar-image';
import { getAvatarSource } from '@/utils/avatar-utils';
import { i18n } from '@/utils/i18n';

interface ProfileEditDialogProps {
  open: boolean;
  onClose: () => void;
}

/**
 * The single profile editor: nickname, email and avatar. Every entry point opens this one dialog, and
 * every mutation it makes answers with the whole profile, so the auth store is written once at the end.
 */
export const ProfileEditDialog: React.FC<ProfileEditDialogProps> = ({ open, onClose }) => {
  const user = useAuthStore((state) => state.user);
  const applyProfile = useAuthStore((state) => state.applyProfile);
  const fileInput = useRef<HTMLInputElement>(null);

  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [mode, setMode] = useState<'upload' | 'link'>('upload');
  const [staged, setStaged] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [linkUrl, setLinkUrl] = useState('');
  const [removing, setRemoving] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open || !user) return;
    setDisplayName(user.displayName);
    setEmail(user.email ?? '');
    setMode('upload');
    setStaged(null);
    setPreviewUrl(null);
    setLinkUrl(getAvatarSource(user.avatar).kind === 'external' ? user.avatar : '');
    setRemoving(false);
    setError('');
  }, [open, user]);

  useEffect(() => () => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  if (!user) return null;
  const hasAvatar = getAvatarSource(user.avatar).kind !== 'initials';

  const pickFile = async (file: File | undefined) => {
    if (!file) return;
    setError('');
    try {
      // Resized here so a full-size phone photo fits the server's 1 MB avatar cap.
      const normalized = await normalizeAvatarImage(file);
      setStaged(normalized);
      setMode('upload');
      setRemoving(false);
      // The preview is the resized picture, so what you see is byte-for-byte what gets uploaded.
      setPreviewUrl(URL.createObjectURL(normalized));
    } catch (e) {
      setError(readErrorMessage(e, i18n('This image could not be read')));
    }
  };

  const handleSave = async () => {
    setError('');
    setSaving(true);
    try {
      const name = displayName.trim();
      const mail = email.trim();
      let profile = user;
      if (name !== profile.displayName || mail !== (profile.email ?? '')) {
        profile = await updateProfile({ displayName: name, email: mail });
      }
      if (staged) {
        profile = await uploadAvatar(staged);
      } else if (removing) {
        profile = await removeAvatar();
      } else {
        const link = linkUrl.trim();
        if (mode === 'link' && link && link !== profile.avatar) profile = await setAvatarUrl(link);
      }
      applyProfile(profile);
      onClose();
    } catch (e) {
      setError(readErrorMessage(e, i18n('Save failed')));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} title={i18n('Edit profile')}>
      <div className="space-y-5 max-w-md">
        <div className="flex items-center gap-4">
          {previewUrl ? (
            <div className="w-14 h-14 rounded-full overflow-hidden border border-border shrink-0">
              <img src={previewUrl} alt="" className="w-full h-full object-cover" />
            </div>
          ) : (
            <UserAvatar
              avatar={mode === 'link' && linkUrl.trim() ? linkUrl : (removing ? '' : user.avatar)}
              name={displayName.trim() || user.username}
              seed={user.id}
              size="lg"
            />
          )}
          <div className="min-w-0">
            <p className="text-sm text-muted-foreground">{i18n('Cropped to a square automatically')}</p>
            <p className="text-xs text-muted-foreground">{i18n('PNG, JPEG, WebP or GIF — 256×256 recommended')}</p>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <label className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium">{i18n('Display Name')}</span>
            <Input
              value={displayName}
              maxLength={64}
              placeholder={i18n('Your nickname')}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium">{i18n('Email')}</span>
            <Input
              type="email"
              value={email}
              placeholder="you@example.com"
              onChange={(e) => setEmail(e.target.value)}
            />
          </label>
        </div>

        <div>
          <div className="flex items-center gap-1 mb-2">
            <button
              type="button"
              onClick={() => setMode('upload')}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
                mode === 'upload' ? 'bg-muted font-medium' : 'text-muted-foreground hover:bg-muted'
              }`}
            >
              <ImageIcon className="w-4 h-4" />
              {i18n('Upload image')}
            </button>
            <button
              type="button"
              onClick={() => {
                setMode('link');
                // A staged picture would otherwise win the save silently, ignoring the typed link.
                setStaged(null);
                setPreviewUrl(null);
              }}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
                mode === 'link' ? 'bg-muted font-medium' : 'text-muted-foreground hover:bg-muted'
              }`}
            >
              <Link2 className="w-4 h-4" />
              {i18n('Image URL')}
            </button>
          </div>

          {mode === 'upload' ? (
            <div
              role="button"
              tabIndex={0}
              onClick={() => fileInput.current?.click()}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') fileInput.current?.click();
              }}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                void pickFile(e.dataTransfer.files[0]);
              }}
              className="flex items-center justify-center px-4 py-6 rounded-lg border border-dashed border-border
                text-sm text-muted-foreground cursor-pointer hover:bg-muted transition-colors"
            >
              {staged ? i18n('Choose another image') : i18n('Choose an image, or drop it here')}
            </div>
          ) : (
            <Input
              value={linkUrl}
              placeholder="https://example.com/me.png"
              onChange={(e) => {
                setLinkUrl(e.target.value);
                setRemoving(false);
              }}
            />
          )}

          {mode === 'upload' && (
            <input
              ref={fileInput}
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              className="hidden"
              onChange={(e) => {
                void pickFile(e.target.files?.[0]);
                e.target.value = '';
              }}
            />
          )}

          {hasAvatar && !staged && (
            <button
              type="button"
              onClick={() => {
                setRemoving(!removing);
                setLinkUrl('');
              }}
              className="mt-2 flex items-center gap-1.5 text-sm text-destructive hover:underline"
            >
              <Trash2 className="w-4 h-4" />
              {removing ? i18n('Keep current avatar') : i18n('Remove avatar')}
            </button>
          )}
        </div>

        {error && <p className="text-sm text-destructive">{error}</p>}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            {i18n('Cancel')}
          </Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving && <Loader2 className="w-4 h-4 animate-spin mr-1.5" />}
            {i18n('Save')}
          </Button>
        </div>
      </div>
    </Dialog>
  );
};
