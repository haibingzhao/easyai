import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Pencil, Trash2, ArrowRightLeft, Check, X, ChevronDown, ChevronUp } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { removeQueueMessage, updateQueueMessage, reorderQueueMessages, addQueueMessage } from '@/services/chat-service';
import type { QueuedMessage } from '@/types/message';
import { isImageAttachment, toChatAttachment, populateMessageEditor, readMessageEditorText, copyMessageSelection } from '@/utils/attachment-utils';
import { isSideEffectCommand, parseCommand, serializeCommand } from '@/utils/command-utils';
import type { CommandIdentity } from '@/utils/command-utils';
import { useSlashCommand } from '@/hooks/useSlashCommand';
import { useAgentStore } from '@/services/stores/agent-store';
import { useProjectStore } from '@/services/stores/project-store';
import { i18n } from '@/utils/i18n';
import { UserMessageContent } from './UserMessage';
import { AttachmentImage } from './AttachmentImage';

const QUEUE_COMMAND_WARNING = 'Queued commands with side effects cannot be edited or converted. Delete and send a new command instead.';

// ===================== Sortable Item =====================

interface SortableQueuedMessageProps {
  msg: QueuedMessage;
  onEdit: (id: string) => void;
  onDelete: (id: string) => void;
  onToggleType: (id: string) => void;
}

const SortableQueuedMessage: React.FC<SortableQueuedMessageProps> = ({ msg, onEdit, onDelete, onToggleType }) => {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: msg.id });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  const isSteer = msg.type === 'steer';
  const editBlocked = isSideEffectCommand(msg.content, msg.commandCategory);
  const isFailed = msg.status === 'failed';
  const statusLabel = isFailed ? 'Failed' : isSteer ? 'Steer' : 'Waiting';
  const statusColor = isFailed
    ? 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'
    : isSteer
      ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300'
      : 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300';
  const borderColor = isFailed
    ? 'border-l-red-400'
    : isSteer
      ? 'border-l-blue-400'
      : 'border-l-amber-400';

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`flex items-center gap-2 px-2 py-1.5 rounded-md bg-muted/50 border-l-3 ${borderColor} group text-sm`}
    >
      {/* Drag handle */}
      <button
        className="cursor-grab active:cursor-grabbing text-muted-foreground/50 hover:text-muted-foreground shrink-0"
        {...attributes}
        {...listeners}
      >
        <GripVertical className="w-3.5 h-3.5" />
      </button>

      {/* Status label */}
      <span className={`text-xs px-1.5 py-0.5 rounded-full font-medium shrink-0 ${statusColor}`}>
        {statusLabel}
      </span>

      {/* Content */}
      <span
        className="flex-1 truncate text-foreground/90 min-w-0"
        onCopy={(e) => { if (copyMessageSelection(e.currentTarget, e.clipboardData)) e.preventDefault(); }}
      >
        <UserMessageContent content={msg.content} />
      </span>

      {/* Attachment previews */}
      {msg.attachments && msg.attachments.length > 0 && (
        <div className="flex items-center gap-1 shrink-0">
          {msg.attachments.filter(isImageAttachment).map((a) => (
            <AttachmentImage
              key={a.id}
              attachment={a}
              className="w-5 h-5 object-cover rounded"
            />
          ))}
          {msg.attachments.filter((a) => !isImageAttachment(a)).map((a) => (
            <span key={a.id} className="text-xs text-muted-foreground bg-muted px-1 rounded truncate max-w-[60px]">
              {a.name}
            </span>
          ))}
        </div>
      )}

      {/* Sync indicator */}
      {msg.status === 'syncing' && (
        <span className="text-xs text-muted-foreground shrink-0 animate-pulse">...</span>
      )}

      {/* Action buttons (visible on hover) */}
      <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
        <button
          className="p-1 rounded hover:bg-background/80 text-muted-foreground hover:text-blue-500 transition-colors"
          title={editBlocked ? i18n(QUEUE_COMMAND_WARNING) : isSteer ? 'Convert to FollowUp' : 'Convert to Steer'}
          aria-disabled={editBlocked || msg.status === 'syncing'}
          onClick={() => onToggleType(msg.id)}
        >
          <ArrowRightLeft className="w-3.5 h-3.5" />
        </button>
        <button
          className="p-1 rounded hover:bg-background/80 text-muted-foreground hover:text-foreground transition-colors"
          title={editBlocked ? i18n(QUEUE_COMMAND_WARNING) : 'Edit'}
          aria-disabled={editBlocked || msg.status === 'syncing'}
          onClick={() => onEdit(msg.id)}
        >
          <Pencil className="w-3.5 h-3.5" />
        </button>
        <button
          className="p-1 rounded hover:bg-background/80 text-muted-foreground hover:text-destructive transition-colors"
          title="Delete"
          onClick={() => onDelete(msg.id)}
        >
          <Trash2 className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
};

