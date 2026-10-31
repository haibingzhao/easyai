import { describe, expect, it } from 'vitest';
import { planErrorText, submitOutcome } from '../TtsScriptPanel';
import { TtsError } from '@/services/tts-service';

/**
 * The panel shows one line of explanation when a plan cannot be built. The status is the only thing
 * that distinguishes "you have no model configured" from "this reply is too long", so each one has
 * to map to its own sentence rather than to a generic failure.
 */
describe('planErrorText', () => {
  it.each([
    [401, 'Sign in again to use read-aloud'],
    [409, 'Read-aloud needs a TTS model and a Speech Direction Model (Settings → Task Models)'],
    [413, 'This reply is too long to read aloud (max 20,000 characters)'],
    [422, 'The read-aloud script could not be built; check the Speech Direction Model or try again'],
    [429, 'The previous read-aloud is still running'],
  ])('maps status %i to its own explanation', (status, expected) => {
    expect(planErrorText(new TtsError('request refused', status))).toBe(expected);
  });

  it('falls back for a status it has nothing specific to say about', () => {
    expect(planErrorText(new TtsError('server exploded', 500))).toBe('Could not plan this reply');
  });

  it('falls back for a transport failure that never became an HTTP status', () => {
    expect(planErrorText(new TypeError('network down'))).toBe('Could not plan this reply');
  });
});

/**
 * "Read this plan" submits an edit the server re-validates against the source it stored, so it can
 * come back refused. Closing the dialog on the click would take the user's edited sentences with it
 * before they ever learned the edit did not take.
 */
describe('submitOutcome', () => {
  it('waits while the plan is still being prepared', () => {
    expect(submitOutcome({ state: 'loading', activeKey: 'm1', error: null }, 'm1')).toBe('waiting');
  });

  it('closes the panel once the first segment is audible', () => {
    expect(submitOutcome({ state: 'playing', activeKey: 'm1', error: null }, 'm1')).toBe('started');
  });

  it('closes the panel for a clip parked behind the autoplay policy', () => {
    // The plan was accepted; only the browser refused to start it, and one tap finishes the job.
    expect(submitOutcome({ state: 'ready', activeKey: 'm1', error: null }, 'm1')).toBe('started');
  });

  it('keeps the panel open when the edited script is refused', () => {
    expect(
      submitOutcome(
        { state: 'idle', activeKey: 'm1', error: 'The read-aloud script is no longer available' },
        'm1'
      )
    ).toBe('refused');
  });

  it('gives the button back when the player was stopped or taken over', () => {
    // No error to show: the user stopped it, or another reply started. The panel must not sit on a
    // disabled button waiting for a plan that no longer exists.
    expect(submitOutcome({ state: 'idle', activeKey: null, error: null }, 'm1')).toBe('cancelled');
    expect(submitOutcome({ state: 'idle', activeKey: 'm2', error: 'Playback failed' }, 'm1')).toBe('cancelled');
  });
});
