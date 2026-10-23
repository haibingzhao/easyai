import { beforeEach, describe, expect, it, vi } from 'vitest';
import { capabilityService } from '@/services/capability-service';
import type { Capabilities } from '@/services/capability-service';
import { useCapabilityStore } from '@/services/stores/capability-store';

vi.mock('@/services/capability-service', () => ({
  capabilityService: { get: vi.fn() },
}));

const capabilities = (groupId: string): Capabilities => ({
  groupId,
  groupUserId: null,
  isGroupOwner: false,
  setupMode: 'database',
  assets: {},
});

const get = vi.mocked(capabilityService.get);

beforeEach(() => {
  useCapabilityStore.setState({ capabilities: null, loaded: false });
  get.mockReset();
});

describe('capability store load ordering', () => {
  it('ignores a stale probe that resolves after a newer one', async () => {
    let resolveOld!: (value: Capabilities) => void;
    let resolveNew!: (value: Capabilities) => void;
    get
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve; }));

    const oldLoad = useCapabilityStore.getState().load();
    const newLoad = useCapabilityStore.getState().load();

    resolveNew(capabilities('family-new'));
    await newLoad;
    resolveOld(capabilities('family-old'));
    await oldLoad;

    expect(useCapabilityStore.getState().capabilities?.groupId).toBe('family-new');
  });

  it('a clear invalidates an in-flight probe', async () => {
    let resolveLoad!: (value: Capabilities) => void;
    get.mockImplementationOnce(() => new Promise((resolve) => { resolveLoad = resolve; }));

    const load = useCapabilityStore.getState().load();
    useCapabilityStore.getState().clear();
    resolveLoad(capabilities('family-old'));
    await load;

    expect(useCapabilityStore.getState().capabilities).toBeNull();
    expect(useCapabilityStore.getState().loaded).toBe(false);
  });
});
