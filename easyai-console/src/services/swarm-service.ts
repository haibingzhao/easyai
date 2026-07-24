import { authFetch, getAccessToken } from '@/services/api-client';
import type { SessionDetail, SessionMessagesAfterResponse } from '@/services/session-service';
import type { McpBindingDto } from '@/types/agent';

const API_BASE = '/api/swarm';

export interface PresetAgent {
  id: string;
  role: string;
  description: string;
}

export interface PresetTask {
  id: string;
  agentId: string;
  type: 'SINGLE' | 'DELIBERATION' | 'TEAM';
  dependsOn: string[];
  promptTemplate?: string;
  deliberation?: { participants: string[]; judge: string };
  team?: { leader: string; members: string[] };
}

export interface PresetVariable {
  name: string;
  description: string;
  required?: boolean;
  defaultValue: string | null;
  updatable?: boolean;
}

export interface SwarmAgentSpecDto {
  id: string;
  agentDefinitionId?: string;
  role: string;
  maxIterations?: number;
  timeoutSeconds?: number;
  modelName?: string;
  maxRetries?: number;
  // Inline custom agent fields (used when agentDefinitionId is blank)
  name?: string;
  description?: string;
  systemPrompt?: string;
  toolNames?: string[];
  mcpConfigs?: McpBindingDto[];
}

export interface SwarmTaskDto {
  id: string;
  agentId?: string;
  promptTemplate: string;
  dependsOn?: string[];
  inputFrom?: Record<string, string>;
  type: 'SINGLE' | 'DELIBERATION' | 'TEAM';
  deliberation?: DeliberationSpecDto;
  team?: TeamSpecDto;
  maxRetries?: number;
  updatableVariables?: string[];
  agentPromptEnabled?: boolean;
  systemPromptTemplate?: string;
  reportEnabled?: boolean;
}

export interface DeliberationSpecDto {
  participants: string[];
  judge: string;
  maxRounds?: number;
  order?: 'SEQUENTIAL' | 'ROUND_ROBIN';
  /** Jinja2 template for deliberation context/topic. */
  contextTemplate?: string;
}

export interface TeamSpecDto {
  leader: string;
  members: string[];
  maxIterations: number;
  maxDynamicTasks: number;
  roundTimeoutSeconds: number;
  memberTimeoutSeconds: number;
  contextTemplate: string;
}

export interface EscalationEntryDto {
  memberId: string;
  round: number;
  reason: string;
  resolution?: string;
  reassignedTo?: string;
}

export type MemberStatusDto = 'RUNNING' | 'COMPLETED' | 'ESCALATED' | 'REASSIGNED';

export interface TeamMemberExecutionDto {
  memberId: string;
  round: number;
  assignment: string;
  status: MemberStatusDto;
  summary?: string;
  escalationReason?: string;
  inputTokens: number;
  outputTokens: number;
}

export interface TeamRoundRecordDto {
  round: number;
  leaderAnalysis: string;
  delegatedMembers: string[];
  completedMembers: string[];
  escalations: string[];
  leaderPrompt?: string;
}

export interface TeamHistoryResponse {
  escalationHistory: EscalationEntryDto[];
  memberExecutions: TeamMemberExecutionDto[];
  roundRecords: TeamRoundRecordDto[];
}

export interface DeliberationEntryDto {
  agentId: string;
  round: number;
  response: string;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  durationMs: number;
  openingPrompt?: string;
  roundPrompts?: Record<string, string>;
}

export interface DeliberationHistoryResponseDto {
  entries: DeliberationEntryDto[];
  verdictPrompt?: string;
  verdictResponse?: string;
}

export interface SwarmVariableDto {
  name: string;
  description?: string;
  required?: boolean;
  defaultValue?: string | null;
  updatable?: boolean;
}

export interface PresetRequest {
  name: string;
  title: string;
  description?: string;
  agents: SwarmAgentSpecDto[];
  tasks: SwarmTaskDto[];
  variables?: SwarmVariableDto[];
  language?: string;
}

