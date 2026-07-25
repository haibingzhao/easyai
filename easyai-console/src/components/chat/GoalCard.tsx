import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ChevronDown,
  ChevronRight,
  Pause,
  Play,
  Pencil,
  Trash2,
  Target,
  CheckCircle2,
  AlertTriangle,
  Timer,
} from 'lucide-react';
import type { GoalStatusEvent } from '@/types/socket-event';
import { useChatStore } from '@/services/stores/chat-store';
import { pauseGoal, resumeGoal, deleteGoal } from '@/services/goal-service';
import { i18n } from '@/utils/i18n';
import { formatDurationSeconds } from '@/utils/format';
import { GoalEditDialog } from './GoalEditDialog';

interface GoalCardProps {
  goal: GoalStatusEvent;
}

/**
 * Interactive Goal card component with collapsible content and management actions.
 *
 * Features:
 * - Collapsible header showing goal status
 * - Pause/Resume toggle button
 * - Edit button to modify goal objective
 * - Delete button with confirmation
 * - Color-coded status indicators
 */
export const GoalCard: React.FC<GoalCardProps> = ({ goal }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isLoading, setIsLoading] = useState(false);

  const sessionId = useChatStore((state) => state.sessionId);
  const refreshGoal = useChatStore((state) => state.refreshGoal);

  const isCompleted = goal.status === 'completed';
  const isBlocked = goal.status === 'blocked';
  const isPaused = goal.status === 'paused';
  const isLimitReached = goal.status === 'limit_reached';
  const isActive = goal.status === 'active';

  const handlePauseResume = async () => {
    if (!sessionId) return;
    setIsLoading(true);
    try {
      if (isPaused) {
        await resumeGoal(sessionId);
      } else if (isActive) {
        await pauseGoal(sessionId);
      }
      await refreshGoal(sessionId);
    } catch (error) {
      console.error('Failed to pause/resume goal:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const handleDelete = async () => {
    if (!sessionId) return;
    if (!isDeleting) {
      setIsDeleting(true);
      return;
    }
    setIsLoading(true);
    try {
      await deleteGoal(sessionId);
      await refreshGoal(sessionId);
    } catch (error) {
      console.error('Failed to delete goal:', error);
    } finally {
      setIsLoading(false);
      setIsDeleting(false);
    }
  };

  const handleEditComplete = async (_newObjective: string) => {
    setIsEditing(false);
    if (sessionId) {
      await refreshGoal(sessionId);
    }
  };

  const getStatusIcon = () => {
    if (isCompleted) return <CheckCircle2 className="size-3.5 text-green-500" />;
    if (isBlocked) return <AlertTriangle className="size-3.5 text-amber-500" />;
    if (isPaused) return <Pause className="size-3.5 text-gray-500" />;
    if (isLimitReached) return <Timer className="size-3.5 text-gray-500" />;
    return <Target className="size-3.5 text-blue-500" />;
  };

  const getStatusLabel = () => {
    if (isCompleted) return i18n('Goal Completed');
    if (isBlocked) return i18n('Goal Blocked');
    if (isPaused) return i18n('Goal Paused');
    if (isLimitReached) return i18n('Goal Limit Reached');
    return i18n('Goal Active');
  };

  const getStatusLabelColor = () => {
    if (isCompleted) return 'text-green-700 dark:text-green-400';
    if (isBlocked) return 'text-amber-700 dark:text-amber-400';
    if (isPaused) return 'text-gray-700 dark:text-gray-400';
    return 'text-blue-700 dark:text-blue-400';
  };

  return (
    <>
      <div className="text-sm">
        {/* Header — matches Progress / References style */}
        <div
          className="flex items-center justify-between py-2 cursor-pointer hover:bg-muted/50 transition-colors px-2 rounded"
          onClick={() => setIsExpanded(!isExpanded)}
        >
          <div className="flex items-center gap-1.5 min-w-0">
            {getStatusIcon()}
            <span className="text-muted-foreground font-medium">
              {i18n('Goal')}
            </span>
            <span className={`text-xs ${getStatusLabelColor()}`}>
              {getStatusLabel()}
            </span>
          </div>
          <div className="flex items-center gap-1 shrink-0">
            {/* Pause/Resume button — only when goal can actually be paused or resumed */}
            {(isActive || isPaused) && (
              <button
                onClick={(e) => { e.stopPropagation(); handlePauseResume(); }}
                disabled={isLoading}
                className="p-1 rounded hover:bg-black/10 dark:hover:bg-white/10 transition-colors disabled:opacity-50"
                title={isPaused ? i18n('Resume Goal') : i18n('Pause Goal')}
              >
                {isPaused ? (
                  <Play className="size-3 text-muted-foreground" />
                ) : (
                  <Pause className="size-3 text-muted-foreground" />
                )}
              </button>
            )}
            {/* Edit button — available when goal is not in terminal state */}
            {!isCompleted && !isBlocked && (
              <button
                onClick={(e) => { e.stopPropagation(); setIsEditing(true); }}
                disabled={isLoading}
                className="p-1 rounded hover:bg-black/10 dark:hover:bg-white/10 transition-colors disabled:opacity-50"
                title={i18n('Edit Goal')}
              >
                <Pencil className="size-3 text-muted-foreground" />
              </button>
            )}
            {/* Delete button */}
            {!isCompleted && (
              <button
                onClick={(e) => { e.stopPropagation(); handleDelete(); }}
                disabled={isLoading}
                className={`p-1 rounded transition-colors disabled:opacity-50 ${
                  isDeleting
                    ? 'bg-red-100 dark:bg-red-950/50 hover:bg-red-200 dark:hover:bg-red-900/50'
                    : 'hover:bg-black/10 dark:hover:bg-white/10'
                }`}
                title={isDeleting ? i18n('Click again to confirm') : i18n('Delete Goal')}
              >
                <Trash2 className={`size-3 ${isDeleting ? 'text-red-500' : 'text-muted-foreground'}`} />
              </button>
            )}
            {isExpanded ? (
              <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />
            ) : (
              <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
            )}
          </div>
        </div>

        {/* Expandable content */}
        {isExpanded && (
          <div className="pb-2 px-2 space-y-1.5">
            {/* Goal objective */}
            <div className="text-xs text-foreground bg-muted/40 rounded px-2 py-1.5 prose prose-xs dark:prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{goal.objective}</ReactMarkdown>
            </div>

            {/* Progress info */}
            <div className="flex items-center gap-3 text-xs text-muted-foreground">
              <span>
                {i18n('Turn')}: {goal.turnCount}/{goal.maxTurns}
              </span>
              <span>·</span>
              <span>
                {formatDurationSeconds(goal.elapsedSeconds)} {i18n('elapsed')}
              </span>
            </div>

            {/* Evidence (if completed) */}
            {isCompleted && goal.evidence && (
              <div className="text-xs text-green-600 dark:text-green-400 prose prose-xs dark:prose-invert max-w-none">
                <span className="font-medium">{i18n('Evidence')}:</span>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{goal.evidence}</ReactMarkdown>
              </div>
            )}

            {/* Blocked reason (if blocked) */}
            {isBlocked && goal.blockedReason && (
              <div className="text-xs text-amber-600 dark:text-amber-400 prose prose-xs dark:prose-invert max-w-none">
                <span className="font-medium">{i18n('Blocked')}:</span>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{goal.blockedReason}</ReactMarkdown>
              </div>
            )}

            {/* Delete confirmation hint */}
            {isDeleting && (
              <div className="text-xs text-red-500">
                {i18n('Click delete again to confirm')}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Edit dialog */}
      {isEditing && (
        <GoalEditDialog
          currentObjective={goal.objective}
          sessionId={goal.sessionId}
          onClose={() => setIsEditing(false)}
          onSave={handleEditComplete}
        />
      )}
    </>
  );
};
