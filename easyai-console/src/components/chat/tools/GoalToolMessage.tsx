/**
 * GoalTool 消息渲染组件
 *
 * 在 MessageList 中展示 goal 工具调用：
 * - 标题行显示 action 类型摘要及状态 Badge
 * - 可展开查看 objective / evidence / reason 详情
 */

import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  Target,
  CheckCircle2,
  AlertTriangle,
  Pause,
  CircleDot,
  ChevronDown,
  RefreshCw,
  FileText,
  ShieldCheck,
} from 'lucide-react';
import type { ToolMessageProps } from './types';

// ---------------------------------------------------------------------------
// 参数解析
// ---------------------------------------------------------------------------

interface ParsedGoalArgs {
  action: string;
  status?: string;
  objective?: string;
  evidence?: string;
  reason?: string;
}

function parseGoalArgs(args: string): ParsedGoalArgs | null {
  try {
    return JSON.parse(args);
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// 状态 Badge 配色（与 GoalCard 视觉体系保持一致）
// ---------------------------------------------------------------------------

const STATUS_CONFIG: Record<string, { label: string; color: string; dotColor: string; icon: React.ReactNode }> = {
  active: {
    label: 'Active',
    color: 'text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-950/40',
    dotColor: 'bg-blue-500',
    icon: <CircleDot className="size-3 text-blue-500" />,
  },
  completed: {
    label: 'Completed',
    color: 'text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-950/40',
    dotColor: 'bg-green-500',
    icon: <CheckCircle2 className="size-3 text-green-500" />,
  },
  blocked: {
    label: 'Blocked',
    color: 'text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-950/40',
    dotColor: 'bg-amber-500',
    icon: <AlertTriangle className="size-3 text-amber-500" />,
  },
  paused: {
    label: 'Paused',
    color: 'text-gray-600 dark:text-gray-400 bg-gray-100 dark:bg-gray-800/60',
    dotColor: 'bg-gray-400',
    icon: <Pause className="size-3 text-gray-500" />,
  },
};

// ---------------------------------------------------------------------------
// Action 标签映射
// ---------------------------------------------------------------------------

const ACTION_META: Record<string, { label: string; icon: React.ReactNode }> = {
  update_status:    { label: 'Update Status',    icon: <RefreshCw className="size-3.5 text-muted-foreground" /> },
  update_objective: { label: 'Update Objective', icon: <FileText className="size-3.5 text-muted-foreground" /> },
  add_evidence:     { label: 'Add Evidence',     icon: <ShieldCheck className="size-3.5 text-muted-foreground" /> },
};

// ---------------------------------------------------------------------------
// 工具执行状态 Badge（Running / Pending / Failed）
// ---------------------------------------------------------------------------

function ExecStatusDot({ status }: { status: ToolMessageProps['status'] }) {
  if (status === 'RUNNING' || status === 'PENDING') {
    return <span className="w-2 h-2 rounded-full bg-muted-foreground animate-pulse" />;
  }
  if (status === 'FAILED') {
    return <span className="w-2 h-2 rounded-full bg-destructive" />;
  }
  return <span className="w-2 h-2 rounded-full bg-green-500" />;
}

// ---------------------------------------------------------------------------
// 详情预览（截断长文本）
// ---------------------------------------------------------------------------

function DetailPreview({ text, maxLen = 160 }: { text: string; maxLen?: number }) {
  const truncated = text.length > maxLen;
  return (
    <span className="text-foreground">
      {truncated ? text.slice(0, maxLen) + '…' : text}
    </span>
  );
}

// ---------------------------------------------------------------------------
// 主组件
// ---------------------------------------------------------------------------

export function GoalToolMessage({ toolCall, result, status }: ToolMessageProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  const parsed = parseGoalArgs(toolCall.args);
  const isFailed = status === 'FAILED';

  // ---------- 无有效参数时降级到通用展示 ----------
  if (!parsed) {
    return (
      <div className="border border-border rounded-lg bg-card overflow-hidden">
        <div className="p-3 flex items-center gap-2">
          <Target className="size-4 text-muted-foreground" />
          <span className="text-sm font-medium">Goal</span>
          <span className="text-xs text-muted-foreground">{toolCall.args}</span>
        </div>
      </div>
    );
  }

  const actionMeta = ACTION_META[parsed.action] ?? { label: parsed.action, icon: <Target className="size-3.5 text-muted-foreground" /> };
  const statusCfg  = parsed.status ? STATUS_CONFIG[parsed.status] : null;

  // 详情文本：根据不同 action 决定主体内容
  const detailText =
    parsed.action === 'update_objective' ? parsed.objective :
    parsed.action === 'add_evidence'     ? parsed.evidence  :
    parsed.reason ?? parsed.evidence ?? null;

  // 标题行摘要文本（折叠时可见的简短描述）
  const summaryText =
    parsed.action === 'update_objective' && parsed.objective
      ? parsed.objective.length > 80 ? parsed.objective.slice(0, 80) + '…' : parsed.objective
      : parsed.action === 'add_evidence' && parsed.evidence
        ? parsed.evidence.length > 80 ? parsed.evidence.slice(0, 80) + '…' : parsed.evidence
        : null;

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* 标题行 */}
      <div
        className="p-3 flex items-center justify-between gap-2 cursor-pointer hover:bg-muted/50 transition-colors"
        onClick={() => detailText ? setIsExpanded(!isExpanded) : undefined}
      >
        <div className="flex items-center gap-2 min-w-0">
          {/* Goal 图标 */}
          <Target className="size-4 text-violet-500 dark:text-violet-400 shrink-0" />

          {/* 工具名 */}
          <span className="text-sm font-medium text-foreground shrink-0">Goal</span>

          {/* Action 图标 + 标签 */}
          <span className="flex items-center gap-1 text-xs text-muted-foreground shrink-0">
            {actionMeta.icon}
            {actionMeta.label}
          </span>

          {/* 状态 Badge（仅 update_status 时显示） */}
          {statusCfg && (
            <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-xs font-medium ${statusCfg.color}`}>
              {statusCfg.icon}
              {statusCfg.label}
            </span>
          )}

          {/* 摘要文本（update_objective / add_evidence 折叠时内联预览） */}
          {summaryText && !isExpanded && (
            <span className="text-xs text-muted-foreground truncate min-w-0">
              — <DetailPreview text={summaryText} maxLen={60} />
            </span>
          )}
        </div>

        <div className="flex items-center gap-2 shrink-0">
          <ExecStatusDot status={status} />
          {detailText && (
            <ChevronDown
              className={`size-4 text-muted-foreground transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
            />
          )}
        </div>
      </div>

      {/* 展开详情 */}
      {isExpanded && detailText && (
        <>
          <div className="border-t border-border" />
          <div className="p-3 space-y-2">
            {/* Action 描述头 */}
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              {actionMeta.icon}
              <span className="font-medium">{actionMeta.label}</span>
              {statusCfg && (
                <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-xs font-medium ${statusCfg.color}`}>
                  {statusCfg.icon}
                  {statusCfg.label}
                </span>
              )}
            </div>

            {/* 主体文本 */}
            <div className="text-sm bg-muted/40 rounded px-3 py-2 prose prose-sm dark:prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {detailText.length > 500 ? detailText.slice(0, 500) + '…' : detailText}
              </ReactMarkdown>
            </div>

            {/* 附加 reason（update_status + reason 时额外显示） */}
            {parsed.action === 'update_status' && parsed.reason && parsed.evidence && (
              <div className="text-xs text-muted-foreground space-y-1">
                <div className="prose prose-xs dark:prose-invert max-w-none">
                  <span className="font-medium">Reason: </span>
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{parsed.reason}</ReactMarkdown>
                </div>
                <div className="prose prose-xs dark:prose-invert max-w-none">
                  <span className="font-medium">Evidence: </span>
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{parsed.evidence}</ReactMarkdown>
                </div>
              </div>
            )}
          </div>
        </>
      )}

      {/* 错误输出 */}
      {isFailed && result && (
        <>
          <div className="border-t border-border" />
          <div className="p-3">
            <div className="flex items-center gap-2 mb-1.5">
              <AlertTriangle className="size-4 text-amber-500" />
              <span className="text-sm font-medium text-amber-600 dark:text-amber-400">Error</span>
            </div>
            <div className="text-sm font-mono whitespace-pre-wrap break-all text-destructive bg-destructive/10 rounded p-2 max-h-[10em] overflow-y-auto">
              {result.result}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
