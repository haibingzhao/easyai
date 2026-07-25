import type { TeamMemberExecution, TeamRoundRecord } from '@/types/team';
import { fetchJson } from '@/services/api-client';

const API_BASE = '/api/team';

export class TeamService {
  /** Member execution records for a team session. */
  async getExecutions(sessionId: string): Promise<TeamMemberExecution[]> {
    return fetchJson<TeamMemberExecution[]>(`${API_BASE}/sessions/${sessionId}/executions`);
  }

  /** Coordination round records for a team session. */
  async getRounds(sessionId: string): Promise<TeamRoundRecord[]> {
    return fetchJson<TeamRoundRecord[]>(`${API_BASE}/sessions/${sessionId}/rounds`);
  }
}

export const teamService = new TeamService();
