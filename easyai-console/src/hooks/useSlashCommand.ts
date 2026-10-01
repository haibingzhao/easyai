import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import type { SlashCommand } from '@/types/command';
import type { CommandIdentity } from '@/utils/command-utils';
import { flattenCommands } from '@/utils/command-utils';
import { CommandService } from '@/services/command-service';
import { useAuthStore } from '@/services/stores/auth-store';
import { i18n } from '@/utils/i18n';

interface CommandSnapshot {
  scopeKey: string;
  agentId: string | null;
  commands: SlashCommand[];
}

/** The caller's own commands plus the shared layer, refreshed on every opening. No process-wide cache. */
export function useSlashCommand(agentId: string | null) {
  const userId = useAuthStore((state) => state.user?.id);
  const scopeKey = userId ?? '';
  const [snapshot, setSnapshot] = useState<CommandSnapshot | null>(null);
  const [query, setQuery] = useState<string | null>(null);
  const queryRef = useRef<string | null>(null);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const requestRef = useRef<AbortController | null>(null);
  const validationsRef = useRef(new Set<AbortController>());

  const isCurrentContext = useCallback(() => useAuthStore.getState().user?.id === userId, [userId]);

  const refresh = useCallback(() => {
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    CommandService.fetchCommands(agentId, controller.signal).then((commands) => {
      if (!controller.signal.aborted && isCurrentContext()) {
        setSnapshot({ scopeKey, agentId, commands });
        setSelectedIndex(0);
      }
    }).catch(() => {
      if (!controller.signal.aborted && isCurrentContext()) {
        setSnapshot({ scopeKey, agentId, commands: [] });
      }
    });
  }, [agentId, scopeKey, isCurrentContext]);

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

  // Gate synchronously on the owner, so a render after switching user never shows old rows.
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
    if (!command?.skillName) return null;
    if (snapshot?.scopeKey !== scopeKey) return i18n('Checking skill availability...');
    return snapshot.commands.some((candidate) => candidate.skillName === command.skillName)
      ? null : i18n('This skill is not installed for you any more. Remove it or select another skill.');
  }, [snapshot, scopeKey]);

  /** Validate the named skill against the caller's visible set. Also used before queue promotion. */
  const validateCommand = useCallback(async (command: CommandIdentity | null | undefined): Promise<string | null> => {
    if (!isCurrentContext()) return i18n('The user changed. Please try again.');
    if (!command?.skillName) return null;
    const controller = new AbortController();
    validationsRef.current.add(controller);
    try {
      // SKILL visibility does not depend on the selected agent.
      const available = await CommandService.fetchCommands(null, controller.signal);
      if (!isCurrentContext() || controller.signal.aborted) return i18n('The user changed. Please try again.');
      return available.some((candidate) => candidate.skillName === command.skillName)
        ? null : i18n('This skill is not installed for you any more. Remove it or select another skill.');
    } catch {
      return i18n('Unable to verify the skill. Please try again.');
    } finally {
      validationsRef.current.delete(controller);
    }
  }, [isCurrentContext]);

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
