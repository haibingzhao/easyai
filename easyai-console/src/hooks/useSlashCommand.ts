import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import type { SlashCommand } from '@/types/command';
import type { CommandIdentity } from '@/utils/command-utils';
import { flattenCommands } from '@/utils/command-utils';
import { CommandService } from '@/services/command-service';
import { useAuthStore } from '@/services/stores/auth-store';
import { useProjectStore } from '@/services/stores/project-store';
import { i18n } from '@/utils/i18n';

interface CommandSnapshot {
  scopeKey: string;
  agentId: string | null;
  commands: SlashCommand[];
}

/** Project/user-scoped candidates, refreshed on every opening. No process-wide cache. */
export function useSlashCommand(agentId: string | null, projectId?: string) {
  const userId = useAuthStore((state) => state.user?.id);
  const scopeKey = JSON.stringify([userId, projectId]);
  const [snapshot, setSnapshot] = useState<CommandSnapshot | null>(null);
  const [query, setQuery] = useState<string | null>(null);
  const queryRef = useRef<string | null>(null);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const requestRef = useRef<AbortController | null>(null);
  const validationsRef = useRef(new Set<AbortController>());

  const isCurrentContext = useCallback(() => (
    useAuthStore.getState().user?.id === userId
    && useProjectStore.getState().currentProject?.id === projectId
  ), [userId, projectId]);

  const refresh = useCallback(() => {
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    CommandService.fetchCommands(agentId, projectId, controller.signal).then((commands) => {
      if (!controller.signal.aborted && isCurrentContext()) {
        setSnapshot({ scopeKey, agentId, commands });
        setSelectedIndex(0);
      }
    }).catch(() => {
      if (!controller.signal.aborted && isCurrentContext()) {
        setSnapshot({ scopeKey, agentId, commands: [] });
      }
    });
  }, [agentId, projectId, scopeKey, isCurrentContext]);

  const close = useCallback(() => {
    queryRef.current = null;
    setQuery(null);
    setSelectedIndex(0);
  }, []);

  useEffect(() => {
    close();
    refresh();
    const validations = validationsRef.current;
    return () => {
      requestRef.current?.abort();
      validations.forEach((controller) => controller.abort());
      validations.clear();
    };
  }, [refresh, close]);

  // Gate synchronously on scope, so a render after switching user/project never shows old rows.
  const commands = snapshot?.scopeKey === scopeKey && snapshot.agentId === agentId ? snapshot.commands : [];
  const filtered = useMemo(() => flattenCommands(commands.filter((command) => query !== null && (
    command.name.toLowerCase().startsWith(query)
    || command.aliases.some((alias) => alias.toLowerCase().startsWith(query))
  ))), [commands, query]);
  const isOpen = query !== null;

  const onInput = useCallback((text: string, hasCommand: boolean) => {
    if (hasCommand || !/^\/[a-zA-Z0-9_:-]*$/.test(text)) {
      close();
      return;
    }
    if (queryRef.current === null) refresh();
    queryRef.current = text.slice(1).toLowerCase();
    setQuery(queryRef.current);
    setSelectedIndex(0);
  }, [close, refresh]);

  const selectionError = useCallback((command: CommandIdentity | null | undefined): string | null => {
    if (!command?.source && command?.category !== 'SKILL') return null;
    if (snapshot?.scopeKey !== scopeKey) return i18n('Checking skill source...');
    return snapshot.commands.some((candidate) => candidate.category === 'SKILL' && candidate.source === command.source && candidate.name === command.name)
      ? null : i18n('This skill source is unavailable in the current project. Remove it or select another skill.');
  }, [snapshot, scopeKey]);

  /** Validate exact source, never substitute a same-named candidate. Also used before queue promotion. */
  const validateCommand = useCallback(async (command: CommandIdentity | null | undefined): Promise<string | null> => {
    if (!isCurrentContext()) return i18n('Project or user changed. Please try again.');
    if (!command?.source && command?.category !== 'SKILL') return null;
    const controller = new AbortController();
    validationsRef.current.add(controller);
    try {
      // SKILL visibility does not depend on the selected agent.
      const available = await CommandService.fetchCommands(null, projectId, controller.signal);
      if (!isCurrentContext() || controller.signal.aborted) return i18n('Project or user changed. Please try again.');
      return available.some((candidate) => candidate.category === 'SKILL' && candidate.source === command.source && candidate.name === command.name)
        ? null : i18n('This skill source is unavailable in the current project. Remove it or select another skill.');
    } catch {
      return i18n('Unable to verify the skill source. Please try again.');
    } finally {
      validationsRef.current.delete(controller);
    }
  }, [projectId, isCurrentContext]);

  const onKeyDown = useCallback((e: React.KeyboardEvent): boolean => {
    if (!isOpen) return false;
    if (e.key === 'Escape') {
      e.preventDefault();
      close();
      return true;
    }
    if (!filtered.length) return false;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIndex((index) => (index + (e.key === 'ArrowDown' ? 1 : -1) + filtered.length) % filtered.length);
      return true;
    }
    return e.key === 'Tab' || e.key === 'Enter';
  }, [isOpen, filtered.length, close]);

  return { isOpen, filtered, selectedIndex, onInput, onKeyDown, close, selectionError, validateCommand, isCurrentContext };
}
