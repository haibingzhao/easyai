import type { Message, ToolCall, ToolResult, ToolResultContentBlock, ContextReferences, Attachment } from '@/types/message';
import type {
  MessageSnapshot,
  ToolResultContentBlock as ToolResultContentBlockData,
  CustomContentBlock,
} from '@/services/session-service';
import {
  type StreamingBlock,
  type ThinkingBlockData,
  type TextBlockData,
  type ToolBlockData,
  isTextBlock,
  isThinkingBlock,
  isToolCallBlock,
  isImageBlock,
  isFileRefBlock,
} from './types';
import { parseAllRefs } from '@/utils/attachment-utils';

/**
 * Convert sub-agent streaming blocks to Message[] for committed subAgentMessages.
 */
export function convertSubAgentBlocksToMessages(blocks: StreamingBlock[]): Message[] {
  const messages: Message[] = [];
  let currentText = '';
  let currentThinking = '';
  let currentToolCalls: ToolCall[] = [];
  let currentToolResults: ToolResult[] = [];
  let thinkingDurationMs = 0;

  const flush = () => {
    if (currentText || currentThinking || currentToolCalls.length > 0) {
      messages.push({
        role: 'assistant',
        content: currentText,
        thinking: currentThinking || undefined,
        toolCalls: currentToolCalls.length > 0 ? currentToolCalls : undefined,
        toolResults: currentToolResults.length > 0 ? currentToolResults : undefined,
        thinkingDurationMs: thinkingDurationMs > 0 ? thinkingDurationMs : undefined,
        timestamp: Date.now(),
      } as Extract<Message, { role: 'assistant' }>);
      currentText = '';
      currentThinking = '';
      currentToolCalls = [];
      currentToolResults = [];
      thinkingDurationMs = 0;
    }
  };

  for (const block of blocks) {
    if (block.type === 'text') {
      currentText += (block as TextBlockData).content;
    } else if (block.type === 'thinking') {
      currentThinking += (block as ThinkingBlockData).content;
      if ((block as ThinkingBlockData).durationMs) {
        thinkingDurationMs = (block as ThinkingBlockData).durationMs!;
      }
    } else if (block.type === 'tool') {
      const toolBlock = block as ToolBlockData;
      currentToolCalls.push({
        id: toolBlock.toolCall.id,
        toolName: toolBlock.toolCall.toolName,
        args: toolBlock.toolCall.args,
      });
      if (toolBlock.toolResult) {
        currentToolResults.push(toolBlock.toolResult);
      }
    }
  }
  flush();
  return messages;
}

/**
 * Convert a MessageSnapshot to a Message.
 * Shared helper used by both loadSessionMessages and sub-agent message reconstruction.
 */
