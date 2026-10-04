import { ChevronDown, ChevronRight } from 'lucide-react';
import type { ToolCallStatus } from '@/types/socket-event';
import { getToolDisplayName, getToolIcon } from './icons';
import { i18n } from '@/utils/i18n';
import { ToolTooltip } from '@/components/agent/ToolItem';

interface ToolRowHeaderProps {
  toolName: string;
  status: ToolCallStatus;
  summary?: string;
  expanded: boolean;
  onToggle: () => void;
  /** False for rows with no detail body — renders without a chevron or click affordance */
  expandable?: boolean;
}

function getStatusLabel(status: ToolCallStatus): string {
  switch (status) {
    case 'RUNNING':
      return i18n('Running...');
    case 'PENDING':
      return i18n('Pending...');
    case 'FAILED':
      return i18n('Failed');
    case 'COMPLETED':
      return i18n('Ran');
    default:
      return i18n('Running...');
  }
}

function getStatusColor(status: ToolCallStatus): string {
  if (status === 'FAILED') return 'text-destructive';
  return 'text-muted-foreground';
}

function getStatusDotColor(status: ToolCallStatus): string {
  if (status === 'RUNNING' || status === 'PENDING') return 'bg-muted-foreground animate-pulse';
  if (status === 'FAILED') return 'bg-destructive';
  return 'bg-green-500';
}

/**
 * Compact single-line header for tool rows inside process groups.
 * MCP tools ("serverName__toolName") render as "server / tool".
 */
export function ToolRowHeader({ toolName, status, summary, expanded, onToggle, expandable = true }: ToolRowHeaderProps) {
  const isMcp = toolName.includes('__');
  const Icon = getToolIcon(toolName);

  let displayName: string;
  let serverName: string | undefined;
  if (isMcp) {
    const parts = toolName.split('__');
    serverName = parts.slice(0, -1).join('__').replace(/_/g, '-') || toolName;
    displayName = parts[parts.length - 1]?.replace(/_/g, '-') || toolName;
  } else {
    displayName = getToolDisplayName(toolName);
  }

  return (
    <button
      type="button"
      onClick={expandable ? onToggle : undefined}
      className={`w-full flex items-center gap-2 py-0.5 text-left transition-colors ${expandable ? 'hover:bg-muted/30 rounded' : 'cursor-default'}`}
    >
      {expandable && (expanded
        ? <ChevronDown className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
        : <ChevronRight className="w-3.5 h-3.5 text-muted-foreground shrink-0" />)}
      <Icon className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
      {serverName && (
        <>
          <span className="text-xs text-muted-foreground shrink-0">{serverName}</span>
          <span className="text-muted-foreground shrink-0">/</span>
        </>
      )}
      <span className="text-sm text-foreground/90 shrink-0">{displayName}</span>
      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${getStatusDotColor(status)}`} />
      <span className={`text-xs shrink-0 ${getStatusColor(status)}`}>{getStatusLabel(status)}</span>
      {summary && (
        <>
          <span className="text-muted-foreground/50 shrink-0">·</span>
          <ToolTooltip name={toolName} description={summary} className="min-w-0">
            <span className="block text-xs text-muted-foreground font-mono truncate">{summary}</span>
          </ToolTooltip>
        </>
      )}
    </button>
  );
}
