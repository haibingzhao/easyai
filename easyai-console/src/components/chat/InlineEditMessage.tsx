import React, { useState, useRef, useCallback, useEffect, useMemo } from 'react';
import { Send, X, Bot, Paperclip } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useProjectStore, adoptHandshakeWorkspace } from '@/services/stores/project-store';
import { useNavStore } from '@/services/stores/nav-store';
import { pathToFile, IMAGE_EXTS } from '@/services/file-browser-service';
import { sendMessageToBackend, getCurrentSendService, type ChatService } from '../../services/chat-service';
import { sessionService } from '../../services/session-service';
import { editMessage, getCheckpoints } from '@/services/checkpoint-service';
import type { CheckpointInfo } from '@/types/checkpoint';
import { ModelSelector } from './ModelSelector';
import { AgentSelector } from './AgentSelector';
import { SlashCommandPopover } from './SlashCommandPopover';
import { useSlashCommand } from '@/hooks/useSlashCommand';
import { useAttachmentManager } from '@/hooks/useAttachmentManager';
import { AttachmentPreviewBar } from './AttachmentPreviewBar';
import { AttachmentImage } from './AttachmentImage';
import type { SlashCommand } from '@/types/command';
import type { CommandIdentity } from '@/utils/command-utils';
import { parseCommand, serializeCommand } from '@/utils/command-utils';
import { createCommandChip, populateMessageEditor, readMessageEditorText, copyMessageSelection } from '@/utils/attachment-utils';
import type { Message, Attachment } from '../../types/message';
import type { ModelCapabilities } from '@/types/settings';
import { isImageAttachment, isTextAttachment, toChatAttachment, buildMessageWithTextAttachments, buildFileRef, buildFolderRef, parseFileRefs, resolveEmptyFileRefs } from '../../utils/attachment-utils';
import { useMention } from '@/hooks/useMention';
import type { MentionItem } from '@/hooks/useMention';
import { ResourceMentionPopover } from '@/components/chat/ResourceMentionPopover';
import { i18n } from '../../utils/i18n';
import { createRoot, type Root } from 'react-dom/client';

interface InlineEditMessageProps {
  message: Message & { role: 'user' | 'user-with-attachments' };
  messageIndex: number;
  onCancel: () => void;
  onSubmit?: () => void;
}

