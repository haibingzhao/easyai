import { create } from 'zustand';
import { agentService } from '@/services/agent-service';
import type { AgentDto, AgentCreateRequest, ToolInfo, AgentToolConfig, TargetType, SkillInfo } from '@/types/agent';

interface AgentStore {
  // State
  agents: AgentDto[];
  subAgents: AgentDto[];
  selectedAgentId: string | null;
  tools: ToolInfo[];
  skills: SkillInfo[];
  loading: boolean;
  error: string | null;

  // Actions
  loadAgents: () => Promise<void>;
  loadSubAgents: () => Promise<void>;
  fetchAgent: (id: string) => Promise<AgentDto>;
  loadTools: () => Promise<void>;
  loadSkills: () => Promise<void>;
  createAgent: (request: AgentCreateRequest) => Promise<AgentDto>;
  updateAgent: (id: string, request: AgentCreateRequest) => Promise<AgentDto>;
  deleteAgent: (id: string) => Promise<void>;
  selectAgent: (id: string) => void;
  getAgentConfigs: (id: string, targetType?: TargetType) => Promise<AgentToolConfig[]>;
  saveAgentConfigs: (id: string, targetType: TargetType, targetNames: string[]) => Promise<AgentToolConfig[]>;
  clearError: () => void;
}

export const useAgentStore = create<AgentStore>((set, _get) => ({
  agents: [],
  subAgents: [],
  selectedAgentId: 'default-agent',
  tools: [],
  skills: [],
  loading: false,
  error: null,

  loadAgents: async () => {
    set({ loading: true, error: null });
    try {
      const agents = await agentService.listAgents();
      set({ agents, loading: false });
    } catch (err) {
      set({ error: (err as Error).message, loading: false });
    }
  },

  loadSubAgents: async () => {
    try {
      const subAgents = await agentService.listSubAgents();
      set({ subAgents });
    } catch (err) {
      set({ error: (err as Error).message });
    }
  },

  fetchAgent: async (id: string) => {
    const agent = await agentService.getAgent(id);
    // Update the agent in the list if it exists
    set((state) => ({
      agents: state.agents.some((a) => a.id === id)
        ? state.agents.map((a) => (a.id === id ? agent : a))
        : state.agents,
    }));
    return agent;
  },

  loadTools: async () => {
    try {
      const tools = await agentService.listTools();
      set({ tools });
    } catch (err) {
      set({ error: (err as Error).message });
    }
  },

  loadSkills: async () => {
    try {
      const skills = await agentService.listSkills();
      set({ skills });
    } catch (err) {
      set({ error: (err as Error).message });
    }
  },

  createAgent: async (request: AgentCreateRequest) => {
    set({ loading: true, error: null });
    try {
      const agent = await agentService.createAgent(request);
      set((state) => ({
        agents: [...state.agents, agent],
        loading: false,
      }));
      return agent;
    } catch (err) {
      set({ error: (err as Error).message, loading: false });
      throw err;
    }
  },

  updateAgent: async (id: string, request: AgentCreateRequest) => {
    set({ loading: true, error: null });
    try {
      const agent = await agentService.updateAgent(id, request);
      set((state) => ({
        agents: state.agents.map((a) => (a.id === id ? agent : a)),
        loading: false,
      }));
      return agent;
    } catch (err) {
      set({ error: (err as Error).message, loading: false });
      throw err;
    }
  },

  deleteAgent: async (id: string) => {
    set({ loading: true, error: null });
    try {
      await agentService.deleteAgent(id);
      set((state) => ({
        agents: state.agents.filter((a) => a.id !== id),
        selectedAgentId: state.selectedAgentId === id ? null : state.selectedAgentId,
        loading: false,
      }));
    } catch (err) {
      set({ error: (err as Error).message, loading: false });
      throw err;
    }
  },

  selectAgent: (id: string) => {
    set({ selectedAgentId: id });
  },

  getAgentConfigs: async (id: string, targetType?: TargetType) => {
    try {
      return await agentService.getAgentConfigs(id, targetType);
    } catch (err) {
      set({ error: (err as Error).message });
      throw err;
    }
  },

  saveAgentConfigs: async (id: string, targetType: TargetType, targetNames: string[]) => {
    try {
      return await agentService.saveAgentConfigs(id, { targetType, targetNames });
    } catch (err) {
      set({ error: (err as Error).message });
      throw err;
    }
  },

  clearError: () => {
    set({ error: null });
  },
}));