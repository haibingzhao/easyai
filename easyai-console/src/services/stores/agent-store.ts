import { create } from 'zustand';
import { agentService } from '@/services/agent-service';
import { chatPreferenceService } from '@/services/chat-preference-service';
import { isChatSelectable, visibleChatAgents } from '@/utils/chat-agents';
import type {
  AgentDto, AgentCreateRequest, ChatPreference, ToolInfo, AgentToolConfig, TargetType, SkillInfo,
} from '@/types/agent';

interface AgentStore {
  // State
  agents: AgentDto[];
  subAgents: AgentDto[];
  selectedAgentId: string | null;
  /** Effective Chat preference (personal → group → defaults); null before it loads or when unknown. */
  chatPreference: ChatPreference | null;
  /** Once the user (or a restored session) picks an agent, the configured default stops overriding it. */
  agentSelectionTouched: boolean;
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
  loadChatPreference: () => Promise<void>;
  /** Adopt a preference the settings UI just saved and re-resolve the selected agent. */
  setChatPreference: (preference: ChatPreference) => void;
  createAgent: (request: AgentCreateRequest) => Promise<AgentDto>;
  updateAgent: (id: string, request: AgentCreateRequest) => Promise<AgentDto>;
  deleteAgent: (id: string) => Promise<void>;
  selectAgent: (id: string) => void;
  /** A conversation has started; later default changes must not re-target it. */
  markAgentSelectionTouched: () => void;
  /** Return a fresh conversation to the configured default agent (or the first selectable one). */
  resetAgentSelection: () => void;
  resetForUserSwitch: () => void;
  getAgentConfigs: (id: string, targetType?: TargetType) => Promise<AgentToolConfig[]>;
  saveAgentConfigs: (id: string, targetType: TargetType, targetNames: string[]) => Promise<AgentToolConfig[]>;
  clearError: () => void;
}

/**
 * Keep the selected agent usable after agents or the preference change.
 *
 * Hiding only trims the picker: an explicit pick survives it, so an existing conversation keeps its
 * agent (the backend does not reject hidden ids either). A pick is replaced only when it becomes
 * truly unusable — disabled, deleted, or no longer a CHAT agent. A fresh (untouched) selection
 * follows the configured default, which must still be visible. With no agents loaded yet, leave the
 * selection alone.
 */
function reconcileSelectedAgent(state: AgentStore): string | null {
  if (state.agents.length === 0) return state.selectedAgentId;
  const selectable = state.agents.filter(isChatSelectable);
  if (selectable.length === 0) return state.selectedAgentId;
  const visible = visibleChatAgents(state.agents, state.chatPreference?.hiddenAgentIds ?? []);
  const preferred = state.chatPreference?.defaultAgentId;
  const preferredVisible = preferred && visible.some((agent) => agent.id === preferred) ? preferred : null;
  const fallback = preferredVisible
    ?? (visible.some((agent) => agent.id === 'default-agent') ? 'default-agent' : visible[0]?.id)
    ?? selectable[0].id;
  if (state.agentSelectionTouched) {
    return selectable.some((agent) => agent.id === state.selectedAgentId)
      ? state.selectedAgentId
      : fallback;
  }
  return fallback;
}

export const useAgentStore = create<AgentStore>((set, _get) => ({
  agents: [],
  subAgents: [],
  selectedAgentId: 'default-agent',
  chatPreference: null,
  agentSelectionTouched: false,
  tools: [],
  skills: [],
  loading: false,
  error: null,

  loadAgents: async () => {
    set({ loading: true, error: null });
    try {
      const agents = await agentService.listAgents();
      set((state) => {
        const next = { ...state, agents, loading: false };
        return { agents, loading: false, selectedAgentId: reconcileSelectedAgent(next) };
      });
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

  loadChatPreference: async () => {
    try {
      const chatPreference = await chatPreferenceService.get();
      set((state) => {
        const next = { ...state, chatPreference };
        return { chatPreference, selectedAgentId: reconcileSelectedAgent(next) };
      });
    } catch (err) {
      // Preference unavailable (older backend, DB disabled) — keep the built-in default agent.
      console.warn('Failed to load chat preferences, falling back to built-in defaults:', err);
    }
  },

  setChatPreference: (chatPreference: ChatPreference) => {
    set((state) => {
      const next = { ...state, chatPreference };
      return { chatPreference, selectedAgentId: reconcileSelectedAgent(next) };
    });
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
      set((state) => {
        const agents = state.agents.map((a) => (a.id === id ? agent : a));
        return { agents, loading: false, selectedAgentId: reconcileSelectedAgent({ ...state, agents }) };
      });
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
      set((state) => {
        const agents = state.agents.filter((a) => a.id !== id);
        const selectedAgentId = reconcileSelectedAgent({ ...state, agents });
        return {
          agents,
          loading: false,
          // Reconcile leaves the selection alone when nothing is left to pick, because that also
          // covers a list that has not loaded yet. Here the row is known to be gone.
          selectedAgentId: selectedAgentId === id ? null : selectedAgentId,
        };
      });
    } catch (err) {
      set({ error: (err as Error).message, loading: false });
      throw err;
    }
  },

  selectAgent: (id: string) => {
    set({ selectedAgentId: id, agentSelectionTouched: true });
  },

  markAgentSelectionTouched: () => {
    set({ agentSelectionTouched: true });
  },

  resetAgentSelection: () => {
    set((state) => {
      const visible = visibleChatAgents(state.agents, state.chatPreference?.hiddenAgentIds ?? []);
      if (visible.length === 0) {
        return { agentSelectionTouched: false };
      }
      const preferred = state.chatPreference?.defaultAgentId;
      const selectedAgentId =
        preferred && visible.some((agent) => agent.id === preferred)
          ? preferred
          : visible.some((agent) => agent.id === 'default-agent')
            ? 'default-agent'
            : visible[0].id;
      return { agentSelectionTouched: false, selectedAgentId };
    });
  },

  resetForUserSwitch: () => {
    set({
      agents: [],
      subAgents: [],
      selectedAgentId: 'default-agent',
      chatPreference: null,
      agentSelectionTouched: false,
      tools: [],
      skills: [],
      loading: false,
      error: null,
    });
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