export const InlineEditMessage: React.FC<InlineEditMessageProps> = ({ message, messageIndex, onCancel, onSubmit }) => {
  const [editorValue, setEditorValue] = useState('');
  const [selectedCommand, setSelectedCommand] = useState<CommandIdentity | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [currentModelId, setCurrentModelId] = useState('');
  const [currentCapabilities, setCurrentCapabilities] = useState<ModelCapabilities | undefined>();
  const [isModelLoading, setIsModelLoading] = useState(true);
  const [showConfirmDialog, setShowConfirmDialog] = useState(false);
  const [pendingRevertFiles, setPendingRevertFiles] = useState<{ path: string; additions: number }[]>([]);
  const editorRef = useRef<HTMLDivElement>(null);
  const commandsLoadedRef = useRef(false);
  const imageRootsRef = useRef<Map<HTMLElement, Root>>(new Map());

  const {
    sessionId,
    messages,
    checkpointsByMessageId,
    setStreaming,
    addMessage,
    handleEvent,
    truncateMessagesFrom,
    setRevertState,
    isAwaitingPermission,
  } = useChatStore((state) => ({
    sessionId: state.sessionId,
    messages: state.messages,
    checkpointsByMessageId: state.checkpointsByMessageId,
    setStreaming: state.setStreaming,
    addMessage: state.addMessage,
    handleEvent: state.handleEvent,
    truncateMessagesFrom: state.truncateMessagesFrom,
    setRevertState: state.setRevertState,
    isAwaitingPermission: state.isAwaitingPermission,
  }));

  const selectedAgentId = useAgentStore((state) => state.selectedAgentId);
  const currentProjectId = useProjectStore((state) => state.currentProject?.id);

  // Slash command autocomplete
  const slashCommand = useSlashCommand(selectedAgentId);
  const { validateCommand, isCurrentContext } = slashCommand;
  const commandError = slashCommand.selectionError(selectedCommand);

  // @ mention autocomplete
  const mention = useMention();

  // --- Chip DOM management ---

  const insertChip = useCallback((cmd: SlashCommand) => {
    const editor = editorRef.current;
    if (!editor) return;

    const chip = createCommandChip(cmd);

    // Remove existing chip if any
    const existing = editor.querySelector('.command-chip');
    if (existing) existing.remove();

    // Insert at beginning
    editor.insertBefore(chip, editor.firstChild);

    // Place cursor after chip
    const range = document.createRange();
    const sel = window.getSelection();
    if (sel) {
      range.setStartAfter(chip);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
    }
  }, []);

  const removeChip = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) return;
    const chip = editor.querySelector('.command-chip');
    if (chip) {
      chip.remove();
      if (editor.childNodes.length === 0) {
        editor.appendChild(document.createTextNode(''));
      }
    }
  }, []);

  // --- Command select handler ---

  const handleCommandSelect = useCallback((cmd: SlashCommand) => {
    setSelectedCommand(cmd);
    insertChip(cmd);
    // Clear any typed text (the "/" query)
    const editor = editorRef.current;
    if (editor) {
      const chip = editor.querySelector('.command-chip');
      Array.from(editor.childNodes).forEach((node) => {
        if (node !== chip) node.remove();
      });
      editor.appendChild(document.createTextNode(''));
    }
    setEditorValue('');
    slashCommand.close();
    editorRef.current?.focus();
  }, [insertChip, slashCommand]);

  // --- Mention DOM helpers ---

  /** Get current cursor text offset within the editor (encoded-aware: mention chips count as their ref length) */
  const getCursorPosition = useCallback((): number => {
    const editor = editorRef.current;
    if (!editor) return 0;
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return 0;
    const range = sel.getRangeAt(0);

    let offset = 0;
    let foundCursor = false;

    const walk = (node: Node) => {
      if (foundCursor) return;

      if (node === range.startContainer && node.nodeType === Node.TEXT_NODE) {
        const textBefore = (node.textContent || '').slice(0, range.startOffset);
        offset += textBefore.length;
        foundCursor = true;
        return;
      }

      if (node === range.startContainer && node.nodeType === Node.ELEMENT_NODE) {
        for (let i = 0; i < range.startOffset && i < node.childNodes.length; i++) {
          walk(node.childNodes[i]);
          if (foundCursor) return;
        }
        if (range.startOffset >= node.childNodes.length) {
          foundCursor = true;
        }
        return;
      }

      if (node.nodeType === Node.ELEMENT_NODE) {
        const el = node as HTMLElement;
        if (el.classList.contains('command-chip')) return;
        if (el.classList.contains('image-chip')) return;
        if (el.classList.contains('mention-chip')) {
          const path = el.dataset.path || '';
          const type = el.dataset.type || 'file';
          const name = (el.textContent || '').replace(/^[📄📁]\s*/, '');
          const encoded = type === 'directory' ? buildFolderRef(name, path) : buildFileRef(name, path);
          offset += encoded.length;
          return;
        }
        el.childNodes.forEach(walk);
      } else if (node.nodeType === Node.TEXT_NODE) {
        offset += (node.textContent || '').length;
      }
    };

    editor.childNodes.forEach(walk);
    return offset;
  }, []);

  /** Insert a DOM node at the current cursor position */
  const insertNodeAtCursor = useCallback((editor: HTMLElement, node: HTMLElement) => {
    const sel = window.getSelection();
    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0);
      range.deleteContents();
      range.insertNode(node);
      range.setStartAfter(node);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
    } else {
      editor.appendChild(node);
    }
  }, []);

  /** Insert text after a given node and position cursor after it */
  const insertTextAfterNode = useCallback((node: Node, text: string) => {
    const textNode = document.createTextNode(text);
    node.parentNode?.insertBefore(textNode, node.nextSibling);
    const sel = window.getSelection();
    if (sel) {
      const range = document.createRange();
      range.setStartAfter(textNode);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
    }
  }, []);

  /** Create an inline image chip DOM element with a React-rendered AttachmentImage. */
  const createImageChip = useCallback((attachment: Attachment): HTMLElement => {
    const container = document.createElement('span');
    container.className = 'image-chip';
    container.contentEditable = 'false';
    container.dataset.attachmentId = attachment.id;
    container.style.cssText = 'display:inline-flex;align-items:center;vertical-align:middle;margin:0 2px;';

    const root = createRoot(container);
    root.render(
      <AttachmentImage attachment={attachment} className="w-5 h-5 object-cover rounded" inline />,
    );
    imageRootsRef.current.set(container, root);
    return container;
  }, []);

  /** Unmount React root for a given image chip container. */
  const unmountImageChip = useCallback((container: HTMLElement) => {
    const root = imageRootsRef.current.get(container);
    if (root) {
      root.unmount();
      imageRootsRef.current.delete(container);
    }
  }, []);

  /** Unmount all inline image chip React roots. */
  const unmountAllImageChips = useCallback(() => {
    imageRootsRef.current.forEach((root) => root.unmount());
    imageRootsRef.current.clear();
  }, []);

  /** Remove the @ trigger text from DOM (finds @ directly in text nodes) */
  const removeAtTriggerText = useCallback((editor: HTMLElement) => {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return;

    const range = sel.getRangeAt(0);
    const preCaretRange = document.createRange();
    preCaretRange.selectNodeContents(editor);
    preCaretRange.setEnd(range.startContainer, range.startOffset);

    const textBefore = preCaretRange.toString();
    const atIdx = textBefore.lastIndexOf('@');
    if (atIdx === -1) return;

    const charsToDelete = textBefore.length - atIdx;
    let offset = 0;
    let deleted = 0;
    const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT);
    let node = walker.nextNode();
    while (node && deleted < charsToDelete) {
      const len = (node.textContent || '').length;
      const nodeStart = offset;
      const nodeEnd = offset + len;
      const deleteStart = Math.max(atIdx, nodeStart) - nodeStart;
      const deleteEnd = Math.min(nodeEnd, atIdx + charsToDelete) - nodeStart;
      if (deleteStart < len && deleteStart < deleteEnd) {
        const text = node.textContent || '';
        node.textContent = text.slice(0, deleteStart) + text.slice(deleteEnd);
        deleted += deleteEnd - deleteStart;
      }
      offset = nodeEnd;
      node = walker.nextNode();
    }
    offset = 0;
    const walker2 = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT);
    let n = walker2.nextNode();
    while (n) {
      const len = (n.textContent || '').length;
      if (offset + len >= atIdx) {
        const r = document.createRange();
        r.setStart(n, atIdx - offset);
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        break;
      }
      offset += len;
      n = walker2.nextNode();
    }
  }, []);

  // --- Editor text extraction (needed by submit handlers and insertMentionChip) ---

  /** Extract text content, excluding command chip text and replacing mention chips with encoded refs */
  const getEditorText = useCallback((): string => {
    const editor = editorRef.current;
    return editor ? readMessageEditorText(editor) : '';
  }, []);

  /** Insert a mention chip at the cursor position, removing the @ trigger text */
  const insertMentionChip = useCallback((item: MentionItem) => {
    const editor = editorRef.current;
    if (!editor) return;

    // Remove the @ trigger text
    removeAtTriggerText(editor);

    // Create mention chip element
    const chip = document.createElement('span');
    chip.className = `mention-chip mention-${item.type === 'directory' ? 'folder' : 'file'}`;
    chip.contentEditable = 'false';
    chip.dataset.path = item.path;
    chip.dataset.type = item.type;
    chip.textContent = item.type === 'directory' ? `📁 ${item.name}` : `📄 ${item.name}`;

    // Insert chip at cursor position
    insertNodeAtCursor(editor, chip);

    // Insert space after chip
    insertTextAfterNode(chip, '\u00A0');

    // Close mention popover
    mention.close();

    // Update editor value
    setEditorValue(getEditorText());
  }, [mention, removeAtTriggerText, insertNodeAtCursor, insertTextAfterNode, getEditorText]);

  const handleModelChange = useCallback((configId: string, capabilities?: ModelCapabilities) => {
    setCurrentModelId(configId);
    setCurrentCapabilities(capabilities);
    setIsModelLoading(false);
  }, []);

  const visionSupported = currentCapabilities?.vision === true;

  // Attachments whose inline ref is restored as an editor chip must not also sit in the
  // attachment bar, or the same file shows twice in the edit UI. Images stay in the bar:
  // the text protocol restores them as plain chips, so the bar remains their only
  // thumbnail preview (and keeps the vision capability check honest).
  const initialAttachments = useMemo(() => {
    const inlinePaths = new Set(parseFileRefs(message.content).map((ref) => ref.path));
    return (message.attachments ?? []).filter((a) => !a.filePath || !inlinePaths.has(a.filePath) || isImageAttachment(a));
  }, [message.content, message.attachments]);

  const {
    attachments,
    setAttachments,
    processingFiles,
    isProcessingFiles,
    uploadPendingAttachments,
    fileInputRef,
    handleFiles,
    removeAttachment,
    getImageFilesFromPaste,
  } = useAttachmentManager({ visionSupported, sessionId, initialAttachments, onError: (msg) => setError(msg) });

  /** Wrap handleFiles to insert inline image chips into the editor after processing. */
  const handleFilesWithChips = useCallback(async (files: File[]) => {
    const editor = editorRef.current;
    const newAttachments = await handleFiles(files);
    if (!editor || newAttachments.length === 0) return;
    for (const att of newAttachments) {
      if (isImageAttachment(att)) {
        const chip = createImageChip(att);
        insertNodeAtCursor(editor, chip);
        insertTextAfterNode(chip, '\u00A0');
      }
    }
    setEditorValue(getEditorText());
  }, [handleFiles, createImageChip, insertNodeAtCursor, insertTextAfterNode, getEditorText]);
  const handleFilesWithChipsRef = useRef<(files: File[]) => Promise<void>>(null!);
  handleFilesWithChipsRef.current = handleFilesWithChips;

  // --- Pending "Add to Chat" requests from the file tree context menu ---

  /** Last cursor range inside the editor — right-clicking the tree moves the DOM selection */
  const lastEditorRangeRef = useRef<Range | null>(null);
  useEffect(() => {
    const onSelectionChange = () => {
      const sel = window.getSelection();
      const editor = editorRef.current;
      if (!sel || !editor || sel.rangeCount === 0) return;
      const range = sel.getRangeAt(0);
      if (editor.contains(range.commonAncestorContainer)) {
        lastEditorRangeRef.current = range.cloneRange();
      }
    };
    document.addEventListener('selectionchange', onSelectionChange);
    return () => document.removeEventListener('selectionchange', onSelectionChange);
  }, []);

  const restoreEditorCursor = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) return;
    const range = lastEditorRangeRef.current;
    const sel = window.getSelection();
    if (range && editor.contains(range.commonAncestorContainer)) {
      sel?.removeAllRanges();
      sel?.addRange(range);
      return;
    }
    // No cursor history (e.g. editor just mounted): place the cursor at the editor end
    editor.focus();
    const endRange = document.createRange();
    endRange.selectNodeContents(editor);
    endRange.collapse(false);
    sel?.removeAllRanges();
    sel?.addRange(endRange);
  }, []);

  const handleEditorFocus = useCallback(() => {
    useNavStore.getState().setActiveChatEditor('inline');
  }, []);

  // While mounted and focused this editor owns "Add to Chat"; hand ownership back
  // to the main composer once the inline edit closes.
  useEffect(() => () => {
    if (useNavStore.getState().activeChatEditor === 'inline') {
      useNavStore.getState().setActiveChatEditor('main');
    }
  }, []);

  // Claim ownership on mount rather than relying on the mount focus() to fire onFocus:
  // the editor may already hold focus (no focus event), and under StrictMode the remount
  // pass skips focus() entirely because the history-restore effect early-returns.
  useEffect(() => {
    useNavStore.getState().setActiveChatEditor('inline');
  }, []);

  /** Insert a file/folder mention chip at the editor cursor */
  const insertMentionChipAtCursor = useCallback((name: string, path: string, type: 'file' | 'directory') => {
    const editor = editorRef.current;
    if (!editor) return;
    const chip = document.createElement('span');
    chip.className = `mention-chip mention-${type === 'directory' ? 'folder' : 'file'}`;
    chip.contentEditable = 'false';
    chip.dataset.path = path;
    chip.dataset.type = type;
    chip.textContent = type === 'directory' ? `📁 ${name}` : `📄 ${name}`;
    insertNodeAtCursor(editor, chip);
    insertTextAfterNode(chip, '\u00A0');
    setEditorValue(getEditorText());
  }, [insertNodeAtCursor, insertTextAfterNode, getEditorText]);

  const pendingChatFile = useNavStore((s) => s.pendingChatFile);

  // Consume "Add to Chat" requests while this editor owns them: images go through the
  // upload pipeline (inline chip at cursor); other files become a file-reference chip.
  useEffect(() => {
    if (!pendingChatFile) return;
    if (useNavStore.getState().activeChatEditor !== 'inline') return;
    const pending = useNavStore.getState().consumePendingChatFile();
    if (!pending) return;
    const { path, type } = pending;
    const name = path.split('/').pop() || path;
    if (type === 'directory') {
      restoreEditorCursor();
      insertMentionChipAtCursor(name, path, 'directory');
      return;
    }
    const ext = name.includes('.') ? name.split('.').pop()!.toLowerCase() : '';
    if (IMAGE_EXTS.has(ext) && visionSupported) {
      const projectId = useProjectStore.getState().currentProject?.id || '';
      restoreEditorCursor();
      pathToFile(path, projectId)
        .then((file) => handleFilesWithChipsRef.current([file]))
        .catch((err) => {
          console.error('[InlineEditMessage] Failed to add file to chat:', err);
          setError(`Failed to attach ${name}`);
        });
    } else {
      restoreEditorCursor();
      insertMentionChipAtCursor(name, path, 'file');
    }
  }, [pendingChatFile, visionSupported, restoreEditorCursor, insertMentionChipAtCursor]);

  const handleAgentSelect = useCallback((_agentId: string) => {
    // Agent selection is managed in useAgentStore
  }, []);

  // Restore history immediately from the text protocol, without guessing its category.
  useEffect(() => {
    if (commandsLoadedRef.current) return;
    const editor = editorRef.current;
    if (!editor) return;
    setSelectedCommand(populateMessageEditor(editor, message.content));
    setEditorValue(getEditorText());
    commandsLoadedRef.current = true;
    editor.focus();
    const range = document.createRange();
    range.selectNodeContents(editor);
    range.collapse(false);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  }, [message.content, getEditorText]);

  // Handle Escape key to cancel edit or dismiss dialog
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (submittingRef.current) return;
        if (showConfirmDialog) {
          setShowConfirmDialog(false);
          setPendingRevertFiles([]);
          setIsSubmitting(false);
        } else {
          onCancel();
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onCancel, showConfirmDialog]);

  // --- Revert file calculation ---

  /** Compute files that will be reverted if this message is edited */
  const getRevertFiles = useCallback((): { path: string; additions: number }[] => {
    const fileMap = new Map<string, number>();
    for (let i = messageIndex + 1; i < messages.length; i++) {
      const msg = messages[i];
      const msgId = (msg as { messageId?: string }).messageId;
      if (!msgId) continue;
      const checkpoint = checkpointsByMessageId[msgId];
      if (!checkpoint) continue;
      for (const fc of checkpoint.filesChanged) {
        fileMap.set(fc.path, (fileMap.get(fc.path) || 0) + fc.additions);
      }
    }
    return Array.from(fileMap.entries())
      .map(([path, additions]) => ({ path, additions }))
      .sort((a, b) => a.path.localeCompare(b.path));
  }, [messageIndex, messages, checkpointsByMessageId]);

  // --- Submit flow (two-phase: validate → confirm dialog → execute) ---

  /** Shared execute logic: call edit-message API, truncate, send new message */
  const executeEdit = useCallback(async (messageText: string, messageId: string) => {
    const validationError = await validateCommand(parseCommand(messageText)?.command);
    if (validationError) throw new Error(validationError);
    if (attachments.some(isImageAttachment) && !visionSupported) {
      throw new Error(i18n('Current model does not support image input'));
    }
    // Validate and persist image uploads before deleting any history or rolling back files.
    const uploadedAttachments = await uploadPendingAttachments(sessionId!);
    const textDrafts = uploadedAttachments.filter((a) => !a.filePath && isTextAttachment(a));
    const finalMessage = buildMessageWithTextAttachments(resolveEmptyFileRefs(messageText, uploadedAttachments), textDrafts);
    const storedAttachments = uploadedAttachments.filter((a) => a.filePath);
    const chatAttachments = storedAttachments.map(toChatAttachment);

    if (!isCurrentContext()) throw new Error(i18n('Project or user changed. Please try again.'));
    // Call edit-message API (deletes messages + rolls back files)
    const editResult = await editMessage(sessionId!, messageId);

    // Truncate frontend messages from the edit index
    truncateMessagesFrom(messageIndex);

    // Set revert state if rollback occurred with actual file changes
    if (editResult.rollback && editResult.rollback.filesCount > 0) {
      setRevertState({
        messageId: '',
        additions: editResult.rollback.additions,
        deletions: editResult.rollback.deletions,
        filesCount: editResult.rollback.filesCount,
        timestamp: Date.now(),
      });
    }

    // Send new message content
    addMessage({
      role: 'user-with-attachments',
      content: finalMessage,
      attachments: storedAttachments.length > 0 ? storedAttachments : undefined,
      timestamp: Date.now(),
    });
    setStreaming(true);

    // Notify parent to clear editing state — the new user message will render as UserMessage
    unmountAllImageChips();
    onSubmit?.();

    const sid = sessionId!;
    let ownService: ChatService | null = null;
    // Drop events from a replaced stream: a stale 'done' would clear isStreaming and
    // reconcile a stale snapshot over this turn's optimistic user message.
    const isStaleStream = () => ownService !== null && getCurrentSendService() !== ownService;
    ownService = sendMessageToBackend({
      message: finalMessage,
      sessionId: sid,
      agentId: selectedAgentId,
      modelId: currentModelId,
      projectId: currentProjectId,
      attachments: chatAttachments.length > 0 ? chatAttachments : undefined,
      onEvent: (event) => {
        if (isStaleStream()) return;
        if (event.type === 'session_context') adoptHandshakeWorkspace(event);
        handleEvent(event);
      },
      onDone: (event) => {
        if (isStaleStream()) return;
        handleEvent(event);
        // Full reconciliation after stream ends: recover any SSE events that may
        // have been lost (e.g., compaction indicators).
        Promise.all([
          sessionService.getSessionDetail(sid),
          getCheckpoints(sid).catch(() => [] as CheckpointInfo[]),
        ]).then(([detail, checkpoints]) => {
          useChatStore.getState().loadSessionMessages(
            detail.messages, detail.pendingPermission, checkpoints, detail.endReason, detail.variables, detail.modelContextLength, sid
          );
        }).catch(() => { /* best-effort */ });
      },
      onError: (event) => { if (!isStaleStream()) handleEvent(event); },
    });
    setAttachments([]);
  }, [sessionId, messageIndex, truncateMessagesFrom, setRevertState, addMessage, setStreaming, handleEvent, selectedAgentId, currentModelId, currentProjectId, onSubmit, attachments, setAttachments, uploadPendingAttachments, visionSupported, validateCommand, isCurrentContext]);

  /** Phase 1: Validate and show confirm dialog if files will be reverted */
  const handleSubmit = useCallback(async () => {
    const editorText = getEditorText().trim();
    const command = editorRef.current?.querySelector('.command-chip') ? selectedCommand : null;
    const messageText = serializeCommand(command, editorText);

    if (!messageText || isSubmitting || submittingRef.current || isProcessingFiles() || showConfirmDialog || isAwaitingPermission() || !sessionId) return;
    const messageId = (message as { messageId?: string }).messageId;
    if (!messageId) return;

    if (isModelLoading) {
      setError(i18n('Please wait for models to load'));
      return;
    }
    if (!currentModelId) {
      setError(i18n('Please configure a model before sending messages'));
      return;
    }

    submittingRef.current = true;
    setIsSubmitting(true);
    setError(null);

    try {
      // 1. Check if there are files that will be reverted
      const revertFiles = getRevertFiles();
      if (revertFiles.length > 0) {
        // Show confirm dialog instead of proceeding
        setPendingRevertFiles(revertFiles);
        setShowConfirmDialog(true);
        setIsSubmitting(false);
        return;
      }

      // 2. No files to revert — proceed directly
      await executeEdit(messageText, messageId);
    } catch (err) {
      console.error('Failed to edit message:', err);
      setStreaming(false);
      setError(`Edit failed: ${(err as Error).message}`);
    } finally {
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  }, [editorValue, isSubmitting, sessionId, message, isModelLoading, currentModelId, selectedCommand, getEditorText, getRevertFiles, executeEdit, isProcessingFiles, showConfirmDialog, isAwaitingPermission, setStreaming]);

  /** Phase 2: User confirmed — execute the actual edit */
  const handleConfirmEdit = useCallback(async () => {
    const editorText = getEditorText().trim();
    const command = editorRef.current?.querySelector('.command-chip') ? selectedCommand : null;
    const messageText = serializeCommand(command, editorText);
    const messageId = (message as { messageId?: string }).messageId;
    if (!messageText || !messageId || !sessionId || submittingRef.current || isProcessingFiles() || isAwaitingPermission()) return;

    if (isModelLoading) {
      setError(i18n('Please wait for models to load'));
      setShowConfirmDialog(false);
      setPendingRevertFiles([]);
      return;
    }
    if (!currentModelId) {
      setError(i18n('Please configure a model before sending messages'));
      setShowConfirmDialog(false);
      setPendingRevertFiles([]);
      return;
    }

    setShowConfirmDialog(false);
    setPendingRevertFiles([]);
    submittingRef.current = true;
    setIsSubmitting(true);
    setError(null);

    try {
      await executeEdit(messageText, messageId);
    } catch (err) {
      console.error('Failed to edit message:', err);
      setStreaming(false);
      setError(`Edit failed: ${(err as Error).message}`);
    } finally {
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  }, [sessionId, message, selectedCommand, getEditorText, executeEdit, isModelLoading, currentModelId, isProcessingFiles, isAwaitingPermission, setStreaming]);

  const handleCancelDialog = useCallback(() => {
    setShowConfirmDialog(false);
    setPendingRevertFiles([]);
    setIsSubmitting(false);
  }, []);

  // --- Editor event handlers ---

  const handleEditorInput = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) return;
    const chip = editor.querySelector<HTMLElement>('.command-chip');
    let command = chip ? (serializeCommand(selectedCommand, '') === chip.dataset.commandToken
      ? selectedCommand : parseCommand(chip.dataset.commandToken ?? '')?.command ?? null) : null;
    if (!chip && parseCommand(getEditorText())?.command.skillName) {
      command = populateMessageEditor(editor, getEditorText());
      const range = document.createRange();
      range.selectNodeContents(editor);
      range.collapse(false);
      const selection = window.getSelection();
      selection?.removeAllRanges();
      selection?.addRange(range);
    }
    setSelectedCommand(command);
    const text = getEditorText();
    setEditorValue(text);
    slashCommand.onInput(text, command !== null);
    mention.onInput(text, getCursorPosition());
  }, [slashCommand, selectedCommand, getEditorText, getCursorPosition, mention]);

  const handleEditorKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const target = e.currentTarget;
    if ('isComposing' in target && target.isComposing || e.key === 'Process') return;

    // Intercept keys when mention popover is open
    if (mention.isOpen && mention.items.length > 0) {
      if (mention.onKeyDown(e)) return;
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
        e.preventDefault();
        const item = mention.items[mention.selectedIndex];
        if (item) insertMentionChip(item);
        return;
      }
      // Prevent Enter from sending while mention is open
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        return;
      }
    }

    // Intercept keys when slash command popover is open
    if (!mention.isOpen && slashCommand.isOpen && slashCommand.filtered.length > 0) {
      if (e.key === 'ArrowUp' || e.key === 'ArrowDown' || e.key === 'Escape') {
        slashCommand.onKeyDown(e);
        return;
      }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
        e.preventDefault();
        const cmd = slashCommand.filtered[slashCommand.selectedIndex];
        if (cmd) handleCommandSelect(cmd);
        return;
      }
    }

    // Handle backspace to delete command chip as a whole unit
    if (e.key === 'Backspace' && selectedCommand) {
      const chip = editorRef.current?.querySelector('.command-chip');
      if (chip) {
        const sel = window.getSelection();
        if (sel && sel.rangeCount > 0) {
          const range = sel.getRangeAt(0);
          if (range.collapsed) {
            const prevSibling = range.startContainer === editorRef.current
              ? range.startContainer.childNodes[range.startOffset - 1]
              : range.startContainer.previousSibling;
            if (prevSibling === chip || (range.startContainer === chip.nextSibling && range.startOffset === 0)) {
              e.preventDefault();
              setSelectedCommand(null);
              removeChip();
              setEditorValue(getEditorText());
              return;
            }
          }
        }
      }
    }

    // Handle backspace to delete image chip as a whole unit
    if (e.key === 'Backspace') {
      const editor = editorRef.current;
      if (editor) {
        const sel = window.getSelection();
        if (sel && sel.rangeCount > 0) {
          const range = sel.getRangeAt(0);
          if (range.collapsed) {
            const prevSibling = range.startContainer === editor
              ? editor.childNodes[range.startOffset - 1]
              : range.startContainer.previousSibling;
            if (prevSibling && (prevSibling as HTMLElement).classList?.contains('image-chip')) {
              e.preventDefault();
              const imgChip = prevSibling as HTMLElement;
              const attId = imgChip.dataset.attachmentId;
              unmountImageChip(imgChip);
              imgChip.remove();
              if (attId) removeAttachment(attId);
              if (editor.childNodes.length === 0) {
                editor.appendChild(document.createTextNode(''));
              }
              setEditorValue(getEditorText());
              return;
            }
          }
        }
      }
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (!isSubmitting && (selectedCommand || editorValue.trim())) {
        handleSubmit();
      }
    }
  };

  const handleEditorPaste = (e: React.ClipboardEvent) => {
    if (submittingRef.current || showConfirmDialog) {
      e.preventDefault();
      return;
    }
    // Check for image files first
    const imageFiles = getImageFilesFromPaste(e);
    if (imageFiles.length > 0) {
      e.preventDefault();
      if (!visionSupported) {
        setError(i18n('Current model does not support image input'));
        return;
      }
      handleFilesWithChips(imageFiles);
      return;
    }

    // For text paste, insert plain text only (strip HTML)
    e.preventDefault();
    const text = e.clipboardData?.getData('text/plain') || '';
    document.execCommand('insertText', false, text);
    handleEditorInput();
  };

  const handleClipboard = (e: React.ClipboardEvent<HTMLDivElement>, cut = false) => {
    if (cut && (submittingRef.current || showConfirmDialog)) { e.preventDefault(); return; }
    if (copyMessageSelection(e.currentTarget, e.clipboardData, cut)) {
      e.preventDefault();
      if (cut) handleEditorInput();
    }
  };

  const handleEditorClick = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) return;
    const chip = editor.querySelector('.command-chip');
    if (chip && editor.childNodes.length > 0) {
      const sel = window.getSelection();
      if (sel && sel.rangeCount > 0) {
        const range = sel.getRangeAt(0);
        if (range.startContainer === chip || range.startContainer === editor && range.startOffset === 0) {
          const newRange = document.createRange();
          newRange.setStartAfter(chip);
          newRange.collapse(true);
          sel.removeAllRanges();
          sel.addRange(newRange);
        }
      }
    }
  }, []);

  const hasContent = selectedCommand || editorValue.trim();

  return (
    <div className="flex justify-end mx-4">
      <div className="w-full max-w-[80%] flex flex-col gap-2">
        {(error || commandError) && (
          <div role="alert" className="text-xs text-destructive bg-destructive/10 rounded px-2 py-1">
            {error || commandError}
          </div>
        )}

        {/* Slash command popover (mutually exclusive with mention popover) */}
        {!mention.isOpen && slashCommand.isOpen && slashCommand.filtered.length > 0 && (
          <div className="relative">
            <SlashCommandPopover
              commands={slashCommand.filtered}
              selectedIndex={slashCommand.selectedIndex}
              onSelect={handleCommandSelect}
              onClose={slashCommand.close}
            />
          </div>
        )}

        {/* Resource mention popover (mutually exclusive with slash command popover) */}
        {mention.isOpen && !slashCommand.isOpen && (
          <div className="relative">
            <ResourceMentionPopover
              viewMode={mention.viewMode}
              items={mention.items}
              selectedIndex={mention.selectedIndex}
              hoveredItem={mention.hoveredItem}
              currentPath={mention.currentPath}
              query={mention.query}
              onSelect={insertMentionChip}
              onEnterView={mention.enterView}
              onExitView={mention.exitView}
              onHover={mention.setHoveredItem}
              onClose={mention.close}
            />
          </div>
        )}

        {/* Attachment previews */}
        <AttachmentPreviewBar attachments={attachments.filter((a) => !isImageAttachment(a))} onRemove={removeAttachment} disabled={isSubmitting || showConfirmDialog} showImageThumbnails />

        <div
          ref={editorRef}
          onInput={handleEditorInput}
          onKeyDown={handleEditorKeyDown}
          onPaste={handleEditorPaste}
          onCopy={(e) => handleClipboard(e)}
          onCut={(e) => handleClipboard(e, true)}
          onClick={handleEditorClick}
          onFocus={handleEditorFocus}
          data-placeholder={i18n('Plan, @ for context, / for commands')}
          className="message-editor w-full px-3 py-2 rounded-xl border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring overflow-y-auto whitespace-pre-wrap break-words"
          style={{ minHeight: '3em', maxHeight: '12em' }}
          contentEditable={!isSubmitting && !showConfirmDialog && !isAwaitingPermission()}
        />
        <input
          type="file"
          ref={fileInputRef}
          className="hidden"
          multiple
          accept="image/*,.txt,.md,.json,.xml,.html,.css,.js,.ts,.jsx,.tsx,.py,.java,.kt,.go,.rs,.rb,.sh,.sql,.toml,.ini,.cfg,.yml,.yaml,.csv"
          onChange={(e) => {
            if (e.target.files && !submittingRef.current && !showConfirmDialog) {
              handleFilesWithChips(Array.from(e.target.files));
              e.target.value = '';
            }
          }}
        />
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <button
              onClick={() => fileInputRef.current?.click()}
              className={`p-1.5 transition-colors ${visionSupported ? 'text-muted-foreground hover:text-foreground' : 'text-muted-foreground/70'}`}
              title={visionSupported ? 'Attach files' : i18n('Images not supported; text files only')}
              disabled={isSubmitting || processingFiles || showConfirmDialog}
            >
              <Paperclip className="w-4 h-4" />
            </button>
            <AgentSelector onSelect={handleAgentSelect} />
            <ModelSelector onModelChange={handleModelChange} />
          </div>
          <div className="flex items-center gap-1.5">
            <button
              className="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              onClick={onCancel}
              disabled={isSubmitting}
              title={i18n('Cancel')}
            >
              <X className="w-4 h-4" />
            </button>
            {isSubmitting ? (
              <button
                className="p-1.5 rounded-md bg-primary text-primary-foreground opacity-50 cursor-default"
                disabled
                title={i18n('Submitting...')}
              >
                <Send className="w-4 h-4" />
              </button>
            ) : (
              <button
                className="p-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                disabled={!hasContent || processingFiles || showConfirmDialog || isModelLoading || isAwaitingPermission()}
                onClick={handleSubmit}
                title={i18n('Submit')}
              >
                <Send className="w-4 h-4" />
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Revert confirmation dialog */}
      {showConfirmDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={handleCancelDialog}>
          <div className="w-full max-w-md mx-4 rounded-xl border border-border bg-card shadow-xl" onClick={(e) => e.stopPropagation()}>
            <div className="p-5">
              <p className="text-sm text-foreground leading-relaxed">
                This will roll back to the state before this point and discard all file changes from this turn and later.
              </p>
            </div>
            <div className="px-5 pb-4">
              <div className="rounded-lg border border-border bg-muted/30 divide-y divide-border">
                {pendingRevertFiles.map((file) => (
                  <div key={file.path} className="flex items-center justify-between px-3 py-2.5">
                    <div className="flex items-center gap-2 min-w-0">
                      <Bot className="w-4 h-4 text-muted-foreground shrink-0" />
                      <span className="text-sm font-mono truncate" title={file.path}>
                        {file.path.split('/').pop()}
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5 shrink-0 ml-3">
                      <span className="text-sm text-green-500 font-mono">+{file.additions}</span>
                      <span className="text-xs text-muted-foreground">Will revert</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
            <div className="flex items-center justify-end gap-2 px-5 pb-5">
              <button
                className="px-4 py-2 text-sm font-medium rounded-md bg-muted text-muted-foreground hover:bg-muted/80 hover:text-foreground transition-colors"
                onClick={handleCancelDialog}
              >
                Cancel
              </button>
              <button
                className="px-4 py-2 text-sm font-medium rounded-md bg-foreground text-background hover:bg-foreground/90 transition-colors"
                onClick={handleConfirmEdit}
                disabled={isSubmitting || processingFiles}
              >
                Continue
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
