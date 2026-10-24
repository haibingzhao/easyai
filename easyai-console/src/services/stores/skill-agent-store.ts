import { create } from 'zustand';
import { resolveWriteScope } from '../api-client';
import type { AssetScope } from '../api-client';
import { installSkillArchive, streamSkillAgent } from '../skill-agent-service';
import { useSkillStore } from './skill-store';
import { i18n } from '@/utils/i18n';
import type { SocketEvent } from '@/types/socket-event';
import type {
  SkillAgentHistoryTurn,
  SkillAgentRequest,
  SkillArchiveEntry,
  SkillArchiveInput,
  SkillArchiveProposal,
  SkillProposal,
  SkillProposalInput,
} from '@/types/skill-agent';

export interface SkillAgentToolRun {
  toolCallId: string;
  toolName: string;
  status: 'running' | 'success' | 'error';
  output?: string;
}

/**
 * Ordered transcript of one assistant turn. The SSE stream interleaves thinking,
 * text and tool runs across agent-loop iterations, so rendering aggregates in a
 * fixed order would move the final answer ahead of the tools it was based on.
 */
export type SkillAgentPart =
  | { kind: 'thinking'; content: string }
  | { kind: 'text'; content: string }
  | { kind: 'tool'; toolCallId: string };

export interface SkillAgentMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  thinking?: string;
  thinkingDone?: boolean;
  parts: SkillAgentPart[];
  tools: SkillAgentToolRun[];
  proposal?: SkillProposal;
  proposalState?: 'active' | 'superseded' | 'installed';
  /** Multi-skill preview from `preview_skill_archive`; only one preview of either kind is active. */
  archiveProposal?: SkillArchiveProposal;
  archiveState?: 'active' | 'superseded';
  /** The user checked "I read the high-risk findings". */
  acknowledged?: boolean;
  installing?: boolean;
  installError?: string;
  /** Non-fatal notice from the install endpoint (e.g. a shadowed same-named skill). */
  installWarning?: string;
  /**
   * Write bucket this turn's preview was resolved against. Carried onto the install so a scope the
   * host switched in between cannot move the package somewhere other than the card advertises.
   */
  installScope?: AssetScope;
  error?: string;
}

interface SkillAgentState {
  messages: SkillAgentMessage[];
  isStreaming: boolean;
  modelConfigId: string | null;
  abortController: AbortController | null;
  error: string | null;
  setModelConfigId: (id: string) => void;
  /** Adds the turn and starts the stream; false means nothing was sent (input must be kept). */
  send: (text: string) => boolean;
  stop: () => void;
  reset: () => void;
  setAcknowledged: (messageId: string, acknowledged: boolean) => void;
  install: (messageId: string) => Promise<void>;
  setArchiveAcknowledged: (messageId: string, entryPath: string, acknowledged: boolean) => void;
  /**
   * Installs the named entries of an archive preview; `null` installs every installable entry.
   * An empty array is not a synonym for "all": an archive-root skill's entryPath is `""`.
   */
  installArchive: (messageId: string, entryPaths: string[] | null) => Promise<void>;
}

/** Whether an archive entry can be installed at all, ignoring the high-risk acknowledgement. */
export function archiveEntryInstallable(entry: SkillArchiveEntry): boolean {
  return !entry.conflict && !entry.invalidReason && !entry.scan.blocking;
}

/** Whether an entry needs the user's explicit acknowledgement before it may be installed. */
export function archiveEntryNeedsAcknowledgement(entry: SkillArchiveEntry): boolean {
  return archiveEntryInstallable(entry) && entry.scan.requiresAcknowledgement && !entry.acknowledged;
}

let counter = 0;

/** Stay below the server's 256 KiB JSON body ceiling, with room for transport overhead. */
const MAX_REQUEST_BYTES = 240 * 1024;
/** Mirror the server's per-message cap so an overlong paste fails before it enters the history. */
const MAX_MESSAGE_CHARS = 8_000;
/**
 * The server rejects more than 20 turns / 60K characters outright; trim from the oldest end and
 * keep a margin so a long conversation can never wedge every later turn.
 */
const MAX_HISTORY_TURNS = 20;
const MAX_HISTORY_TOTAL_CHARS = 40_000;
/** The server rejects any single turn above 20K characters; drop it rather than wedge the panel. */
const MAX_HISTORY_TURN_CHARS = 20_000;

