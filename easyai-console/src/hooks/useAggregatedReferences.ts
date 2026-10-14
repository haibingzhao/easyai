import { useMemo } from 'react';
import { useChatStore } from '@/services/stores/chat-store';
import type { AssistantMessage, ContextReferences } from '@/types/message';

/**
 * Every memory / rule the conversation actually pulled into context.
 *
 * References arrive per assistant turn (and some land on `message_end` before the turn commits),
 * so the panel needs them merged and de-duplicated — by `scope:name` for memories and
 * `source:name` for rules, first occurrence wins.
 *
 * Extracted from `ChatPanel`: the desktop right panel and the mobile tasks sheet show the same list.
 */
export function useAggregatedReferences(): ContextReferences {
  const messages = useChatStore((state) => state.messages);
  const pendingMessageData = useChatStore((state) => state.pendingMessageData);

  return useMemo<ContextReferences>(() => {
    const memoryMap = new Map<string, ContextReferences['memories'][0]>();
    const ruleMap = new Map<string, ContextReferences['rules'][0]>();

    const addRefs = (refs: ContextReferences) => {
      for (const mem of refs.memories) {
        const key = `${mem.scope}:${mem.name}`;
        if (!memoryMap.has(key)) memoryMap.set(key, mem);
      }
      for (const rule of refs.rules) {
        const key = `${rule.source}:${rule.name}`;
        if (!ruleMap.has(key)) ruleMap.set(key, rule);
      }
    };

    // From committed messages
    for (const msg of messages) {
      if (msg.role === 'assistant') {
        const refs = (msg as AssistantMessage).references;
        if (refs) addRefs(refs);
      }
    }

    // From pendingMessageData (references received via message_end but not yet committed)
    for (const data of Object.values(pendingMessageData)) {
      if (data.references) addRefs(data.references);
    }

    return {
      memories: Array.from(memoryMap.values()),
      rules: Array.from(ruleMap.values()),
    };
  }, [messages, pendingMessageData]);
}
