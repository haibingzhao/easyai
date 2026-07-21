import React, { useEffect, useRef, useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Settings } from 'lucide-react';
import type { SlashCommand } from '@/types/command';

interface SlashCommandPopoverProps {
  commands: SlashCommand[];
  selectedIndex: number;
  onSelect: (cmd: SlashCommand) => void;
  onClose: () => void;
}

interface CommandGroup {
  label: string;
  commands: SlashCommand[];
}

export const SlashCommandPopover: React.FC<SlashCommandPopoverProps> = ({
  commands,
  selectedIndex,
  onSelect,
  onClose,
}) => {
  const listRef = useRef<HTMLDivElement>(null);
  const selectedRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const [hoveredCmd, setHoveredCmd] = useState<SlashCommand | null>(null);
  const [tooltipPos, setTooltipPos] = useState<{ top: number } | null>(null);
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleMouseEnter = useCallback((cmd: SlashCommand, e: React.MouseEvent) => {
    if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
    // Capture rect synchronously — e.currentTarget is null inside setTimeout
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const outerEl = listRef.current;
    const outerRect = outerEl?.getBoundingClientRect();
    // Position relative to the outer wrapper (not clipped by overflow)
    const offsetTop = outerRect ? rect.top - outerRect.top : null;
    hoverTimerRef.current = setTimeout(() => {
      if (offsetTop !== null) {
        setTooltipPos({ top: offsetTop });
      }
      setHoveredCmd(cmd);
    }, 300);
  }, []);

  const handleMouseLeave = useCallback(() => {
    if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
    setHoveredCmd(null);
    setTooltipPos(null);
  }, []);

  // Cleanup timer on unmount
  useEffect(() => {
    return () => {
      if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
    };
  }, []);

  // Group commands into three sections
  const groups = useMemo<CommandGroup[]>(() => {
    const userSkills: SlashCommand[] = [];
    const projectSkills: SlashCommand[] = [];
    const cmds: SlashCommand[] = [];

    for (const cmd of commands) {
      switch (cmd.category) {
        case 'USER':
          userSkills.push(cmd);
          break;
        case 'SKILL':
          projectSkills.push(cmd);
          break;
        default:
          cmds.push(cmd);
          break;
      }
    }

    const result: CommandGroup[] = [];
    if (userSkills.length > 0) {
      result.push({ label: 'Skills (User)', commands: userSkills });
    }
    if (cmds.length > 0) {
      result.push({ label: 'Commands', commands: cmds });
    }
    if (projectSkills.length > 0) {
      result.push({ label: 'Skills (Project)', commands: projectSkills });
    }
    return result;
  }, [commands]);

  // Scroll selected item into view
  useEffect(() => {
    selectedRef.current?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (listRef.current && !listRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onClose]);

  if (commands.length === 0) return null;

  // Compute flat index offset for each group
  let flatIndex = 0;

  return (
    <div
      ref={listRef}
      className="absolute left-0 right-0 z-50 bottom-full mb-1 mx-0 relative"
    >
      <div className="bg-popover border border-border rounded-lg shadow-lg overflow-hidden max-h-72 overflow-y-auto">
        {groups.map((group) => {
          const groupStartIndex = flatIndex;
          flatIndex += group.commands.length;

          return (
            <div key={group.label}>
              {/* Group header — title case */}
              <div className="px-3 pt-2 pb-1 text-[11px] font-semibold text-muted-foreground tracking-wider">
                {group.label}
              </div>

              {/* Group items */}
              {group.commands.map((cmd, localIndex) => {
                const globalIndex = groupStartIndex + localIndex;
                const isSelected = globalIndex === selectedIndex;

                return (
                  <div
                    key={`${cmd.category}-${cmd.name}`}
                    ref={isSelected ? selectedRef : undefined}
                    className={`flex items-start gap-2 px-3 py-1.5 cursor-pointer transition-colors ${
                      isSelected
                        ? 'bg-accent text-accent-foreground'
                        : 'hover:bg-muted/50'
                    }`}
                    onMouseDown={(e) => {
                      e.preventDefault();
                      onSelect(cmd);
                    }}
                    onMouseEnter={(e) => handleMouseEnter(cmd, e)}
                    onMouseLeave={handleMouseLeave}
                  >
                    <span className="text-sm font-mono font-medium shrink-0 leading-5">
                      {cmd.name}
                    </span>
                    {cmd.description && (
                      <span className="text-xs text-muted-foreground truncate leading-5">
                        {cmd.description}
                      </span>
                    )}
                  </div>
                );
              })}
            </div>
          );
        })}

        {/* Manage footer */}
        <div
          className="flex items-center gap-2 px-3 py-2 cursor-pointer border-t border-border text-sm text-muted-foreground hover:bg-muted/50 transition-colors"
          onMouseDown={(e) => {
            e.preventDefault();
            navigate('/commands');
            onClose();
          }}
        >
          <Settings className="w-3.5 h-3.5" />
          <span>Manage</span>
        </div>
      </div>

      {/* Hover tooltip — outside scroll container to avoid clipping */}
      {hoveredCmd?.description && tooltipPos && (
        <div
          className="absolute right-0 z-50 w-72 p-3 bg-zinc-800 border border-zinc-700 rounded-lg shadow-xl pointer-events-none"
          style={{ top: tooltipPos.top }}
        >
          <div className="text-sm font-mono font-semibold text-zinc-100 mb-1">
            {hoveredCmd.name}
          </div>
          <div className="text-xs text-zinc-300 leading-relaxed whitespace-pre-wrap">
            {hoveredCmd.description}
          </div>
        </div>
      )}
    </div>
  );
};
