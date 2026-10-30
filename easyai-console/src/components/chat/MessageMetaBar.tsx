import React, { useMemo, useState } from 'react';
import { GitBranch, Bookmark, Play, Square, Volume2 } from 'lucide-react';
import type { AssistantMessage } from '../../types/message';
import { useChatStore } from '@/services/stores/chat-store';
import { sessionService } from '@/services/session-service';
import { switchToSession } from '@/services/session-switch';
import { SaveToKnowledgeDialog } from '@/components/knowledge/SaveToKnowledgeDialog';
import { useTtsAvailability } from '@/hooks/useTtsAvailability';
import { useTtsPlayback } from '@/hooks/useTtsPlayback';
import { MAX_SPEECH_CHARS, stripMarkdownForSpeech } from '@/utils/speech-text';
import { i18n } from '@/utils/i18n';
import { formatTokenCount } from '../../utils/format';
import { ConfirmDialog } from '../ui/ConfirmDialog';

interface MessageMetaBarProps {
  /** Messages covered by this bar — a single message, or a merged process cluster */
  messages: AssistantMessage[];
  isStreaming?: boolean;
}

/**
 * Hover meta bar: aggregated token usage of the covered turn(s) plus the fork
 * action, which branches from the newest message in the group.
 */
export const MessageMetaBar: React.FC<MessageMetaBarProps> = ({ messages, isStreaming = false }) => {
  const sessionId = useChatStore((s) => s.sessionId);
  const transcriptMode = useChatStore((s) => s.transcriptMode);
  const [forking, setForking] = useState(false);
  const [forkConfirmOpen, setForkConfirmOpen] = useState(false);
  const [saveDialogOpen, setSaveDialogOpen] = useState(false);

  const forkTarget = [...messages].reverse().find((m) => m.messageId);
  // Forking branches into a new editable session; on a read-only background-task transcript there is
  // nothing sensible to branch from, so the action is hidden there.
  const canFork = !isStreaming && !transcriptMode && !!sessionId && !!forkTarget?.messageId;
  const canSave = !isStreaming && messages.some((m) => m.content?.trim());
  const saveContent = messages.map((m) => m.content).filter(Boolean).join('\n\n');
  const saveMessageId = forkTarget?.messageId;

  // ─── Read-aloud ────────────────────────────────────────────────────────────
  // The clip is shared conversation-wide, so this bar only reports the slice that belongs to it.
  const ttsAvailability = useTtsAvailability();
  const tts = useTtsPlayback();
  // Cleaning is not free on a long answer and the list re-renders while streaming; cache it and
  // reuse the same string on click instead of cleaning twice.
  const speechText = useMemo(() => stripMarkdownForSpeech(saveContent), [saveContent]);
  // A reply that is only a code block or an image cleans down to nothing and has no action. Keep
  // the speaker visible but disabled when synthesis is unavailable or the reply is over-long, so
  // the reason is discoverable instead of the control silently vanishing.
  const speechTooLong = speechText.length > MAX_SPEECH_CHARS;
  const showSpeak = !isStreaming && speechText.length > 0;
  const speakEnabled = showSpeak && ttsAvailability.ready && !speechTooLong;
  /** Stable per-message identity: an unsaved message still needs a key of its own. */
  const lastMessage = messages[messages.length - 1];
  const speechKey = saveMessageId ?? `ts:${lastMessage?.timestamp ?? 0}:${speechText.length}`;
  const speaking = tts.activeKey === speechKey;
  const ttsError = speaking ? tts.error : null;
  /** A clip this message owns is loaded: playing, or parked because the browser blocked autoplay. */
  const ttsLoaded = speaking && (tts.state === 'playing' || tts.state === 'ready');
  const ttsLoading = speaking && tts.state === 'loading';
  /** Loading cancels synthesis; playing stops audio and any body still streaming. */
  const ttsStopping = ttsLoading || (speaking && tts.state === 'playing');
  const speakTitle = !ttsAvailability.ready
    ? ttsAvailability.status === 'failed'
      ? i18n('Read-aloud is temporarily unavailable; please try again later')
      : ttsAvailability.status === 'loading' || ttsAvailability.status === 'idle'
        ? i18n('Checking read-aloud availability…')
        : i18n('No usable speech model configured — check Settings → Task Models')
    : speechTooLong
      ? i18n('This reply is too long to read aloud (max 20,000 characters)')
      : ttsLoading
        ? i18n('Stop reading')
        : tts.state === 'ready' && speaking
          ? i18n('Tap to play')
          : ttsLoaded
            ? i18n('Stop reading')
            : i18n('Read aloud');

  let inputTokens = 0;
  let outputTokens = 0;
  let totalCache = 0;
  let durationMs = 0;
  let modelName: string | undefined;
  for (const message of messages) {
    const usage = message.usage;
    if (!usage) continue;
    inputTokens += usage.inputTokens;
    outputTokens += usage.outputTokens;
    totalCache += (usage.cacheReadTokens ?? 0) + (usage.cacheWriteTokens ?? 0);
    durationMs += usage.durationMs ?? 0;
    if (usage.modelName) modelName = usage.modelName;
  }
  const showUsage = outputTokens > 0;
  if (!showUsage && !canFork && !canSave && !showSpeak) return null;

  const handleFork = async () => {
    if (!sessionId || !forkTarget?.messageId || forking) return;
    setForking(true);
    try {
      const branchId = await sessionService.forkSession(sessionId, forkTarget.messageId);
      await switchToSession(branchId);
    } catch (e) {
      console.error('Failed to fork session:', e);
    } finally {
      setForking(false);
    }
  };

  let durationText = '';
  if (showUsage && durationMs > 0) {
    const secs = Math.round(durationMs / 1000);
    if (secs < 60) {
      durationText = `${secs}s`;
    } else {
      const m = Math.floor(secs / 60);
      const s = secs % 60;
      durationText = `${m}m ${s}s`;
    }
  }
  const callTime = showUsage
    ? new Date(messages[messages.length - 1].timestamp).toLocaleTimeString()
    : '';

  return (
    <>
      {/*
        `data-meta-bar` 是移动层的钩子：触屏没有 hover，所以 mobile.css 用它把这条
        信息栏直接展开，而不必把 variant 参数钻到 MessageList 底下。
        行内各 `data-meta-*` 钩子供 mobile.css 在窄屏裁剪条目（见 mobile.css）。
      */}
      <div data-meta-bar className="max-h-0 overflow-hidden group-hover:max-h-8 transition-[max-height] duration-200 ease-out">
        <div data-meta-row className="flex items-center gap-2 text-[11px] text-muted-foreground/60 tabular-nums pt-1">
          {showUsage && (
            <>
              {modelName && (
                <span className="font-mono">{modelName}</span>
              )}
              <span>↑ {formatTokenCount(inputTokens)}</span>
              <span>↓ {formatTokenCount(outputTokens)}</span>
              {totalCache > 0 && (
                <span data-meta-cache>cache {formatTokenCount(totalCache)}</span>
              )}
              {durationText && (
                <span>· {durationText}</span>
              )}
              <span>· {callTime}</span>
            </>
          )}
          {canFork && (
            <button
              data-meta-fork
              onClick={() => setForkConfirmOpen(true)}
              disabled={forking}
              className="p-1 -my-1 rounded hover:bg-muted hover:text-foreground transition-colors disabled:opacity-50"
              title={i18n('Create a branch task from here')}
            >
              <GitBranch className="w-3 h-3" />
            </button>
          )}
          {/* Phones reuse this bar without the `.mobile-app` scope when a desktop build is opened on
              a touch screen, so the save action is cut by pointer capability rather than mobile.css. */}
          {canSave && (
            <button
              onClick={() => setSaveDialogOpen(true)}
              className="p-1 -my-1 rounded hover:bg-muted hover:text-foreground transition-colors [@media(hover:none)]:hidden"
              title={i18n('Save to Knowledge')}
            >
              <Bookmark className="w-3 h-3" />
            </button>
          )}
          {/* Touch sizing and always-visible behaviour live in mobile.css (`.mobile-app [data-meta-tts]`):
              `mobile-hit` is unscoped and its 44px height would inflate this desktop-hover row. */}
          {showSpeak && (
            // Disabled buttons do not consistently dispatch mouse events across browsers; the
            // wrapper keeps the explanatory tooltip available when synthesis cannot be started.
            <span className="inline-flex" title={speakTitle}>
              <button
                data-meta-tts
                onClick={() => tts.toggle(speechKey, speechText)}
                disabled={!speakEnabled && !ttsStopping}
                className={`p-1 -my-1 rounded transition-colors disabled:opacity-40 disabled:hover:bg-transparent ${
                  ttsStopping
                    ? 'bg-red-500 text-white shadow-sm hover:bg-red-600'
                    : 'hover:bg-muted hover:text-foreground'
                }`}
                title={speakTitle}
                aria-label={speakTitle}
                aria-pressed={ttsStopping}
              >
                {ttsStopping ? (
                  <Square className="w-3 h-3 fill-current" />
                ) : tts.state === 'ready' && speaking ? (
                  <Play className="w-3 h-3" />
                ) : (
                  <Volume2 className="w-3 h-3" />
                )}
              </button>
            </span>
          )}
          {ttsError && (
            <span role="status" className="text-red-500/80" title={ttsError}>
              {i18n('Read aloud failed')}
            </span>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={forkConfirmOpen}
        message={i18n('Create a new branch session from this message?')}
        confirmLabel={i18n('Create')}
        onConfirm={() => {
          setForkConfirmOpen(false);
          handleFork();
        }}
        onCancel={() => setForkConfirmOpen(false)}
      />

      <SaveToKnowledgeDialog
        open={saveDialogOpen}
        onClose={() => setSaveDialogOpen(false)}
        content={saveContent}
        sessionId={sessionId ?? undefined}
        messageId={saveMessageId}
      />
    </>
  );
};
