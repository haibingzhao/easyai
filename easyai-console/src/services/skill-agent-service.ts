import { authFetch, JSON_HEADERS, readErrorBody } from './api-client';
import { parseSSEStream } from './sse-parser';
import type { SocketEvent } from '@/types/socket-event';
import type {
  SkillAgentRequest,
  SkillArchiveInstallRequest,
  SkillArchiveInstallResponse,
} from '@/types/skill-agent';

/**
 * Stateless installer stream. The backend runs a dry-run agent: no session, no message rows.
 * `scope` carries the same host default the later upload uses, so the previewed bucket and the
 * installed bucket cannot disagree.
 */
export async function streamSkillAgent(
  request: SkillAgentRequest,
  signal: AbortSignal,
  onEvent: (event: SocketEvent) => void,
): Promise<void> {
  const response = await authFetch('/api/skills/agent/stream', {
    method: 'POST',
    headers: { ...JSON_HEADERS },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok || !response.body) {
    const body = await response.text().catch(() => '');
    throw new Error(body || `Request failed: ${response.status}`);
  }
  await parseSSEStream(response.body.getReader(), {
    onEvent: ({ data }) => {
      if (!data) return;
      onEvent(JSON.parse(data) as SocketEvent);
    },
  });
}

/**
 * Installs the entries the user selected on an archive preview card. The server re-downloads the
 * archive and verifies `sha256` before writing anything, so a changed file is refused rather than
 * installed. Per-entry outcomes come back in the response; archive-level failures throw.
 */
export async function installSkillArchive(
  request: SkillArchiveInstallRequest,
): Promise<SkillArchiveInstallResponse> {
  const response = await authFetch('/api/skills/agent/install', {
    method: 'POST',
    headers: { ...JSON_HEADERS },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(readErrorBody(body) || `Install failed: ${response.status}`);
  }
  return response.json() as Promise<SkillArchiveInstallResponse>;
}