export interface PresetDetail {
  name: string;
  title: string;
  description: string;
  agents: SwarmAgentSpecDto[];
  tasks: SwarmTaskDto[];
  variables: SwarmVariableDto[];
  language: string;
}

export interface PresetInfo {
  name: string;
  title: string;
  description: string;
  agents: PresetAgent[];
  tasks: PresetTask[];
  variables: PresetVariable[];
  language?: string;
}

export interface RunSummary {
  id: string;
  presetName: string;
  title: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'PAUSED' | 'RESUMING';
  totalInputTokens: number;
  totalOutputTokens: number;
  error: string | null;
  createdAt: number;
  language?: string;
}

export interface TaskSummary {
  id: string;
  agentId: string;
  type: 'SINGLE' | 'DELIBERATION' | 'TEAM';
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'BLOCKED' | 'PAUSED' | 'CANCELLED';
  summary: string | null;
  error: string | null;
  workerIterations: number;
  inputTokens: number;
  outputTokens: number;
}

export interface SwarmEvent {
  type: string;
  runId: string;
  taskId?: string;
  data: Record<string, unknown>;
  timestamp: number;
}

export interface RunDetailResponse {
  id: string;
  presetName: string;
  title: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'PAUSED' | 'RESUMING';
  totalInputTokens: number;
  totalOutputTokens: number;
  error: string | null;
  tasks: TaskSummary[];
  language?: string;
  dryRun?: boolean;
}

/** Normalize backend lowercase task type to frontend uppercase convention */
function normalizeTaskType(type: string): 'SINGLE' | 'DELIBERATION' | 'TEAM' {
  const upper = type.toUpperCase();
  if (upper === 'SINGLE' || upper === 'DELIBERATION' || upper === 'TEAM') {
    return upper;
  }
  console.warn(`Unexpected task type '${type}', defaulting to SINGLE`);
  return 'SINGLE';
}

function normalizeTasks<T extends { type: string }>(tasks: T[]): T[] {
  return tasks.map((t) => ({ ...t, type: normalizeTaskType(t.type) }));
}

class SwarmService {
  async listPresets(): Promise<PresetInfo[]> {
    const res = await authFetch(`${API_BASE}/presets`);
    if (!res.ok) return [];
    const data: PresetInfo[] = await res.json();
    return data.map((p) => ({ ...p, tasks: normalizeTasks(p.tasks) }));
  }

