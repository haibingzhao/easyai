import type { AgentDto, AgentCreateRequest, ToolInfo, AgentToolConfig, AgentConfigsRequest, TargetType, SkillInfo, ValidateTemplateResponse } from '@/types/agent';
import { authFetch, fetchJson, fetchVoid, downloadBlob, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/agents';

export class AgentService {
  async listAgents(): Promise<AgentDto[]> {
    return fetchJson<AgentDto[]>(API_BASE);
  }

  async listSubAgents(): Promise<AgentDto[]> {
    return fetchJson<AgentDto[]>(`${API_BASE}/subagents`);
  }

  async getAgent(id: string): Promise<AgentDto> {
    return fetchJson<AgentDto>(`${API_BASE}/${id}`);
  }

  async createAgent(request: AgentCreateRequest): Promise<AgentDto> {
    return fetchJson<AgentDto>(API_BASE, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async updateAgent(id: string, request: AgentCreateRequest): Promise<AgentDto> {
    return fetchJson<AgentDto>(`${API_BASE}/${id}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async deleteAgent(id: string): Promise<void> {
    return fetchVoid(`${API_BASE}/${id}`, { method: 'DELETE' });
  }

  async listTools(): Promise<ToolInfo[]> {
    return fetchJson<ToolInfo[]>(`${API_BASE}/tools`);
  }

  async getAgentConfigs(id: string, targetType?: TargetType): Promise<AgentToolConfig[]> {
    const url = targetType
      ? `${API_BASE}/${id}/configs?targetType=${targetType}`
      : `${API_BASE}/${id}/configs`;
    return fetchJson<AgentToolConfig[]>(url);
  }

  async saveAgentConfigs(id: string, request: AgentConfigsRequest): Promise<AgentToolConfig[]> {
    return fetchJson<AgentToolConfig[]>(`${API_BASE}/${id}/configs`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    });
  }

  async listSkills(): Promise<SkillInfo[]> {
    return fetchJson<SkillInfo[]>('/api/skills');
  }

  async validateTemplate(template: string): Promise<ValidateTemplateResponse> {
    return fetchJson<ValidateTemplateResponse>(`${API_BASE}/validate-template`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ template }),
    });
  }

  async exportAgent(id: string): Promise<void> {
    const resp = await authFetch(`${API_BASE}/${encodeURIComponent(id)}/export`);
    if (!resp.ok) throw new Error(`Export failed: ${resp.status}`);
    const blob = await resp.blob();
    downloadBlob(blob, `${id}.agent.json`);
  }

  async parseAgentFile(file: File): Promise<AgentDto> {
    const text = await file.text();
    const data = JSON.parse(text);
    const agent = data.agent ?? data;
    if (!agent.id || !agent.name) {
      throw new Error('Invalid agent file: missing id or name');
    }
    return agent as AgentDto;
  }
}

export const agentService = new AgentService();