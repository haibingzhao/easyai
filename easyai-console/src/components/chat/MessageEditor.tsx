import React, { useState, useRef, useCallback, useEffect } from 'react';
import { Send, Square, Loader2, Paperclip, ShieldCheck, Clock } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { useAgentStore } from '@/services/stores/agent-store';
import { useProjectStore } from '@/services/stores/project-store';
import { ChatService, sendMessageToBackend, abortAllActiveStreams, cancelChat } from '../../services/chat-service';
import { SessionService, sessionService } from '../../services/session-service';
import { isImageAttachment, isTextAttachment, toChatAttachment, buildMessageWithTextAttachments, buildFileRef, buildFolderRef } from '../../utils/attachment-utils';
import { useMention } from '@/hooks/useMention';
import type { MentionItem } from '@/hooks/useMention';
import { ResourceMentionPopover } from '@/components/chat/ResourceMentionPopover';
import { ModelSelector } from './ModelSelector';
import { AgentSelector } from './AgentSelector';
import { AutoApprovePanel } from './AutoApprovePanel';
import { SlashCommandPopover } from './SlashCommandPopover';
import { useSlashCommand } from '@/hooks/useSlashCommand';
import { useAttachmentManager } from '@/hooks/useAttachmentManager';
import { AttachmentPreviewBar } from './AttachmentPreviewBar';
import type { SlashCommand } from '@/types/command';
import type { CommandIdentity } from '@/utils/command-utils';
import { parseCommand, serializeCommand } from '@/utils/command-utils';
import { createCommandChip, populateMessageEditor, readMessageEditorText, copyMessageSelection } from '@/utils/attachment-utils';
import type { Attachment, QueuedMessage } from '../../types/message';
import type { ModelCapabilities } from '@/types/settings';
import { i18n } from '../../utils/i18n';
import { addQueueMessage, removeQueueMessage } from '../../services/chat-service';
import { QueuedMessagesPanel } from './QueuedMessagesPanel';
import { getCheckpoints } from '@/services/checkpoint-service';
import type { CheckpointInfo } from '@/types/checkpoint';
import type { ErrorEvent } from '@/types/socket-event';

