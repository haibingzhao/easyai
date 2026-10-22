import type { AgentDto } from '@/types/agent';

/**
 * Whether an agent can be chosen as the primary agent in Chat: not a sub-agent, enabled, and
 * compatible with the Chat context. Mirrors the backend's validation for chat preferences.
 */
export function isChatSelectable(agent: AgentDto): boolean {
  return agent.agentType !== 'SUBAGENT' &&
    (agent.agentContext === 'CHAT' || agent.agentContext === 'BOTH') &&
    agent.enabled;
}

/** Chat-selectable agents after removing the ids hidden by the current Chat preference. */
export function visibleChatAgents(agents: AgentDto[], hiddenAgentIds: string[]): AgentDto[] {
  const hidden = new Set(hiddenAgentIds);
  return agents.filter((agent) => isChatSelectable(agent) && !hidden.has(agent.id));
}
