import { describe, expect, it } from 'vitest';
import { resolveInitialSelection } from '@/utils/model-selection';

const enabledIds = ['model-a', 'model-b'];

describe('resolveInitialSelection', () => {
  it('keeps an explicit null selection when the host supports empty mode', () => {
    expect(resolveInitialSelection({
      enabledIds,
      selectedId: null,
      storedId: 'model-b',
      supportsEmptySelection: true,
    })).toEqual({ kind: 'unselected' });
  });

  it('resolves the controlled id while it still exists', () => {
    expect(resolveInitialSelection({
      enabledIds,
      selectedId: 'model-b',
      storedId: null,
      supportsEmptySelection: true,
    })).toEqual({ kind: 'model', configId: 'model-b' });
  });

  it('clears a controlled id that disappeared when empty mode is on', () => {
    expect(resolveInitialSelection({
      enabledIds,
      selectedId: 'deleted-model',
      storedId: 'model-a',
      supportsEmptySelection: true,
    })).toEqual({ kind: 'cleared' });
  });

  it('ignores empty mode when the host does not control the selection', () => {
    expect(resolveInitialSelection({
      enabledIds,
      selectedId: undefined,
      storedId: null,
      supportsEmptySelection: false,
    })).toEqual({ kind: 'model', configId: 'model-a' });
  });

  it('prefers the stored id over the first model', () => {
    expect(resolveInitialSelection({
      enabledIds,
      selectedId: undefined,
      storedId: 'model-b',
      supportsEmptySelection: false,
    })).toEqual({ kind: 'model', configId: 'model-b' });
  });

  it('does not clear a controlled id just because no models are enabled', () => {
    expect(resolveInitialSelection({
      enabledIds: [],
      selectedId: 'model-a',
      storedId: null,
      supportsEmptySelection: true,
    })).toEqual({ kind: 'unselected' });
  });

  it('has nothing to select without enabled models', () => {
    expect(resolveInitialSelection({
      enabledIds: [],
      selectedId: null,
      storedId: null,
      supportsEmptySelection: false,
    })).toEqual({ kind: 'unselected' });
  });
});