// ===================== Inline Edit =====================

interface InlineEditProps {
  msg: QueuedMessage;
  onSave: (id: string, content: string) => Promise<void>;
  onCancel: () => void;
}

const InlineEdit: React.FC<InlineEditProps> = ({ msg, onSave, onCancel }) => {
  const editorRef = useRef<HTMLDivElement>(null);
  const commandRef = useRef<CommandIdentity | null>(null);
  const savingRef = useRef(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const editor = editorRef.current;
    if (!editor) return;
    commandRef.current = populateMessageEditor(editor, msg.content);
    editor.focus();
  }, [msg.content]);

  const syncCommand = () => {
    const editor = editorRef.current;
    if (!editor) return;
    const chip = editor.querySelector<HTMLElement>('.command-chip');
    commandRef.current = chip ? parseCommand(chip.dataset.commandToken ?? '')?.command ?? null : null;
    if (!chip && parseCommand(readMessageEditorText(editor))?.command.source) {
      commandRef.current = populateMessageEditor(editor, readMessageEditorText(editor));
    }
  };

  const handleSave = async () => {
    if (!editorRef.current || savingRef.current) return;
    syncCommand();
    const content = serializeCommand(commandRef.current, readMessageEditorText(editorRef.current));
    if (!content || content === msg.content) { onCancel(); return; }
    savingRef.current = true;
    setSaving(true);
    try { await onSave(msg.id, content); }
    finally { savingRef.current = false; setSaving(false); }
  };

  const handleClipboard = (e: React.ClipboardEvent<HTMLDivElement>, cut = false) => {
    if (cut && savingRef.current) { e.preventDefault(); return; }
    if (copyMessageSelection(e.currentTarget, e.clipboardData, cut)) {
      e.preventDefault();
      if (cut) syncCommand();
    }
  };

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded-md bg-muted/50 border-l-3 border-l-green-400 text-sm">
      <div
        ref={editorRef}
        className="message-editor flex-1 bg-background border border-input rounded px-2 py-0.5 text-sm focus:outline-none focus:ring-1 focus:ring-ring min-w-0 whitespace-pre-wrap"
        contentEditable={!saving}
        onInput={syncCommand}
        onCopy={(e) => handleClipboard(e)}
        onCut={(e) => handleClipboard(e, true)}
        onPaste={(e) => {
          e.preventDefault();
          if (savingRef.current) return;
          document.execCommand('insertText', false, e.clipboardData.getData('text/plain'));
          syncCommand();
        }}
        onKeyDown={(e) => {
          if (e.nativeEvent.isComposing) return;
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void handleSave(); }
          if (e.key === 'Escape' && !savingRef.current) onCancel();
        }}
      />
      <button
        className="p-1 rounded hover:bg-background/80 text-green-600 hover:text-green-700 shrink-0"
        onClick={handleSave}
        disabled={saving}
        title="Save"
      >
        <Check className="w-3.5 h-3.5" />
      </button>
      <button
        className="p-1 rounded hover:bg-background/80 text-muted-foreground hover:text-foreground shrink-0"
        onClick={onCancel}
        disabled={saving}
        title="Cancel"
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
};

// ===================== Main Panel =====================

