import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { MemoryEntry } from '@/types/memory';

vi.mock('@/services/memory-service', () => ({
  memoryService: {
    listArchived: vi.fn(),
  },
}));

vi.mock('@/services/stores/project-store', () => ({
  useProjectStore: {
    getState: () => ({ projects: [] }),
  },
}));

import { useMemoryStore } from '../memory-store';
import { memoryService } from '@/services/memory-service';

function makeEntry(overrides: Partial<MemoryEntry> = {}): MemoryEntry {
  return {
    name: 'source',
    description: '',
    type: 'task_summary',
    scope: 'project',
    content: 'body',
    keywords: [],
    scenarios: [],
    id: 'doc-1',
    ...overrides,
  };
}

describe('useMemoryStore archived drill-down', () => {
  beforeEach(() => {
    useMemoryStore.setState({ entries: [], archivedById: {}, error: null, loading: false });
    vi.mocked(memoryService.listArchived).mockReset();
  });

  it('tags a drill-down entry with the project slice path', async () => {
    vi.mocked(memoryService.listArchived).mockResolvedValue([makeEntry()]);

    const [entry] = await useMemoryStore.getState().loadArchived(['doc-1'], 'project', '/repo/p');

    expect(entry.projectPath).toBe('/repo/p');
    expect(useMemoryStore.getState().archivedById['project|/repo/p|doc-1'].projectPath).toBe('/repo/p');
  });

  it('keeps the slice path on cache hits', async () => {
    vi.mocked(memoryService.listArchived).mockResolvedValue([makeEntry()]);

    await useMemoryStore.getState().loadArchived(['doc-1'], 'project', '/repo/p');
    const [entry] = await useMemoryStore.getState().loadArchived(['doc-1'], 'project', '/repo/p');

    expect(memoryService.listArchived).toHaveBeenCalledTimes(1);
    expect(entry.projectPath).toBe('/repo/p');
  });

  it('leaves projectPath null for global drill-downs', async () => {
    vi.mocked(memoryService.listArchived).mockResolvedValue([makeEntry({ scope: 'global' })]);

    const [entry] = await useMemoryStore.getState().loadArchived(['doc-1'], 'global', null);

    expect(entry.projectPath).toBeNull();
  });

  it('keeps the same docId separate per slice', async () => {
    vi.mocked(memoryService.listArchived)
      .mockResolvedValueOnce([makeEntry({ scope: 'global' })])
      .mockResolvedValueOnce([makeEntry()]);

    const [globalEntry] = await useMemoryStore.getState().loadArchived(['doc-1'], 'global', null);
    const [projectEntry] = await useMemoryStore.getState().loadArchived(['doc-1'], 'project', '/repo/p');

    expect(memoryService.listArchived).toHaveBeenCalledTimes(2);
    expect(globalEntry.projectPath).toBeNull();
    expect(projectEntry.projectPath).toBe('/repo/p');
  });
});
