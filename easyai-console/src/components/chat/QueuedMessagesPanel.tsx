import React, { useState, useCallback } from 'react';
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
import { removeQueueMessage, updateQueueMessage } from '@/services/chat-service';
import type { QueuedMessage } from '@/types/message';
import { isImageAttachment, toChatAttachment } from '@/utils/attachment-utils';

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
      <span className="flex-1 truncate text-foreground/90 min-w-0">
        {msg.content}
      </span>

      {/* Attachment previews */}
      {msg.attachments && msg.attachments.length > 0 && (
        <div className="flex items-center gap-1 shrink-0">
          {msg.attachments.filter(isImageAttachment).map((a) => (
            <img
              key={a.id}
              src={a.filePath
                ? `/api/files/serve?path=${encodeURIComponent(a.filePath)}`
                : `data:${a.mimeType};base64,${a.data}`}
              alt={a.name}
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
          title={isSteer ? 'Convert to FollowUp' : 'Convert to Steer'}
          onClick={() => onToggleType(msg.id)}
        >
          <ArrowRightLeft className="w-3.5 h-3.5" />
        </button>
        <button
          className="p-1 rounded hover:bg-background/80 text-muted-foreground hover:text-foreground transition-colors"
          title="Edit"
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
  onSave: (id: string, content: string) => void;
  onCancel: () => void;
}

const InlineEdit: React.FC<InlineEditProps> = ({ msg, onSave, onCancel }) => {
  const [value, setValue] = useState(msg.content);

  const handleSave = () => {
    const trimmed = value.trim();
    if (trimmed && trimmed !== msg.content) {
      onSave(msg.id, trimmed);
    } else {
      onCancel();
    }
  };

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded-md bg-muted/50 border-l-3 border-l-green-400 text-sm">
      <input
        className="flex-1 bg-background border border-input rounded px-2 py-0.5 text-sm focus:outline-none focus:ring-1 focus:ring-ring min-w-0"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') handleSave();
          if (e.key === 'Escape') onCancel();
        }}
        autoFocus
      />
      <button
        className="p-1 rounded hover:bg-background/80 text-green-600 hover:text-green-700 shrink-0"
        onClick={handleSave}
        title="Save"
      >
        <Check className="w-3.5 h-3.5" />
      </button>
      <button
        className="p-1 rounded hover:bg-background/80 text-muted-foreground hover:text-foreground shrink-0"
        onClick={onCancel}
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
      import('@/services/chat-service').then(({ reorderQueueMessages }) => {
        reorderQueueMessages(sessionId, backendIds).catch(console.error);
      });
    }
  }, [queuedMessages, reorderQueuedMessages, sessionId]);

  const handleDelete = useCallback((id: string) => {
    const msg = queuedMessages.find((m) => m.id === id);
    removeQueuedMessage(id);
    if (msg?.backendQueueId && sessionId) {
      removeQueueMessage(sessionId, msg.backendQueueId).catch(console.error);
    }
  }, [queuedMessages, removeQueuedMessage, sessionId]);

  const handleEditSave = useCallback((id: string, content: string) => {
    const msg = queuedMessages.find((m) => m.id === id);
    updateQueuedMessage(id, content);
    if (msg?.backendQueueId && sessionId) {
      updateQueueMessage(sessionId, msg.backendQueueId, content).catch(console.error);
    }
    setEditingId(null);
  }, [queuedMessages, updateQueuedMessage, sessionId]);

  const handleToggleType = useCallback((id: string) => {
    const msg = queuedMessages.find((m) => m.id === id);
    if (!msg) return;

    const newType = msg.type === 'steer' ? 'followUp' : 'steer';

    // If synced to backend, we need to remove old and add new
    if (msg.backendQueueId && sessionId) {
      // Optimistic local update: remove old, add new
      removeQueuedMessage(id);
      const newMsg: QueuedMessage = {
        id: `queued-${Date.now()}-${Math.random().toString(36).slice(2)}`,
        content: msg.content,
        type: newType as 'steer' | 'followUp',
        status: 'syncing',
        attachments: msg.attachments,
      };
      addQueuedMessage(newMsg);

      // Backend: remove old, add new (with attachments)
      const chatAttachments = msg.attachments?.filter((a) => !!a.filePath).map(toChatAttachment);
      removeQueueMessage(sessionId, msg.backendQueueId)
        .then(() => import('@/services/chat-service').then(({ addQueueMessage }) =>
          addQueueMessage(
            sessionId,
            msg.content,
            newType as 'steer' | 'followUp',
            chatAttachments && chatAttachments.length > 0 ? chatAttachments : undefined
          )
        ))
        .then((resp) => {
          // Update the new message's backend ID
          useChatStore.setState((state) => ({
            queuedMessages: state.queuedMessages.map((m) =>
              m.id === newMsg.id ? { ...m, backendQueueId: resp.id, status: 'synced' as const } : m
            ),
          }));
        })
        .catch(console.error);
    } else {
      // Not synced yet, just update locally
      updateQueuedMessage(id, msg.content);
      useChatStore.setState((state) => ({
        queuedMessages: state.queuedMessages.map((m) =>
          m.id === id ? { ...m, type: newType as 'steer' | 'followUp' } : m
        ),
      }));
    }
  }, [queuedMessages, removeQueuedMessage, addQueuedMessage, updateQueuedMessage, sessionId]);

  if (queuedMessages.length === 0) return null;

  return (
    <div className="rounded-md border border-border/60 bg-muted/20 overflow-hidden">
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
                    onEdit={(id) => setEditingId(id)}
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
