import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@/services/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listSubAgents: vi.fn(),
    getAgent: vi.fn(),
    listTools: vi.fn(),
    listSkills: vi.fn(),
    createAgent: vi.fn(),
    updateAgent: vi.fn(),
    deleteAgent: vi.fn(),
    getAgentConfigs: vi.fn(),
    saveAgentConfigs: vi.fn(),
  },
}));

vi.mock('@/services/chat-preference-service', () => ({
  chatPreferenceService: {
    get: vi.fn(),
    save: vi.fn(),
  },
}));

import { useAgentStore } from '../agent-store';
import { agentService } from '@/services/agent-service';
import type { AgentDto, ChatPreference } from '@/types/agent';

function makeAgent(overrides: Partial<AgentDto> & Pick<AgentDto, 'id'>): AgentDto {
  return {
    ownerId: 'system',
    name: overrides.id,
    agentType: 'PRIMARY',
    agentContext: 'CHAT',
    description: null,
    customInstructions: null,
    promptTemplate: null,
    toolNames: [],
    subAgentIds: [],
    skillNames: [],
    mcpConfigs: [],
    commandNames: [],
    memberIds: [],
    customSubAgents: [],
    customMembers: [],
    maxIterations: 10,
    maxSubAgentDepth: 1,
    color: null,
    enabled: true,
    instructionsEnabled: true,
    toolFoldEnabled: false,
    toolFoldKeepRecentRuns: 0,
    thinkingHistoryEnabled: false,
    inputSchema: null,
    outputSchema: null,
    outputSchemaMultiTurn: false,
    builtin: false,
    createdAt: null,
    updatedAt: null,
    ...overrides,
  };
}

function preference(hiddenAgentIds: string[], defaultAgentId: string | null = null): ChatPreference {
  return { defaultAgentId, hiddenAgentIds, effectiveSource: 'user', source: 'user' };
}

describe('useAgentStore agent selection', () => {
  beforeEach(() => {
    useAgentStore.getState().resetForUserSwitch();
  });

  it('keeps an explicitly selected agent when the preference hides it', () => {
    useAgentStore.setState({
      agents: [makeAgent({ id: 'default-agent' }), makeAgent({ id: 'other' })],
      selectedAgentId: 'other',
      agentSelectionTouched: true,
    });

    useAgentStore.getState().setChatPreference(preference(['other']));

    expect(useAgentStore.getState().selectedAgentId).toBe('other');
  });

  it('replaces an explicitly selected agent when it becomes unusable', () => {
    useAgentStore.setState({
      agents: [makeAgent({ id: 'default-agent' }), makeAgent({ id: 'other', enabled: false })],
      selectedAgentId: 'other',
      agentSelectionTouched: true,
    });

    useAgentStore.getState().setChatPreference(preference([]));

    expect(useAgentStore.getState().selectedAgentId).toBe('default-agent');
  });

  it('follows the configured default while the selection is untouched', () => {
    useAgentStore.setState({
      agents: [makeAgent({ id: 'default-agent' }), makeAgent({ id: 'other' })],
      selectedAgentId: 'default-agent',
      agentSelectionTouched: false,
    });

    useAgentStore.getState().setChatPreference(preference([], 'other'));

    expect(useAgentStore.getState().selectedAgentId).toBe('other');
  });

  it('does not follow a configured default that is hidden', () => {
    useAgentStore.setState({
      agents: [makeAgent({ id: 'default-agent' }), makeAgent({ id: 'other' })],
      selectedAgentId: 'default-agent',
      agentSelectionTouched: false,
    });

    useAgentStore.getState().setChatPreference(preference(['other'], 'other'));

    expect(useAgentStore.getState().selectedAgentId).toBe('default-agent');
  });

  it('keeps the agent of a conversation that already sent a message', () => {
    useAgentStore.setState({
      agents: [makeAgent({ id: 'default-agent' }), makeAgent({ id: 'other' })],
      selectedAgentId: 'default-agent',
      agentSelectionTouched: false,
    });

    useAgentStore.getState().markAgentSelectionTouched();
    useAgentStore.getState().setChatPreference(preference([], 'other'));

    expect(useAgentStore.getState().selectedAgentId).toBe('default-agent');
  });

  it('drops the selection when the deleted agent was the only one left', async () => {
    vi.mocked(agentService.deleteAgent).mockResolvedValue();
    useAgentStore.setState({
      agents: [makeAgent({ id: 'mine', ownerId: 'alice' })],
      selectedAgentId: 'mine',
      agentSelectionTouched: true,
    });

    await useAgentStore.getState().deleteAgent('mine');

    expect(useAgentStore.getState().selectedAgentId).toBeNull();
  });
});
