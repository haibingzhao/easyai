import React from 'react';
import { useAvatarImage } from '@/hooks/useAvatarImage';
import { getAvatarColor, getInitials } from '@/utils/avatar-utils';

interface UserAvatarProps {
  /** `UserProfile.avatar`: the preset, an uploaded object key, or an external link. */
  avatar?: string | null;
  /** Nickname, or the username when there is none: what the initial letter is taken from. */
  name: string;
  /** Stable per account, so the fallback colour does not move when the nickname is edited. */
  seed: string;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
  /** Makes the avatar an entry point, e.g. to the profile editor. */
  onClick?: () => void;
  title?: string;
}

const SIZES = {
  sm: 'w-6 h-6 text-xs',
  md: 'w-10 h-10 text-sm',
  lg: 'w-14 h-14 text-xl',
} as const;

/**
 * The one way this console draws a user's face. Initials paint immediately and stay until a picture is
 * actually available, so an unresolved, deleted or unreachable avatar is indistinguishable from no avatar.
 */
export const UserAvatar: React.FC<UserAvatarProps> = ({
  avatar,
  name,
  seed,
  size = 'md',
  className = '',
  onClick,
  title,
}) => {
  const { src, onError } = useAvatarImage(avatar);
  const shape = `relative shrink-0 overflow-hidden rounded-full flex items-center justify-center font-medium text-white ${SIZES[size]}`;
  const initials = (
    <span aria-hidden="true" className="leading-none">
      {getInitials(name)}
    </span>
  );
  const picture = src ? <img src={src} alt="" onError={onError} className="absolute inset-0 w-full h-full object-cover" /> : null;

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        title={title}
        style={{ backgroundColor: getAvatarColor(seed) }}
        className={`${shape} cursor-pointer transition-opacity hover:opacity-85 ${className}`}
      >
        {initials}
        {picture}
      </button>
    );
  }

  return (
    <div style={{ backgroundColor: getAvatarColor(seed) }} className={`${shape} ${className}`}>
      {initials}
      {picture}
    </div>
  );
};
