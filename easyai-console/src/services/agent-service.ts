import type { AgentDto, AgentCreateRequest, ToolInfo, AgentToolConfig, AgentConfigsRequest, TargetType, SkillInfo, ValidateTemplateResponse } from '@/types/agent';
import { authFetch } from '@/services/api-client';

const API_BASE = '/api/agents';

export class AgentService {
  async listAgents(): Promise<AgentDto[]> {
    const response = await authFetch(API_BASE);
    if (!response.ok) {
      throw new Error('Failed to list agents');
    }
    return response.json();
  }

  async listSubAgents(): Promise<AgentDto[]> {
    const response = await authFetch(`${API_BASE}/subagents`);
    if (!response.ok) {
      throw new Error('Failed to list sub-agents');
    }
    return response.json();
  }

  async getAgent(id: string): Promise<AgentDto> {
    const response = await authFetch(`${API_BASE}/${id}`);
    if (!response.ok) {
      throw new Error('Failed to get agent');
    }
    return response.json();
  }

  async createAgent(request: AgentCreateRequest): Promise<AgentDto> {
    const response = await authFetch(API_BASE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error('Failed to create agent');
    }
    return response.json();
  }

  async updateAgent(id: string, request: AgentCreateRequest): Promise<AgentDto> {
    const response = await authFetch(`${API_BASE}/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error('Failed to update agent');
    }
    return response.json();
  }

  async deleteAgent(id: string): Promise<void> {
    const response = await authFetch(`${API_BASE}/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error('Failed to delete agent');
    }
  }

  async listTools(): Promise<ToolInfo[]> {
    const response = await authFetch(`${API_BASE}/tools`);
    if (!response.ok) {
      throw new Error('Failed to list tools');
    }
    return response.json();
  }

  async getAgentConfigs(id: string, targetType?: TargetType): Promise<AgentToolConfig[]> {
    const url = targetType
      ? `${API_BASE}/${id}/configs?targetType=${targetType}`
      : `${API_BASE}/${id}/configs`;
    const response = await authFetch(url);
    if (!response.ok) {
      throw new Error('Failed to get agent configs');
    }
    return response.json();
  }

  async saveAgentConfigs(id: string, request: AgentConfigsRequest): Promise<AgentToolConfig[]> {
    const response = await authFetch(`${API_BASE}/${id}/configs`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error('Failed to save agent configs');
    }
    return response.json();
  }

  async listSkills(): Promise<SkillInfo[]> {
    const response = await authFetch('/api/skills');
    if (!response.ok) {
      throw new Error('Failed to list skills');
    }
    return response.json();
  }

  async validateTemplate(template: string): Promise<ValidateTemplateResponse> {
    const response = await authFetch(`${API_BASE}/validate-template`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ template }),
    });
    if (!response.ok) {
      throw new Error('Failed to validate template');
    }
    return response.json();
  }
}

export const agentService = new AgentService();