export const QueuedMessagesPanel: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const agentId = useAgentStore((state) => state.selectedAgentId);
  const projectId = useProjectStore((state) => state.currentProject?.id);
  const { validateCommand, isCurrentContext } = useSlashCommand(agentId, projectId);
  const busyIds = useRef(new Set<string>());

  const {
    sessionId,
    queuedMessages,
    removeQueuedMessage,
    updateQueuedMessage,
    reorderQueuedMessages,
  } = useChatStore((state) => ({
    sessionId: state.sessionId,
    queuedMessages: state.queuedMessages,
    removeQueuedMessage: state.removeQueuedMessage,
    updateQueuedMessage: state.updateQueuedMessage,
    reorderQueuedMessages: state.reorderQueuedMessages,
  }));

  // Also grab the full store for type toggle (needs add + remove + reorder)
  const addQueuedMessage = useChatStore((s) => s.addQueuedMessage);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragEnd = useCallback((event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    const ids = queuedMessages.map((m) => m.id);
    const oldIndex = ids.indexOf(active.id as string);
    const newIndex = ids.indexOf(over.id as string);
    if (oldIndex === -1 || newIndex === -1) return;

    const newIds = arrayMove(ids, oldIndex, newIndex);
    reorderQueuedMessages(newIds);

    // Sync reorder to backend (using backend queue IDs)
    const backendIds = queuedMessages
      .map((m) => {
        const newIdx = newIds.indexOf(m.id);
        return { newIdx, backendId: m.backendQueueId };
      })
      .sort((a, b) => a.newIdx - b.newIdx)
      .map((x) => x.backendId)
      .filter((id): id is string => !!id);

    if (sessionId && backendIds.length > 0) {
      reorderQueueMessages(sessionId, backendIds).catch(console.error);
    }
  }, [queuedMessages, reorderQueuedMessages, sessionId]);

  const handleDelete = useCallback((id: string) => {
    const msg = queuedMessages.find((m) => m.id === id);
    removeQueuedMessage(id);
    if (msg?.backendQueueId && sessionId) {
      removeQueueMessage(sessionId, msg.backendQueueId).catch(console.error);
    }
  }, [queuedMessages, removeQueuedMessage, sessionId]);

  const handleEdit = useCallback((id: string) => {
    const msg = queuedMessages.find((item) => item.id === id);
    if (!msg || busyIds.current.has(id)) return;
    if (isSideEffectCommand(msg.content, msg.commandCategory)) { setError(i18n(QUEUE_COMMAND_WARNING)); return; }
    if (msg.status === 'syncing') { setError(i18n('Wait for the queued message to finish syncing.')); return; }
    setError(null);
    setEditingId(id);
  }, [queuedMessages]);

  const handleEditSave = useCallback(async (id: string, content: string) => {
    const msg = queuedMessages.find((item) => item.id === id);
    if (!msg || busyIds.current.has(id)) return;
    const parsed = parseCommand(content);
    const category = parsed?.command.source ? 'SKILL'
      : parseCommand(msg.content)?.command.name === parsed?.command.name ? msg.commandCategory : undefined;
    if (isSideEffectCommand(msg.content, msg.commandCategory) || isSideEffectCommand(content, category)) {
      setError(i18n(QUEUE_COMMAND_WARNING));
      return;
    }
    busyIds.current.add(id);
    try {
      const validationError = await validateCommand(parsed?.command);
      if (validationError) { setError(validationError); return; }
      if (!useChatStore.getState().queuedMessages.some((item) => item.id === id) || useChatStore.getState().sessionId !== sessionId) return;
      if (msg.backendQueueId && sessionId) await updateQueueMessage(sessionId, msg.backendQueueId, content);
      if (!isCurrentContext() || useChatStore.getState().sessionId !== sessionId) return;
      updateQueuedMessage(id, content);
      useChatStore.setState((state) => ({
        queuedMessages: state.queuedMessages.map((item) => item.id === id ? { ...item, commandCategory: category } : item),
      }));
      setError(null);
      setEditingId(null);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      busyIds.current.delete(id);
    }
  }, [queuedMessages, updateQueuedMessage, sessionId, validateCommand, isCurrentContext]);

  const handleToggleType = useCallback(async (id: string) => {
    const msg = queuedMessages.find((item) => item.id === id);
    if (!msg || busyIds.current.has(id)) return;
    if (isSideEffectCommand(msg.content, msg.commandCategory)) { setError(i18n(QUEUE_COMMAND_WARNING)); return; }
    if (msg.status === 'syncing') { setError(i18n('Wait for the queued message to finish syncing.')); return; }
    busyIds.current.add(id);
    const newType = msg.type === 'steer' ? 'followUp' : 'steer';
    let replacementId: string | undefined;
    try {
      const parsed = parseCommand(msg.content);
      const validationError = await validateCommand(parsed?.command);
      if (validationError) { setError(validationError); return; }
      if (!useChatStore.getState().queuedMessages.some((item) => item.id === id) || useChatStore.getState().sessionId !== sessionId) return;
      // Both steer and followUp use the same codec; attachments remain storage references.
      const content = parsed ? serializeCommand(parsed.command, parsed.args) : msg.content;
      if (msg.backendQueueId && sessionId) {
        await removeQueueMessage(sessionId, msg.backendQueueId);
        if (!isCurrentContext() || useChatStore.getState().sessionId !== sessionId) return;
        removeQueuedMessage(id);
        replacementId = `queued-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        addQueuedMessage({ ...msg, id: replacementId, backendQueueId: undefined, content, type: newType, status: 'syncing' });
        const chatAttachments = msg.attachments?.filter((attachment) => !!attachment.filePath).map(toChatAttachment);
        const response = await addQueueMessage(sessionId, content, newType, chatAttachments?.length ? chatAttachments : undefined);
        useChatStore.setState((state) => ({
          queuedMessages: state.queuedMessages.map((item) => item.id === replacementId
            ? { ...item, backendQueueId: response.id, status: 'synced' as const } : item),
        }));
      } else {
        useChatStore.setState((state) => ({
          queuedMessages: state.queuedMessages.map((item) => item.id === id ? { ...item, content, type: newType } : item),
        }));
      }
      setError(null);
    } catch (err) {
      setError((err as Error).message);
      if (replacementId) useChatStore.setState((state) => ({
        queuedMessages: state.queuedMessages.map((item) => item.id === replacementId ? { ...item, status: 'failed' as const } : item),
      }));
    } finally {
      busyIds.current.delete(id);
    }
  }, [queuedMessages, sessionId, validateCommand, isCurrentContext, removeQueuedMessage, addQueuedMessage]);

  if (queuedMessages.length === 0) return null;

  return (
    <div className="rounded-md border border-border/60 bg-muted/20 overflow-hidden">
      {error && <div role="alert" className="px-3 py-1 text-xs text-destructive">{error}</div>}
      {/* Header */}
      <button
        className="flex items-center gap-2 w-full px-3 py-1.5 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
        onClick={() => setCollapsed((c) => !c)}
      >
        {collapsed ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronUp className="w-3.5 h-3.5" />}
        <span>Message queued</span>
        <span className="text-xs bg-muted rounded-full px-1.5 py-0.5">{queuedMessages.length}</span>
      </button>

      {/* Message list */}
      {!collapsed && (
        <div className="px-2 pb-2 flex flex-col gap-1">
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
          >
            <SortableContext
              items={queuedMessages.map((m) => m.id)}
              strategy={verticalListSortingStrategy}
            >
              {queuedMessages.map((msg) =>
                editingId === msg.id ? (
                  <InlineEdit
                    key={msg.id}
                    msg={msg}
                    onSave={handleEditSave}
                    onCancel={() => setEditingId(null)}
                  />
                ) : (
                  <SortableQueuedMessage
                    key={msg.id}
                    msg={msg}
                    onEdit={handleEdit}
                    onDelete={handleDelete}
                    onToggleType={handleToggleType}
                  />
                )
              )}
            </SortableContext>
          </DndContext>
        </div>
      )}
    </div>
  );
};
