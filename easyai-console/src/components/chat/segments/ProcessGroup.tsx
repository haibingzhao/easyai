import type { ReactNode } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { useAutoExpand } from './useAutoExpand';

interface ProcessGroupProps {
  toolCount: number;
  failureCount: number;
  /** True while this group is the live tail of a streaming message */
  isActive: boolean;
  children: ReactNode;
}

/**
 * Collapsible "process group": folds a run of thinking / tool rows behind a single
 * summary line (`Ran N tools` or `Processed` when it holds thinking only).
 */
export function ProcessGroup({ toolCount, failureCount, isActive, children }: ProcessGroupProps) {
  const [expanded, toggle] = useAutoExpand(isActive);

  const title = toolCount > 0
    ? i18n('Ran {count} tools').replace('{count}', String(toolCount))
      + (failureCount > 0 ? i18n(', {count} failed').replace('{count}', String(failureCount)) : '')
    : i18n('Processed');

  return (
    <div>
      <button
        type="button"
        onClick={toggle}
        className="w-full flex items-center gap-2 py-0.5 text-left text-sm text-muted-foreground hover:text-foreground transition-colors"
      >
        {expanded
          ? <ChevronDown className="w-3.5 h-3.5 shrink-0" />
          : <ChevronRight className="w-3.5 h-3.5 shrink-0" />}
        <span className="shrink-0">{title}</span>
      </button>
      {expanded && (
        <div className="ml-2 pl-3 border-l border-border space-y-1 mt-1">
          {children}
        </div>
      )}
    </div>
  );
}
