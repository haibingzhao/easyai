import React from 'react';

interface VolumeLoadingIconProps {
  className?: string;
}

/**
 * A speaker with three trailing dots: the click registered but the clip has not started yet.
 *
 * lucide has no such glyph — `Volume1` puts a wave arc where these dots belong, and that reads as
 * "playing quietly" rather than "still fetching". The speaker outline is copied from lucide's
 * `Volume2` so the three states of the read-aloud button share one silhouette.
 */
export const VolumeLoadingIcon: React.FC<VolumeLoadingIconProps> = ({ className }) => (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    strokeLinecap="round"
    strokeLinejoin="round"
    className={className}
    aria-hidden="true"
  >
    <path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z" />
    {/* Filled rather than stroked: a 1 px stroked dot disappears at the 12 px size this renders at. */}
    <circle cx="15.5" cy="12" r="1" fill="currentColor" stroke="none" />
    <circle cx="18.5" cy="12" r="1" fill="currentColor" stroke="none" />
    <circle cx="21.5" cy="12" r="1" fill="currentColor" stroke="none" />
  </svg>
);
