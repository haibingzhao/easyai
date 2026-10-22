import { afterEach, describe, expect, it } from 'vitest';
import { APP_CONFIG } from '@/constants/navigation';
import { canConfigureAutoApprove } from './auto-approve-policy';

describe('canConfigureAutoApprove', () => {
  afterEach(() => {
    APP_CONFIG.canConfigureAutoApprove = undefined;
  });

  it('hides the entry point without a project', () => {
    expect(canConfigureAutoApprove(undefined)).toBe(false);
  });

  it('defaults to visible when the host sets no policy', () => {
    expect(canConfigureAutoApprove('project-1')).toBe(true);
  });

  it('honours a host predicate that denies access', () => {
    APP_CONFIG.canConfigureAutoApprove = () => false;
    expect(canConfigureAutoApprove('project-1')).toBe(false);
  });
});