function nextId(prefix: string): string {
  counter += 1;
  return `${prefix}-${Date.now()}-${counter}`;
}

function asProposal(value: unknown): SkillProposal | null {
  if (!value || typeof value !== 'object') return null;
  const candidate = value as Partial<SkillProposal>;
  if (typeof candidate.name !== 'string' || !Array.isArray(candidate.files) || !candidate.scan) return null;
  return candidate as SkillProposal;
}

function asArchiveProposal(value: unknown): SkillArchiveProposal | null {
  if (!value || typeof value !== 'object') return null;
  const candidate = value as Partial<SkillArchiveProposal>;
  if (typeof candidate.sourceUrl !== 'string' || typeof candidate.sha256 !== 'string') return null;
  if (!Array.isArray(candidate.skills)) return null;
  return candidate as SkillArchiveProposal;
}

/** Keep the replayed inventory small: no file content, no sizes, bounded fields and count. */
const MAX_ARCHIVE_REPLAY_SKILLS = 20;
const MAX_ARCHIVE_REPLAY_BYTES = 16 * 1024;

function toArchiveInput(proposal: SkillArchiveProposal): SkillArchiveInput {
  const input: SkillArchiveInput = {
    url: proposal.sourceUrl,
    sha256: proposal.sha256,
    skills: proposal.skills.slice(0, MAX_ARCHIVE_REPLAY_SKILLS).map((entry) => ({
      name: entry.name.slice(0, 128),
      entryPath: entry.entryPath.slice(0, 512),
      conflict: entry.conflict ? entry.conflict.slice(0, 256) : undefined,
    })),
  };
  while (input.skills.length > 0 && new Blob([JSON.stringify(input)]).size > MAX_ARCHIVE_REPLAY_BYTES) {
    input.skills.pop();
  }
  return input;
}

function toInput(proposal: SkillProposal): SkillProposalInput {
  const scanSummary = proposal.scan.findings
    .filter((finding) => finding.severity === 'critical' || finding.severity === 'high')
    .slice(0, 20)
    .map((finding) => {
      const location = [finding.file, finding.line].filter((part) => part != null).join(':');
      const message = finding.redacted
        ? 'credential-like value removed from the model context'
        : finding.message;
      return `${finding.severity} ${finding.id}${location ? ` (${location})` : ''}: ${message}`;
    })
    .join('\n');
  return {
    name: proposal.name,
    summary: proposal.summary,
    description: proposal.description,
    version: proposal.version,
    tags: proposal.tags,
    files: proposal.files.map((file) => ({ path: file.path, content: file.content })),
    scanSummary: scanSummary || undefined,
  };
}

function historyOf(messages: SkillAgentMessage[]): SkillAgentHistoryTurn[] {
  const turns = messages
    .filter((message) => message.content.trim() !== '')
    .map((message) => ({ role: message.role, content: message.content }));
  const kept: SkillAgentHistoryTurn[] = [];
  let total = 0;
  for (let index = turns.length - 1; index >= 0 && kept.length < MAX_HISTORY_TURNS; index -= 1) {
    const turn = turns[index];
    if (turn.content.length > MAX_HISTORY_TURN_CHARS) continue;
    if (total + turn.content.length > MAX_HISTORY_TOTAL_CHARS) break;
    kept.unshift(turn);
    total += turn.content.length;
  }
  // A slice can start on an assistant reply; drop it so the history opens with the user.
  while (kept.length > 0 && kept[0].role === 'assistant') kept.shift();
  return kept;
}

function activeProposal(messages: SkillAgentMessage[]): SkillProposal | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message.proposal && message.proposalState === 'active') return message.proposal;
  }
  return null;
}

function activeArchive(messages: SkillAgentMessage[]): SkillArchiveProposal | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message.archiveProposal && message.archiveState === 'active') return message.archiveProposal;
  }
  return null;
}