  async createPreset(request: PresetRequest): Promise<PresetInfo> {
    const res = await authFetch(`${API_BASE}/presets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!res.ok) {
      const error = await res.text();
      throw new Error(error || 'Failed to create preset');
    }
    const data: PresetInfo = await res.json();
    return { ...data, tasks: normalizeTasks(data.tasks) };
  }

  async getPresetDetail(name: string): Promise<PresetDetail | null> {
    const res = await authFetch(`${API_BASE}/presets/${encodeURIComponent(name)}/detail`);
    if (!res.ok) return null;
    const data: PresetDetail = await res.json();
    return { ...data, tasks: normalizeTasks(data.tasks) };
  }

  async updatePreset(name: string, request: PresetRequest): Promise<PresetInfo> {
    const res = await authFetch(`${API_BASE}/presets/${encodeURIComponent(name)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    if (!res.ok) {
      const error = await res.text();
      throw new Error(error || 'Failed to update preset');
    }
    const data: PresetInfo = await res.json();
    return { ...data, tasks: normalizeTasks(data.tasks) };
  }

  async deletePreset(name: string): Promise<boolean> {
    const res = await authFetch(`${API_BASE}/presets/${encodeURIComponent(name)}`, {
      method: 'DELETE',
    });
    return res.ok;
  }

  async listRuns(limit = 20, offset = 0): Promise<RunSummary[]> {
    const res = await authFetch(`${API_BASE}/runs?limit=${limit}&offset=${offset}`);
    if (!res.ok) return [];
    return res.json();
  }

  async getRun(id: string): Promise<RunDetailResponse | null> {
    const res = await authFetch(`${API_BASE}/runs/${id}`);
    if (!res.ok) return null;
    const data: RunDetailResponse = await res.json();
    return { ...data, tasks: normalizeTasks(data.tasks) };
  }

  async launchRun(
    presetName: string,
    variables?: Record<string, string>,
    modelConfigId?: string,
    dryRun?: boolean
  ): Promise<{ runId: string } | null> {
    const res = await authFetch(`${API_BASE}/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        presetName,
        variables: variables || {},
        ...(modelConfigId ? { modelConfigId } : {}),
        ...(dryRun ? { dryRun: true } : {}),
      }),
    });
    if (!res.ok) return null;
    return res.json();
  }

  async cancelRun(id: string): Promise<boolean> {
    const res = await authFetch(`${API_BASE}/runs/${id}/cancel`, { method: 'POST' });
    return res.ok;
  }

  async deleteRun(id: string): Promise<boolean> {
    const res = await authFetch(`${API_BASE}/runs/${id}`, { method: 'DELETE' });
    return res.ok;
  }

  async pauseRun(id: string): Promise<void> {
    await authFetch(`${API_BASE}/runs/${id}/pause`, { method: 'POST' });
  }

  async resumeRun(id: string): Promise<void> {
    await authFetch(`${API_BASE}/runs/${id}/resume`, { method: 'POST' });
  }

  async getTaskSession(runId: string, taskId: string): Promise<SessionDetail | null> {
    const res = await authFetch(`${API_BASE}/runs/${runId}/tasks/${taskId}/session`);
    if (!res.ok) return null;
    return res.json();
  }

  async getTaskSessionMessagesAfter(runId: string, taskId: string, after: number): Promise<SessionMessagesAfterResponse | null> {
    const res = await authFetch(`${API_BASE}/runs/${runId}/tasks/${taskId}/session/messages?after=${after}`);
    if (!res.ok) return null;
    return res.json();
  }

  async fetchTeamHistory(runId: string, taskId: string): Promise<TeamHistoryResponse | null> {
    const res = await authFetch(`${API_BASE}/runs/${runId}/tasks/${taskId}/team-history`);
    if (!res.ok) return null;
    return res.json();
  }

  async fetchDeliberationHistory(runId: string, taskId: string): Promise<DeliberationHistoryResponseDto | null> {
    const res = await authFetch(`${API_BASE}/runs/${runId}/tasks/${taskId}/deliberation-history`);
    if (!res.ok) return null;
    return res.json();
  }

  subscribeToRunEvents(
    runId: string,
    onEvent: (event: SwarmEvent) => void,
    onError?: () => void
  ): EventSource {
    const token = getAccessToken() || '';
    const url = token
      ? `${API_BASE}/runs/${runId}/events?token=${encodeURIComponent(token)}`
      : `${API_BASE}/runs/${runId}/events`;
    const eventSource = new EventSource(url);
    eventSource.onmessage = (e) => {
      const data = JSON.parse(e.data) as SwarmEvent;
      onEvent(data);
    };
    eventSource.onerror = () => {
      onError?.();
      eventSource.close();
    };
    return eventSource;
  }

  async exportPreset(name: string): Promise<void> {
    const resp = await authFetch(`${API_BASE}/presets/${encodeURIComponent(name)}/export`);
    if (!resp.ok) throw new Error(`Export failed: ${resp.status}`);
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${name}.swarm.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  async importPreset(file: File): Promise<PresetInfo> {
    const data = await this.parsePresetFile(file);
    return this.importPresetData(data);
  }

  async parsePresetFile(file: File): Promise<PresetRequest> {
    const text = await file.text();
    const data = JSON.parse(text);
    if (!data.name || !data.agents || !data.tasks) {
      throw new Error('Invalid preset file: missing name, agents, or tasks');
    }
    return data as PresetRequest;
  }

  async importPresetData(data: PresetRequest): Promise<PresetInfo> {
    const resp = await authFetch(`${API_BASE}/presets/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    if (!resp.ok) {
      const msg = await resp.text();
      throw new Error(msg || `Import failed: ${resp.status}`);
    }
    return resp.json();
  }
}

export const swarmService = new SwarmService();
