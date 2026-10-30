/**
 * The media element chat read-aloud speaks through, and the iOS rules it has to satisfy.
 *
 * Desktop browsers happily play a clip that is fetched first and played afterwards. Safari on iOS
 * does not: `HTMLMediaElement.play()` only starts with sound inside a user gesture, and the MP3 does
 * not exist at gesture time — it has to be synthesized upstream first. The `play()` that arrives
 * after that `await` is therefore rejected with `NotAllowedError`, and in a Home-Screen app it can
 * even stay pending forever, which is exactly "I tapped it and nothing happened".
 *
 * The remedy is the long-standing unlock pattern: during the tap itself, `play()` *the same element*
 * on a silent clip. That marks the element as user-activated, and later programmatic `play()` calls
 * on it are allowed no matter how much async work happens in between. Because the mark is per
 * element and only obtainable from a gesture, this module owns exactly one element for the page:
 *  - it is created lazily and never replaced, so every clip inherits the unlock;
 *  - [primeTtsAudio] must be called synchronously from the click handler, before the first `await`.
 *
 * Two smaller iOS details live here as well. The element is appended to the document, because a
 * detached `new Audio()` is unreliable in standalone mode (where `playsinline` is also required).
 * And `<audio>` follows the hardware ring/silent switch unless the page declares a `playback` audio
 * session (Safari 16.4+): read-aloud is content the user explicitly asked for, so it should sound
 * like a podcast rather than like a UI blip — a silent-switch phone is otherwise a total, invisible
 * "no sound" on mobile while the desktop is fine.
 */

/** Eight silent 8-bit samples at 8 kHz: long enough to be a playable resource, too short to hear. */
const SILENT_CLIP =
  'data:audio/wav;base64,UklGRiwAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQgAAACAgICAgICAgA==';

/** The audio-session types this module sets; anything else is left for the platform to decide. */
type AudioSessionType = 'auto' | 'playback';

/** `navigator.audioSession` is Safari-only and not in the DOM typings yet. */
interface AudioSessionNavigator extends Navigator {
  audioSession?: { type: AudioSessionType };
}

let element: HTMLAudioElement | null = null;

/**
 * The one element read-aloud plays through.
 *
 * Reusing it is a correctness requirement, not an optimization: an unlock obtained on one element
 * does not carry over to another, so a fresh `new Audio()` per clip would go back to being blocked.
 */
export function ttsAudioElement(): HTMLAudioElement {
  if (element) return element;
  const audio = document.createElement('audio');
  audio.preload = 'auto';
  // iOS keeps the clip inside the page instead of handing it to the system player; a Home-Screen
  // app is the context where omitting it is most likely to fail.
  audio.setAttribute('playsinline', '');
  audio.setAttribute('webkit-playsinline', '');
  audio.dataset.ttsPlayer = '';
  document.body.appendChild(audio);
  element = audio;
  return audio;
}

function audioSession(): { type: AudioSessionType } | undefined {
  return (navigator as AudioSessionNavigator).audioSession;
}

/** Declare a playback session so iOS ignores the ring/silent switch. No-op elsewhere. */
function claimAudioSession(): void {
  const session = audioSession();
  if (!session) return;
  session.type = 'playback';
}

/** Give the session back once nothing is playing, so background music resumes as before. */
export function endTtsAudioSession(): void {
  const session = audioSession();
  if (session?.type === 'playback') session.type = 'auto';
}

/**
 * Mark the shared element as user-activated. Must run synchronously inside the click that starts a
 * clip — anything awaited before it has already lost the gesture on Safari.
 *
 * Safe to call repeatedly: the silent clip is a 44-byte resource, and re-priming costs one no-op
 * play on a platform that needed no priming at all.
 */
export function primeTtsAudio(): void {
  const audio = ttsAudioElement();
  claimAudioSession();
  audio.src = SILENT_CLIP;
  // This promise is expected to reject with AbortError as soon as the real clip replaces the silent
  // source; the unlock happened when `play()` was called, not when it resolved.
  void audio.play().catch(() => undefined);
}