export const useSkillAgentStore = create<SkillAgentState>()((set, get) => {
  const patchAssistant = (messageId: string, patch: (message: SkillAgentMessage) => SkillAgentMessage) =>
    set((state) => ({
      messages: state.messages.map((message) => (message.id === messageId ? patch(message) : message)),
    }));

  const appendPart = (messageId: string, kind: 'text' | 'thinking', delta: string) =>
    patchAssistant(messageId, (message) => {
      const parts = [...message.parts];
      const last = parts[parts.length - 1];
      const continued = last?.kind === kind;
      if (continued) {
        parts[parts.length - 1] = { kind, content: last.content + delta };
      } else {
        parts.push({ kind, content: delta });
      }
      return kind === 'text'
        ? { ...message, content: message.content + delta, parts }
        : {
          ...message,
          thinking: (message.thinking ?? '') + delta,
          // A later agent-loop iteration can think again; that is a new live block.
          thinkingDone: continued ? message.thinkingDone : false,
          parts,
        };
    });

  const appendText = (messageId: string, delta: string) => appendPart(messageId, 'text', delta);

  const appendThinking = (messageId: string, delta: string) => appendPart(messageId, 'thinking', delta);

  /**
   * Only one preview of either kind may be active: the inline package the agent authored or a
   * multi-skill archive. A newer one supersedes every older card, so the replay in `send()` and
   * the user's Install click always refer to the same thing.
   */
  const activatePreview = (
    messageId: string,
    preview: { proposal?: SkillProposal; archiveProposal?: SkillArchiveProposal },
  ) =>
    set((state) => ({
      messages: state.messages.map((message) => {
        if (message.id === messageId) {
          return {
            ...message,
            proposal: preview.proposal,
            proposalState: preview.proposal ? 'active' as const : undefined,
            archiveProposal: preview.archiveProposal,
            archiveState: preview.archiveProposal ? 'active' as const : undefined,
            acknowledged: false,
            installing: false,
            installError: undefined,
            installWarning: undefined,
          };
        }
        if (message.proposalState !== 'active' && message.archiveState !== 'active') return message;
        return {
          ...message,
          proposalState: message.proposalState === 'active' ? 'superseded' as const : message.proposalState,
          archiveState: message.archiveState === 'active' ? 'superseded' as const : message.archiveState,
        };
      }),
    }));

  const upsertTool = (
    messageId: string,
    toolCallId: string,
    patch: (tool: SkillAgentToolRun) => SkillAgentToolRun,
  ) =>
    patchAssistant(messageId, (message) => {
      const index = message.tools.findIndex((tool) => tool.toolCallId === toolCallId);
      if (index < 0) return message;
      const tools = [...message.tools];
      tools[index] = patch(tools[index]);
      return { ...message, tools };
    });

  /**
   * `sha256` pins the patch to the preview the caller acted on: a newer preview may replace the
   * card while an install is in flight, and its entries must not inherit the old result.
   */
  const patchArchiveEntries = (
    messageId: string,
    entryPaths: Set<string>,
    patch: (entry: SkillArchiveEntry) => SkillArchiveEntry,
    sha256?: string,
  ) =>
    patchAssistant(messageId, (message) =>
      message.archiveProposal && (sha256 == null || message.archiveProposal.sha256 === sha256)
        ? {
          ...message,
          archiveProposal: {
            ...message.archiveProposal,
            skills: message.archiveProposal.skills.map((entry) =>
              entryPaths.has(entry.entryPath) ? patch(entry) : entry,
            ),
          },
        }
        : message,
    );

  const handleEvent = (messageId: string, event: SocketEvent) => {
    switch (event.type) {
      case 'text_delta':
        appendText(messageId, event.delta);
        return;
      case 'thinking_delta':
        appendThinking(messageId, event.delta);
        return;
      case 'thinking_end':
        patchAssistant(messageId, (message) => ({ ...message, thinkingDone: true }));
        return;
      case 'tool_execution_start':
        patchAssistant(messageId, (message) => ({
          ...message,
          parts: [...message.parts, { kind: 'tool' as const, toolCallId: event.toolCallId }],
          tools: [
            ...message.tools,
            { toolCallId: event.toolCallId, toolName: event.toolName, status: 'running' },
          ],
        }));
        return;
      case 'tool_execution_update':
        upsertTool(messageId, event.toolCallId, (tool) => ({ ...tool, output: event.output }));
        return;
      case 'tool_execution_end': {
        upsertTool(messageId, event.toolCallId, (tool) => ({
          ...tool,
          status: event.isError ? 'error' : 'success',
          output: event.result ?? tool.output,
        }));
        if (event.isError) return;
        if (event.toolName === 'preview_skill_archive') {
          const archive = asArchiveProposal(event.details?.skillArchiveProposal);
          if (archive) activatePreview(messageId, { archiveProposal: archive });
          return;
        }
        if (event.toolName !== 'submit_skill') return;
        const proposal = asProposal(event.details?.skillProposal);
        if (proposal) activatePreview(messageId, { proposal });
        return;
      }
      case 'error':
        patchAssistant(messageId, (message) => ({
          ...message,
          error: event.errorMessage ?? 'Unknown error',
        }));
        return;
      default:
        return;
    }
  };

  return {
    messages: [],
    isStreaming: false,
    modelConfigId: null,
    abortController: null,
    error: null,

    setModelConfigId: (id) => set({ modelConfigId: id }),

    send: (text) => {
      const state = get();
      const message = text.trim();
      if (!message || state.isStreaming || !state.modelConfigId) return false;
      if (message.length > MAX_MESSAGE_CHARS) {
        set({ error: i18n('This message is too long. Shorten it before sending.') });
        return false;
      }
      const controller = new AbortController();
      const assistantId = nextId('assistant');
      const current = activeProposal(state.messages);
      const currentArchive = activeArchive(state.messages);
      const history = historyOf(state.messages);
      // Name the bucket instead of omitting it: the preview card and the install that follows it
      // must resolve the same owner even if the host's write default changes in between.
      const scope = resolveWriteScope() ?? 'personal';
      const request: SkillAgentRequest = {
        modelConfigId: state.modelConfigId,
        message,
        history,
        currentProposal: currentArchive || !current ? undefined : toInput(current),
        currentArchive: currentArchive ? toArchiveInput(currentArchive) : undefined,
        scope,
      };
      if (new Blob([JSON.stringify(request)]).size > MAX_REQUEST_BYTES) {
        set({
          error: i18n('This conversation is too large to send. Start a new conversation or use Upload from computer.'),
        });
        return false;
      }
      set({
        isStreaming: true,
        abortController: controller,
        error: null,
        messages: [
          ...state.messages,
          { id: nextId('user'), role: 'user', content: message, parts: [], tools: [] },
          { id: assistantId, role: 'assistant', content: '', parts: [], tools: [], installScope: scope },
        ],
      });

      void (async () => {
        try {
          await streamSkillAgent(request, controller.signal, (event) => handleEvent(assistantId, event));
        } catch (error) {
          if ((error as Error).name !== 'AbortError') {
            patchAssistant(assistantId, (assistant) => ({
              ...assistant,
              error: (error as Error).message,
            }));
          }
        } finally {
          // A stop() followed by an immediate resend starts a new controller; the old run must not
          // clear the new stream's state when its abort finally lands.
          set((currentState) =>
            currentState.abortController === controller
              ? { isStreaming: false, abortController: null }
              : {},
          );
        }
      })();
      return true;
    },

    stop: () => {
      get().abortController?.abort();
      set({ isStreaming: false, abortController: null });
    },

    reset: () => {
      get().abortController?.abort();
      set({ messages: [], isStreaming: false, abortController: null, error: null });
    },

    setAcknowledged: (messageId, acknowledged) =>
      patchAssistant(messageId, (message) => ({ ...message, acknowledged })),

    install: async (messageId) => {
      const message = get().messages.find((item) => item.id === messageId);
      const proposal = message?.proposal;
      if (!message || !proposal || message.proposalState !== 'active' || message.installing) return;
      if (proposal.scan.blocking) {
        patchAssistant(messageId, (item) => ({
          ...item,
          installError: i18n('This package is blocked by the server scan and cannot be installed.'),
        }));
        return;
      }
      if (proposal.scan.requiresAcknowledgement && !message.acknowledged) {
        patchAssistant(messageId, (item) => ({
          ...item,
          installError: i18n('Acknowledge the high-risk findings before installing.'),
        }));
        return;
      }

      patchAssistant(messageId, (item) => ({
        ...item,
        installing: true,
        installError: undefined,
        installWarning: undefined,
      }));
      try {
        const files = proposal.files.map((file) => {
          const name = file.path.split('/').filter(Boolean).pop() || 'file';
          return new File([file.content], name);
        });
        const result = await useSkillStore.getState().installFromUpload({
          name: proposal.name,
          shared: false,
          files,
          paths: proposal.files.map((file) => file.path),
        }, message.installScope);
        patchAssistant(messageId, (item) => {
          if (result.skill) {
            return {
              ...item,
              installing: false,
              proposalState: 'installed' as const,
              installWarning: result.warning ?? undefined,
            };
          }
          return {
            ...item,
            installing: false,
            installError: result.message ?? i18n('The skill could not be installed.'),
          };
        });
      } catch (error) {
        patchAssistant(messageId, (item) => ({
          ...item,
          installing: false,
          installError: (error as Error).message,
        }));
      }
    },

    setArchiveAcknowledged: (messageId, entryPath, acknowledged) =>
      patchArchiveEntries(messageId, new Set([entryPath]), (entry) => ({ ...entry, acknowledged })),

    installArchive: async (messageId, entryPaths) => {
      const message = get().messages.find((item) => item.id === messageId);
      const proposal = message?.archiveProposal;
      if (!message || !proposal || message.archiveState !== 'active') return;
      if (proposal.skills.some((entry) => entry.state === 'installing')) return;

      // "Install all" leaves conflicts, invalid and unacknowledged high-risk entries out of the
      // batch instead of failing it. An explicit click is validated entry by entry.
      const all = entryPaths === null;
      const selected = new Set(entryPaths ?? []);
      const targets = all
        ? proposal.skills.filter(
          (entry) => entry.state !== 'installed' && archiveEntryInstallable(entry) && !archiveEntryNeedsAcknowledgement(entry),
        )
        : proposal.skills.filter((entry) => selected.has(entry.entryPath) && entry.state !== 'installed');
      if (targets.length === 0) {
        if (all && proposal.skills.some(archiveEntryNeedsAcknowledgement)) {
          patchAssistant(messageId, (item) => ({
            ...item,
            installError: i18n('Acknowledge the high-risk findings before installing.'),
          }));
        }
        return;
      }
      const blocked = targets.find((entry) => !archiveEntryInstallable(entry));
      if (blocked) {
        patchAssistant(messageId, (item) => ({
          ...item,
          installError: blocked.conflict ?? blocked.invalidReason ?? i18n('This skill cannot be installed.'),
        }));
        return;
      }
      if (targets.some(archiveEntryNeedsAcknowledgement)) {
        patchAssistant(messageId, (item) => ({
          ...item,
          installError: i18n('Acknowledge the high-risk findings before installing.'),
        }));
        return;
      }

      const paths = new Set(targets.map((entry) => entry.entryPath));
      const sha256 = proposal.sha256;
      patchAssistant(messageId, (item) => ({ ...item, installError: undefined }));
      patchArchiveEntries(messageId, paths, (entry) => ({
        ...entry,
        state: 'installing',
        error: undefined,
        installWarning: undefined,
      }), sha256);
      try {
        const response = await installSkillArchive({
          url: proposal.sourceUrl,
          sha256: proposal.sha256,
          entries: targets.map((entry) => ({ entryPath: entry.entryPath, name: entry.name })),
          scope: message.installScope,
        });
        const results = new Map(response.results.map((result) => [result.entryPath, result]));
        patchArchiveEntries(messageId, paths, (entry) => {
          const result = results.get(entry.entryPath);
          if (!result) {
            return { ...entry, state: 'error', error: i18n('The server did not report a result for this skill.') };
          }
          if (result.outcome === 'installed') {
            return { ...entry, state: 'installed', error: undefined, installWarning: result.message ?? undefined };
          }
          if (result.outcome === 'conflict') {
            return {
              ...entry,
              state: 'error',
              conflict: result.message ?? i18n('This skill is already installed.'),
              error: result.message ?? undefined,
            };
          }
          if (result.outcome === 'missing') {
            return {
              ...entry,
              state: 'error',
              invalidReason: result.message ?? i18n('This skill is no longer in the archive.'),
            };
          }
          return { ...entry, state: 'error', error: result.message ?? i18n('The skill could not be installed.') };
        }, sha256);
        if (response.results.some((result) => result.outcome === 'installed' && paths.has(result.entryPath))) {
          await useSkillStore.getState().load();
        }
      } catch (error) {
        const reason = (error as Error).message;
        patchAssistant(messageId, (item) => ({ ...item, installError: reason }));
        patchArchiveEntries(messageId, paths, (entry) => ({ ...entry, state: 'error', error: undefined }), sha256);
      }
    },
  };
});