export function convertSnapshot(msg: MessageSnapshot): Message {
  const role = msg.role.toLowerCase() as Message['role'];

  if (role === 'user' || role === 'user-with-attachments') {
    const textContent = msg.content.filter(isTextBlock).map(b => b.text).join('');
    const imageBlocks = msg.content.filter(isImageBlock);
    const fileRefBlocks = msg.content.filter(isFileRefBlock);
    // Parse all refs (files and folders) from text content
    const allRefs = parseAllRefs(textContent);

    // Build attachments from: legacy ImageContent (base64), FileRefContent blocks, and all text refs
    const allAttachments: Attachment[] = [];

    // Legacy base64 images
    imageBlocks.forEach((img, i) => {
      allAttachments.push({
        id: `hist-${msg.id ?? 'msg'}-img-${i}`,
        name: `image-${i + 1}.${img.mimeType.split('/')[1] || 'png'}`,
        mimeType: img.mimeType,
        data: img.data,
        size: Math.ceil(img.data.length * 3 / 4),
      });
    });

    // FileRefContent blocks (images or files stored on disk)
    fileRefBlocks.forEach((ref, i) => {
      allAttachments.push({
        id: `hist-${msg.id ?? 'msg'}-ref-${i}`,
        name: ref.name,
        mimeType: ref.mimeType,
        data: '',
        size: 0,
        filePath: ref.filePath,
      });
    });

    // All refs (files and folders) parsed from message text
    allRefs.forEach((ref, i) => {
      // Avoid duplicates with FileRefContent blocks
      if (!allAttachments.some(a => a.filePath === ref.path)) {
        allAttachments.push({
          id: `hist-${msg.id ?? 'msg'}-tref-${i}`,
          name: ref.name,
          mimeType: ref.type === 'folder' ? 'application/x-directory' : 'text/plain',
          data: '',
          size: 0,
          filePath: ref.path,
        });
      }
    });

    // Strip file ref markers from display text
    const displayContent = textContent;

    return {
      role: allAttachments.length > 0 ? 'user-with-attachments' : role,
      content: displayContent,
      timestamp: msg.timestamp,
      compactedAt: msg.compactedAt ?? undefined,
      metadata: msg.metadata ?? undefined,
      messageId: msg.id ?? undefined,
      attachments: allAttachments.length > 0 ? allAttachments : undefined,
    } as Message;
  }

  if (role === 'assistant') {
    const textContent = msg.content.filter(isTextBlock).map(b => b.text).join('');
    const thinkingContent = msg.content.filter(isThinkingBlock).map(b => b.thinking).join('');
    const toolCalls: ToolCall[] = msg.content.filter(isToolCallBlock).map(tc => ({
      id: tc.id, toolName: tc.name, args: tc.arguments,
    }));
    const thinkingBlock = msg.content.find(isThinkingBlock);
    const textBlock = msg.content.find(isTextBlock);
    // Convert backend ReferencesSnapshot to frontend ContextReferences
    const references: ContextReferences | undefined = msg.references ? {
      memories: msg.references.memories.map(m => ({
        name: m.name,
        description: m.description,
        type: m.type,
        scope: m.scope,
      })),
      rules: msg.references.rules.map(r => ({
        name: r.name,
        source: r.source,
      })),
    } : undefined;
    return {
      role: 'assistant',
      messageId: msg.id ?? undefined,
      content: textContent,
      thinking: thinkingContent || undefined,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
      stopReason: msg.stopReason ?? undefined,
      usage: msg.usage ? {
        inputTokens: msg.usage.inputTokens, outputTokens: msg.usage.outputTokens,
        totalTokens: msg.usage.totalTokens, durationMs: msg.usage.durationMs,
        cacheReadTokens: msg.usage.cacheReadTokens, cacheWriteTokens: msg.usage.cacheWriteTokens,
        modelName: msg.usage.modelName,
      } : undefined,
      thinkingDurationMs: thinkingBlock?.durationMs ?? undefined,
      textDurationMs: textBlock?.durationMs ?? undefined,
      timestamp: msg.timestamp,
      compactedAt: msg.compactedAt ?? undefined,
      references,
    } as Message;
  }

  if (role === 'tool') {
    const toolResults = msg.content.filter((block): block is ToolResultContentBlockData => block.type === 'toolResult');
    return {
      role: 'tool',
      toolResults: toolResults.map((tr) => {
        let contentBlocks: ToolResultContentBlock[] | undefined;
        try { const parsed = JSON.parse(tr.output); if (Array.isArray(parsed)) contentBlocks = parsed; } catch { /* ignore */ }
        return {
          id: tr.toolCallId, toolName: tr.toolName, result: tr.output, contentBlocks,
          isError: tr.isError ?? false, exitCode: tr.exitCode, durationMs: tr.durationMs,
          mimeType: tr.mimeType, truncated: tr.truncated, usage: tr.usage,
        };
      }),
      timestamp: msg.timestamp,
    } as Message;
  }

  if (role === 'custom') {
    const customBlock = msg.content.find((b): b is CustomContentBlock => b.type === 'custom');
    return { role: 'custom', customType: customBlock?.customType || '', metadata: customBlock?.metadata || {}, timestamp: msg.timestamp } as Message;
  }

  return { role: role, content: '', timestamp: msg.timestamp } as Message;
}