export const MessageEditor: React.FC = () => {
  const [editorValue, setEditorValue] = useState('');
  const [selectedCommand, setSelectedCommand] = useState<CommandIdentity | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const editorRef = useRef<HTMLDivElement>(null);
  const [currentModelId, setCurrentModelId] = useState('');
  const [currentCapabilities, setCurrentCapabilities] = useState<ModelCapabilities | undefined>();
  const [isModelLoading, setIsModelLoading] = useState(true);
  const [showAutoApprove, setShowAutoApprove] = useState(false);
  const toolbarRowRef = useRef<HTMLDivElement>(null);
  const [toolbarHeight, setToolbarHeight] = useState(0);
  // Tracks whether the user has dismissed the green clock to reveal the Stop button
  const [clockDismissed, setClockDismissed] = useState(false);

  useEffect(() => {
    if (toolbarRowRef.current) {
      setToolbarHeight(toolbarRowRef.current.offsetHeight);
    }
  }, []);

  const {
    sessionId,
    isStreaming,
    setStreaming,
    addMessage,
    handleEvent,
    setSessionId,
    setCancelReason,
    commitStreamingMessage,
    isAwaitingAskQuestion,
    isAwaitingPermission,
    addQueuedMessage,
    queuedMessages,
  } = useChatStore((state) => ({
    sessionId: state.sessionId,
    isStreaming: state.isStreaming,
    setStreaming: state.setStreaming,
    addMessage: state.addMessage,
    handleEvent: state.handleEvent,
    setSessionId: state.setSessionId,
    setCancelReason: state.setCancelReason,
    commitStreamingMessage: state.commitStreamingMessage,
    isAwaitingAskQuestion: state.isAwaitingAskQuestion,
    isAwaitingPermission: state.isAwaitingPermission,
    addQueuedMessage: state.addQueuedMessage,
    queuedMessages: state.queuedMessages,
  }));

  // Read selected agent from global agent store
  const selectedAgentId = useAgentStore((state) => state.selectedAgentId);
  const currentProjectId = useProjectStore((state) => state.currentProject?.id);

  // Slash command autocomplete (must be after selectedAgentId is defined)
  const slashCommand = useSlashCommand(selectedAgentId, currentProjectId);
  const { validateCommand, isCurrentContext } = slashCommand;
  const commandError = slashCommand.selectionError(selectedCommand);

  // @ mention autocomplete
  const mention = useMention();

  const chatServiceRef = useRef<ChatService | null>(null);

  // --- Chip DOM management ---

  const insertChip = useCallback((cmd: SlashCommand) => {
    const editor = editorRef.current;
    if (!editor) return;

    // Create chip element
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
      // Ensure editor is not completely empty (browser needs a text node)
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
      // Remove text nodes (keep only the chip)
      const chip = editor.querySelector('.command-chip');
      Array.from(editor.childNodes).forEach((node) => {
        if (node !== chip) node.remove();
      });
      // Ensure a text node exists after chip for cursor placement
      editor.appendChild(document.createTextNode(''));
    }
    setEditorValue('');
    slashCommand.close();
    editorRef.current?.focus();
  }, [insertChip, slashCommand]);

  // --- Mention DOM helpers ---

  /** Get current cursor text offset within the editor, using the same coordinate system as getEditorText.
   *  Skips command chips, counts mention chips as their encoded ref length, counts text nodes normally. */
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

      // Cursor is inside a text node: count only text before cursor offset
      if (node === range.startContainer && node.nodeType === Node.TEXT_NODE) {
        const textBefore = (node.textContent || '').slice(0, range.startOffset);
        offset += textBefore.length;
        foundCursor = true;
        return;
      }

      // Cursor is inside an element node: walk children up to startOffset
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

      // Non-cursor nodes: count according to type (matching getEditorText)
      if (node.nodeType === Node.ELEMENT_NODE) {
        const el = node as HTMLElement;
        if (el.classList.contains('command-chip')) return; // skip command chip
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

  /** Remove the @ trigger text from the last @ before cursor to the current cursor position */
  const removeAtTriggerText = useCallback((editor: HTMLElement) => {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return;

    const range = sel.getRangeAt(0);
    const preCaretRange = document.createRange();
    preCaretRange.selectNodeContents(editor);
    preCaretRange.setEnd(range.startContainer, range.startOffset);

    // Find @ in raw DOM text before cursor
    const textBefore = preCaretRange.toString();
    const atIdx = textBefore.lastIndexOf('@');
    if (atIdx === -1) return;

    // Delete from @ to cursor
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
    // Place cursor at @ position
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

  // --- Send handler ---

  const handleModelChange = useCallback((configId: string, capabilities?: ModelCapabilities) => {
    setCurrentModelId(configId);
    setCurrentCapabilities(capabilities);
    setIsModelLoading(false);
  }, []);

  const visionSupported = currentCapabilities?.vision === true;

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
  } = useAttachmentManager({ visionSupported, sessionId, onError: (msg) => addMessage({ role: 'error', content: msg, timestamp: Date.now() }) });

  const handleAgentSelect = useCallback((_agentId: string) => {
    // Agent selection is managed in useAgentStore, this callback is for UI feedback
  }, []);

  const handleSend = useCallback(async () => {
    const editorText = getEditorText().trim();
    const command = editorRef.current?.querySelector('.command-chip') ? selectedCommand : null;
    const messageText = serializeCommand(command, editorText);

    if (!messageText || isStreaming || submittingRef.current || isProcessingFiles() || isAwaitingAskQuestion() || isAwaitingPermission()) return;
    // Prevent sending while model configs are still loading
    if (isModelLoading) {
      addMessage({
        role: 'error',
        content: i18n('Please wait for models to load'),
        timestamp: Date.now(),
      });
      return;
    }

    if (!currentModelId) {
      addMessage({
        role: 'error',
        content: i18n('Please configure a model before sending messages'),
        timestamp: Date.now(),
      });
      return;
    }

    const currentSessionId = sessionId;

    // Classify attachments for validation: images and text files are supported
    const unsupported = attachments.filter((a) => !a.filePath && !isImageAttachment(a) && !isTextAttachment(a));
    if (unsupported.length > 0) {
      addMessage({
        role: 'error',
        content: `Unsupported file types: ${unsupported.map((a) => a.name).join(', ')}. Only images and text files are supported.`,
        timestamp: Date.now(),
      });
      return;
    }

    if (attachments.some(isImageAttachment) && !visionSupported) {
      addMessage({ role: 'error', content: i18n('Current model does not support image input'), timestamp: Date.now() });
      return;
    }

    submittingRef.current = true;
    setIsSubmitting(true);
    let releasedForStreaming = false;
    try {
      const validationError = await validateCommand(command ?? parseCommand(messageText)?.command);
      if (validationError) {
        addMessage({ role: 'error', content: validationError, timestamp: Date.now() });
        return;
      }
      let sid = currentSessionId;
      let uploadedAttachments: Attachment[];
      try {
        // A new session is required before uploading clipboard drafts.
        if (!sid) {
          const sessionService = new SessionService();
          sid = await sessionService.createSession();
          setSessionId(sid);
        }
        uploadedAttachments = await uploadPendingAttachments(sid);
      } catch (error) {
        addMessage({ role: 'error', content: (error as Error).message, timestamp: Date.now() });
        return;
      }

      if (!isCurrentContext()) {
        addMessage({ role: 'error', content: i18n('Project or user changed. Please try again.'), timestamp: Date.now() });
        return;
      }
      const textDrafts = uploadedAttachments.filter((a) => !a.filePath && isTextAttachment(a));
      const finalMessage = buildMessageWithTextAttachments(messageText, textDrafts);
      const storedAttachments = uploadedAttachments.filter((a) => a.filePath);
      const chatAttachments = storedAttachments.map(toChatAttachment);
      try {
        addMessage({
          role: 'user-with-attachments',
          content: finalMessage,
          attachments: storedAttachments.length > 0 ? storedAttachments : undefined,
          timestamp: Date.now(),
        });

        // Only clear the draft once every attachment is ready.
        if (editorRef.current) editorRef.current.innerHTML = '';
        setSelectedCommand(null);
        setEditorValue('');
        setAttachments([]);
        setStreaming(true);
        // SSE lasts for the whole response; allow composing follow-ups once uploads are done.
        releasedForStreaming = true;
        submittingRef.current = false;
        setIsSubmitting(false);

        chatServiceRef.current = await sendMessageToBackend({
          message: finalMessage,
          sessionId: sid,
          agentId: selectedAgentId,
          modelId: currentModelId,
          projectId: currentProjectId,
          attachments: chatAttachments.length > 0 ? chatAttachments : undefined,
          onEvent: handleEvent,
          onDone: (event) => {
            handleEvent(event);
            // Full reconciliation after stream ends: recover any SSE events that may
            // have been lost (e.g., compaction indicators). Mirrors the watch path's
            // finalReconciliation in ChatPanel.
            if (sid) {
              Promise.all([
                sessionService.getSessionDetail(sid),
                getCheckpoints(sid).catch(() => [] as CheckpointInfo[]),
              ]).then(([detail, checkpoints]) => {
                useChatStore.getState().loadSessionMessages(
                  detail.messages, detail.pendingPermission, checkpoints, detail.endReason, detail.variables, detail.modelContextLength
                );
              }).catch(() => { /* best-effort */ });
            }
          },
          onError: handleEvent as unknown as (event: ErrorEvent) => void,
        });
      } catch (error) {
        console.error('Failed to send message:', error);
        // Defensive: commit any partial streaming content to preserve it.
        // The store's error handler may have already committed, but this is safe
        // (commitStreamingMessage is a no-op when streamingBlocks is empty).
        commitStreamingMessage();
        // Abort any active SSE streams (retry, resume, question, permission)
        abortAllActiveStreams();
        setStreaming(false);
        addMessage({
          role: 'error',
          content: `发送失败: ${(error as Error).message}`,
          timestamp: Date.now(),
        });
      }
    } finally {
      if (!releasedForStreaming) {
        submittingRef.current = false;
        setIsSubmitting(false);
      }
    }
  }, [editorValue, isStreaming, sessionId, attachments, addMessage, setStreaming, handleEvent, setSessionId, currentModelId, selectedAgentId, currentProjectId, isModelLoading, isAwaitingAskQuestion, isAwaitingPermission, selectedCommand, isProcessingFiles, uploadPendingAttachments, setAttachments, visionSupported, commitStreamingMessage, validateCommand, isCurrentContext]);

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

  // --- FollowUp send handler (clock button during streaming) ---
  const handleFollowUpSend = useCallback(async () => {
    const editorText = getEditorText().trim();
    const command = editorRef.current?.querySelector('.command-chip') ? selectedCommand : null;
    const messageText = serializeCommand(command, editorText);
    if (!messageText || !sessionId || submittingRef.current || isProcessingFiles()) return;

    // Classify attachments for validation
    const unsupported = attachments.filter((a) => !a.filePath && !isImageAttachment(a) && !isTextAttachment(a));
    if (unsupported.length > 0) {
      addMessage({
        role: 'error',
        content: `Unsupported file types: ${unsupported.map((a) => a.name).join(', ')}. Only images and text files are supported.`,
        timestamp: Date.now(),
      });
      return;
    }

    if (attachments.some(isImageAttachment) && !visionSupported) {
      addMessage({ role: 'error', content: i18n('Current model does not support image input'), timestamp: Date.now() });
      return;
    }

    submittingRef.current = true;
    setIsSubmitting(true);
    try {
      const validationError = await validateCommand(command ?? parseCommand(messageText)?.command);
      if (validationError) {
        addMessage({ role: 'error', content: validationError, timestamp: Date.now() });
        return;
      }
      let uploadedAttachments: Attachment[];
      try {
        uploadedAttachments = await uploadPendingAttachments(sessionId);
      } catch (error) {
        addMessage({ role: 'error', content: (error as Error).message, timestamp: Date.now() });
        return;
      }
      if (!isCurrentContext()) {
        addMessage({ role: 'error', content: i18n('Project or user changed. Please try again.'), timestamp: Date.now() });
        return;
      }
      const textDrafts = uploadedAttachments.filter((a) => !a.filePath && isTextAttachment(a));
      const finalMessage = buildMessageWithTextAttachments(messageText, textDrafts);
      const storedAttachments = uploadedAttachments.filter((a) => a.filePath);
      const chatAttachments = storedAttachments.map(toChatAttachment);

      // Add to local queue — store finalMessage to match backend UserMessageAddedEvent.content
      const localMsg: QueuedMessage = {
        id: `queued-${Date.now()}-${Math.random().toString(36).slice(2)}`,
        content: finalMessage,
        commandCategory: command?.category,
        type: 'followUp',
        status: 'syncing',
        attachments: storedAttachments.length > 0 ? storedAttachments : undefined,
      };
      addQueuedMessage(localMsg);

      // Clear editor and attachments
      if (editorRef.current) editorRef.current.innerHTML = '';
      setSelectedCommand(null);
      setEditorValue('');
      setAttachments([]);

      // Sync to backend
      try {
        const resp = await addQueueMessage(sessionId, finalMessage, 'followUp', chatAttachments.length > 0 ? chatAttachments : undefined);
        // Update local message with backend ID
        useChatStore.setState((state) => ({
          queuedMessages: state.queuedMessages.map((m) =>
            m.id === localMsg.id ? { ...m, backendQueueId: resp.id, status: 'synced' as const } : m
          ),
        }));
      } catch (error) {
        console.error('Failed to queue followUp message:', error);
        // Mark message as failed so user can see the sync issue
        useChatStore.setState((state) => ({
          queuedMessages: state.queuedMessages.map((m) =>
            m.id === localMsg.id ? { ...m, status: 'failed' as const } : m
          ),
        }));
      }
    } finally {
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  }, [sessionId, addQueuedMessage, getEditorText, selectedCommand, attachments, setAttachments, addMessage, isProcessingFiles, uploadPendingAttachments, visionSupported, validateCommand, isCurrentContext]);

  // --- Editor event handlers ---

  const handleEditorInput = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) return;
    const chip = editor.querySelector<HTMLElement>('.command-chip');
    let command = chip ? (serializeCommand(selectedCommand, '') === chip.dataset.commandToken
      ? selectedCommand : parseCommand(chip.dataset.commandToken ?? '')?.command ?? null) : null;
    if (!chip && parseCommand(getEditorText())?.command.source) {
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
          // Check if cursor is right after the chip (or at the very start with no selection)
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

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      const text = editorRef.current?.textContent?.trim() || '';
      const hasContent = selectedCommand || text;
      if (!isStreaming && !isAwaitingAskQuestion() && !isAwaitingPermission() && !isModelLoading && hasContent) {
        handleSend();
      } else if (isStreaming && text && sessionId) {
        // During streaming, Enter queues a followUp message
        handleFollowUpSend();
      }
    }
  };

  const handleEditorPaste = (e: React.ClipboardEvent) => {
    if (submittingRef.current) {
      e.preventDefault();
      return;
    }
    // Check for image files first
    const imageFiles = getImageFilesFromPaste(e);
    if (imageFiles.length > 0) {
      e.preventDefault();
      if (!visionSupported) {
        addMessage({
          role: 'error',
          content: i18n('Current model does not support image input'),
          timestamp: Date.now(),
        });
        return;
      }
      handleFiles(imageFiles);
      return;
    }

    // For text paste, insert plain text only (strip HTML)
    e.preventDefault();
    const text = e.clipboardData?.getData('text/plain') || '';
    document.execCommand('insertText', false, text);
    handleEditorInput();
  };

  const handleClipboard = (e: React.ClipboardEvent<HTMLDivElement>, cut = false) => {
    if (cut && submittingRef.current) { e.preventDefault(); return; }
    if (copyMessageSelection(e.currentTarget, e.clipboardData, cut)) {
      e.preventDefault();
      if (cut) handleEditorInput();
    }
  };

  const handleEditorClick = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) return;
    // If user clicks on or near the chip, place cursor after it
    const chip = editor.querySelector('.command-chip');
    if (chip && editor.childNodes.length > 0) {
      const sel = window.getSelection();
      if (sel && sel.rangeCount > 0) {
        const range = sel.getRangeAt(0);
        // If cursor is before the chip or on the chip, move after it
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
  // During streaming, the button becomes a clock icon when there's text input (for followUp queue)
  const hasQueuedInput = isStreaming && editorValue.trim().length > 0;
  // Whether there are pending queued messages (followUp/steer)
  const hasQueuedMessages = queuedMessages.length > 0;

  // Reset clockDismissed when editor gets new text or streaming ends
  useEffect(() => {
    if (hasQueuedInput || !isStreaming) {
      setClockDismissed(false);
    }
  }, [hasQueuedInput, isStreaming]);

  // Handle green clock click:
  // - If there are pending queued messages: cancel them (clock stays visible for a second click)
  // - If no pending queued messages: reveal Stop button, hide clock
  const handleClockClick = useCallback(async () => {
    if (hasQueuedMessages && sessionId) {
      // First click with queued messages: cancel all queued messages
      const currentQueued = useChatStore.getState().queuedMessages;
      for (const msg of currentQueued) {
        if (msg.backendQueueId) {
          try {
            await removeQueueMessage(sessionId, msg.backendQueueId);
          } catch (e) {
            console.error('Failed to remove queued message:', e);
          }
        }
      }
      useChatStore.setState({ queuedMessages: [] });
      // Clock stays visible — next click will go to the else branch
    } else {
      // No queued messages (or second click after clearing): reveal Stop button
      setClockDismissed(true);
    }
  }, [hasQueuedMessages, sessionId]);

  // Button visibility logic during streaming:
  // - hasQueuedInput && !clockDismissed → show Clock, hide Stop
  // - hasQueuedInput && clockDismissed → show Stop, hide Clock
  // - !hasQueuedInput && !hasQueuedMessages → show Stop
  // - !hasQueuedInput && hasQueuedMessages → hide Stop (only QueuedMessagesPanel visible)
  const showStopButton = isStreaming && (
    (hasQueuedInput && clockDismissed) ||
    (!hasQueuedInput && !hasQueuedMessages)
  );
  const showClockButton = hasQueuedInput && !clockDismissed;

  return (
    <div className="flex flex-col gap-2 relative">
      {commandError && <div role="alert" className="text-xs text-destructive">{commandError}</div>}
      {/* Queued messages panel */}
      <QueuedMessagesPanel />

      {attachments.length > 0 && (
        <AttachmentPreviewBar attachments={attachments} onRemove={removeAttachment} disabled={isSubmitting} showImageThumbnails />
      )}

      {/* Slash command popover */}
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

      <div className="flex gap-2">
        <input
          type="file"
          ref={fileInputRef}
          className="hidden"
          multiple
          accept="image/*,.txt,.md,.json,.xml,.html,.css,.js,.ts,.jsx,.tsx,.py,.java,.kt,.go,.rs,.rb,.sh,.sql,.toml,.ini,.cfg,.yml,.yaml,.csv"
          onChange={(e) => {
            if (e.target.files && !submittingRef.current) {
              handleFiles(Array.from(e.target.files));
              e.target.value = '';
            }
          }}
        />

        <div
          ref={editorRef}
          onInput={handleEditorInput}
          onKeyDown={handleEditorKeyDown}
          onPaste={handleEditorPaste}
          onCopy={(e) => handleClipboard(e)}
          onCut={(e) => handleClipboard(e, true)}
          onClick={handleEditorClick}
          data-placeholder={i18n('Plan, @ for context, / for commands')}
          className="message-editor flex-1 w-full px-3 pt-0 pb-2 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring overflow-y-auto whitespace-pre-wrap break-words"
          style={{
            minHeight: 'calc(3 * 1.5em + 1rem)',
            maxHeight: 'calc(7 * 1.5em + 1rem)',
          }}
          contentEditable={!isSubmitting && !isAwaitingAskQuestion() && !isAwaitingPermission()}
        />
      </div>

      <div ref={toolbarRowRef} className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={isSubmitting || processingFiles}
            className="p-1.5 text-muted-foreground hover:text-foreground transition-colors"
            title={visionSupported ? 'Attach files' : i18n('Images not supported; text files only')}
          >
            <Paperclip className="w-4 h-4" />
          </button>
          <div className="relative">
            <button
              onMouseDown={(e) => e.stopPropagation()}
              onClick={() => setShowAutoApprove((prev) => !prev)}
              className={`p-1.5 transition-colors ${showAutoApprove ? 'text-primary' : 'text-muted-foreground hover:text-foreground'}`}
              title={i18n('自动审批')}
            >
              <ShieldCheck className="w-4 h-4" />
            </button>
          </div>
          <AgentSelector onSelect={handleAgentSelect} />
          <ModelSelector onModelChange={handleModelChange} />
        </div>
        <div className="flex items-center gap-0.5">
          {isStreaming ? (
            <>
              {showStopButton && (
                <button
                  className="p-1.5 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
                  title={i18n('Stop')}
                  onClick={() => {
                    commitStreamingMessage();
                    if (chatServiceRef.current) {
                      chatServiceRef.current.abort();
                    }
                    abortAllActiveStreams();
                    if (sessionId) {
                      cancelChat(sessionId);
                    }
                    setCancelReason('Manually Cancelled');
                    setStreaming(false);
                    // Clear queued messages — they will not be consumed after cancel
                    useChatStore.setState({ queuedMessages: [] });
                  }}
                >
                  <Square className="w-4 h-4" />
                </button>
              )}
              {showClockButton && (
                <button
                  className="p-1.5 rounded-md bg-emerald-600 text-white hover:bg-emerald-700 transition-colors"
                  title={hasQueuedMessages ? i18n('Cancel queued messages') : i18n('Show stop button')}
                  onClick={handleClockClick}
                >
                  <Clock className="w-4 h-4" />
                </button>
              )}
            </>
          ) : (
            <button
              className="p-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              disabled={!hasContent || processingFiles || isSubmitting || isAwaitingAskQuestion() || isAwaitingPermission() || isModelLoading}
              onClick={handleSend}
              title={isModelLoading ? i18n('Loading models...') : isAwaitingAskQuestion() ? i18n('Answer the question first') : i18n('Send')}
            >
              {processingFiles || isSubmitting ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <Send className="w-4 h-4" />
              )}
            </button>
          )}
        </div>
      </div>

      {/* AutoApprovePanel overlay - full width, bottom aligned with textarea */}
      {showAutoApprove && (
        <div
          className="absolute inset-x-0 z-50"
          style={{ bottom: `${toolbarHeight + 8}px` }}
        >
          <AutoApprovePanel onClose={() => setShowAutoApprove(false)} />
        </div>
      )}
    </div>
  );
};
