import React, { useState, useRef, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { Code2, ChevronDown } from 'lucide-react';
import { ToolTooltip } from '@/components/agent/ToolItem';
import { i18n } from '@/utils/i18n';

/** A categorized variable group for the dropdown picker. */
export interface VariableGroup {
  label: string;
  vars: { name: string; description: string }[];
  dotColor: string;
}

/**
 * Compact collapsible variable picker designed to live inside the
 * JinjaTemplateEditor toolbar. Click to expand/collapse a categorized
 * grid; clicking a variable calls onInsert with the raw variable name.
 *
 * The dropdown panel is rendered via createPortal to document.body so it
 * is never clipped by overflow-hidden ancestors (e.g. the Jinja2 editor
 * container) and always appears above surrounding content.
 */
export const VariableDropdown: React.FC<{
  groups: VariableGroup[];
  onInsert: (varName: string) => void;
  totalCount: number;
}> = ({ groups, onInsert, totalCount }) => {
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number }>({ top: 0, left: 0 });

  const updatePos = useCallback(() => {
    if (!buttonRef.current) return;
    const rect = buttonRef.current.getBoundingClientRect();
    setPos({ top: rect.bottom + 4, left: rect.left });
  }, []);

  // Re-position on open and on window resize/scroll while open
  useEffect(() => {
    if (!open) return;
    updatePos();
    window.addEventListener('resize', updatePos);
    window.addEventListener('scroll', updatePos, true);
    return () => {
      window.removeEventListener('resize', updatePos);
      window.removeEventListener('scroll', updatePos, true);
    };
  }, [open, updatePos]);

  // Close when clicking outside the button or panel
  useEffect(() => {
    if (!open) return;
    const handleClick = (e: MouseEvent) => {
      const target = e.target as Node;
      if (
        buttonRef.current?.contains(target) ||
        panelRef.current?.contains(target)
      ) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  if (totalCount === 0) return null;

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen(!open)}
        className={`inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded font-medium cursor-pointer transition-colors ${
          open
            ? 'bg-amber-500/10 text-amber-500'
            : 'bg-[#21262d] text-[#8b949e] hover:bg-[#30363d] hover:text-[#c9d1d9] border border-[#30363d]'
        }`}
      >
        <Code2 className="w-2.5 h-2.5" />
        {i18n('Variables')}
        <span className="text-[9px] opacity-60">({totalCount})</span>
        <ChevronDown className={`w-2.5 h-2.5 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && createPortal(
        <div
          ref={panelRef}
          className="fixed z-[10000] w-72 max-h-56 overflow-y-auto rounded-md border border-[#30363d] bg-[#161b22] p-1.5 space-y-2 shadow-lg"
          style={{ top: pos.top, left: pos.left }}
        >
          {groups.map((group) => (
            group.vars.length > 0 && (
              <div key={group.label}>
                <div className="flex items-center gap-1.5 px-1 py-0.5">
                  <span className={`w-1.5 h-1.5 rounded-full ${group.dotColor}`} />
                  <span className="text-[9px] font-semibold text-[#8b949e] uppercase tracking-wider">{group.label}</span>
                </div>
                <div className="flex flex-wrap gap-1 px-0.5">
                  {group.vars.map((v) => (
                    <ToolTooltip key={v.name} name={`{{ ${v.name} }}`} description={v.description}>
                      <button
                        type="button"
                        onClick={() => {
                          onInsert(v.name);
                          setOpen(false);
                        }}
                        className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-mono border border-[#30363d] text-[#c9d1d9] hover:border-amber-500/40 hover:bg-amber-500/10 hover:text-amber-500 transition-colors cursor-pointer"
                      >
                        {v.name}
                      </button>
                    </ToolTooltip>
                  ))}
                </div>
              </div>
            )
          ))}
        </div>,
        document.body,
      )}
    </div>
  );
};
