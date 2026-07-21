import React, { useState, useEffect, useRef } from 'react';
import { X } from 'lucide-react';
import { updateGoal } from '@/services/goal-service';
import { i18n } from '@/utils/i18n';

interface GoalEditDialogProps {
  currentObjective: string;
  sessionId: string;
  onClose: () => void;
  onSave: (newObjective: string) => void;
}

/**
 * Modal dialog for editing a goal's objective text.
 *
 * Features:
 * - Pre-fills textarea with current objective
 * - Validates that objective is not empty
 * - Shows loading state during save
 * - Handles errors gracefully
 */
export const GoalEditDialog: React.FC<GoalEditDialogProps> = ({
  currentObjective,
  sessionId,
  onClose,
  onSave,
}) => {
  const [objective, setObjective] = useState(currentObjective);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    // Focus textarea on mount
    textareaRef.current?.focus();
  }, []);

  useEffect(() => {
    // Close on Escape key
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  const handleSave = async () => {
    const trimmed = objective.trim();
    if (!trimmed) {
      setError(i18n('Goal objective cannot be empty'));
      return;
    }

    setIsSaving(true);
    setError(null);

    try {
      await updateGoal(sessionId, trimmed);
      onSave(trimmed);
    } catch (err) {
      setError(err instanceof Error ? err.message : i18n('Failed to update goal'));
    } finally {
      setIsSaving(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      handleSave();
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 dark:bg-black/70"
        onClick={onClose}
      />

      {/* Dialog */}
      <div className="relative bg-white dark:bg-gray-900 rounded-lg shadow-xl w-full max-w-lg mx-4 border border-gray-200 dark:border-gray-700">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200 dark:border-gray-700">
          <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
            {i18n('Edit Goal')}
          </h2>
          <button
            onClick={onClose}
            className="p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
          >
            <X className="size-5 text-gray-500" />
          </button>
        </div>

        {/* Content */}
        <div className="px-5 py-4">
          <p className="text-sm text-gray-600 dark:text-gray-400 mb-3">
            {i18n('Set a goal to keep the agent focused on a clear outcome across multiple turns. A good goal should include completion criteria: what needs to be accomplished, how to verify success, and any constraints that must be respected.')}
          </p>

          <textarea
            ref={textareaRef}
            value={objective}
            onChange={(e) => {
              setObjective(e.target.value);
              setError(null);
            }}
            onKeyDown={handleKeyDown}
            placeholder={i18n('Describe the goal you want to achieve...')}
            className="w-full h-32 px-3 py-2 text-sm border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 resize-none"
            disabled={isSaving}
          />

          {error && (
            <p className="mt-2 text-sm text-red-500">{error}</p>
          )}

          <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">
            {i18n('Press')} ⌘+Enter {i18n('to save')}
          </p>
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-gray-200 dark:border-gray-700">
          <button
            onClick={onClose}
            disabled={isSaving}
            className="px-4 py-2 text-sm font-medium text-gray-700 dark:text-gray-300 bg-gray-100 dark:bg-gray-800 rounded-md hover:bg-gray-200 dark:hover:bg-gray-700 transition-colors disabled:opacity-50"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={handleSave}
            disabled={isSaving || !objective.trim()}
            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSaving ? i18n('Saving...') : i18n('Continue')}
          </button>
        </div>
      </div>
    </div>
  );
};
