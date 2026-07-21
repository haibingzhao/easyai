import { useState, useEffect, useCallback, useRef } from 'react';
import type { SlashCommand } from '@/types/command';
import { CommandService } from '@/services/command-service';

interface UseSlashCommandReturn {
  isOpen: boolean;
  filtered: SlashCommand[];
  selectedIndex: number;
  onInput: (text: string, hasCommand: boolean) => void;
  onKeyDown: (e: React.KeyboardEvent) => boolean;
  close: () => void;
}

/**
 * Hook for slash command autocomplete in the message editor.
 *
 * Detects when input starts with `/`, filters available commands,
 * and provides keyboard navigation. The caller is responsible for
 * inserting the command chip into the DOM.
 */
export function useSlashCommand(agentId: string | null): UseSlashCommandReturn {
  const [filtered, setFiltered] = useState<SlashCommand[]>([]);
  const [isOpen, setIsOpen] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const commandsRef = useRef<SlashCommand[]>([]);

  // Load commands when agentId changes
  useEffect(() => {
    let cancelled = false;
    CommandService.invalidateCache();
    CommandService.fetchCommands(agentId).then((cmds) => {
      if (!cancelled) {
        commandsRef.current = cmds;
      }
    });
    return () => { cancelled = true; };
  }, [agentId]);

  const close = useCallback(() => {
    setIsOpen(false);
    setFiltered([]);
    setSelectedIndex(0);
  }, []);

  const onInput = useCallback((text: string, hasCommand: boolean) => {
    // Don't trigger when a command chip is already selected
    if (hasCommand) {
      close();
      return;
    }

    // Only trigger when text starts with `/`
    if (!text.startsWith('/')) {
      close();
      return;
    }

    const query = text.slice(1).toLowerCase();
    const matched = commandsRef.current.filter((c) =>
      c.name.toLowerCase().startsWith(query) ||
      c.aliases.some((a) => a.toLowerCase().startsWith(query))
    );

    if (matched.length === 0) {
      close();
      return;
    }

    setFiltered(matched);
    setSelectedIndex(0);
    setIsOpen(true);
  }, [close]);

  const onKeyDown = useCallback((e: React.KeyboardEvent): boolean => {
    if (!isOpen || filtered.length === 0) return false;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setSelectedIndex((prev) => (prev + 1) % filtered.length);
        return true;

      case 'ArrowUp':
        e.preventDefault();
        setSelectedIndex((prev) => (prev - 1 + filtered.length) % filtered.length);
        return true;

      case 'Tab':
      case 'Enter':
        // Don't prevent default here — let the caller handle it
        // but signal that a selection should be made
        return true;

      case 'Escape':
        e.preventDefault();
        close();
        return true;

      default:
        return false;
    }
  }, [isOpen, filtered.length, close]);

  return { isOpen, filtered, selectedIndex, onInput, onKeyDown, close };
}
