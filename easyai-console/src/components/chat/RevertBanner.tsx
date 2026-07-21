import React, { useState } from 'react';
import type { FileDiff } from '../../types/checkpoint';
import { DiffViewer } from './DiffViewer';

interface RevertBannerProps {
  /** Timestamp of the revert */
  timestamp: number;
  /** Whether an unrevert is in progress */
  isUnreverting?: boolean;
  /** Called when user clicks "Restore original state" (unrevert) */
  onUnrevert: () => void;
  /** Called when user clicks "Continue here" (confirm revert, start new) */
  onContinue: () => void;
  /** Optional diffs to show */
  diffs?: FileDiff[];
}

/**
 * Banner displayed above the composer when in reverted state.
 * Shows revert info and actions: view changes, restore, continue.
 */
export const RevertBanner: React.FC<RevertBannerProps> = ({
  timestamp,
  isUnreverting = false,
  onUnrevert,
  onContinue,
  diffs,
}) => {
  const [showDiff, setShowDiff] = useState(false);

  const timeStr = new Date(timestamp).toLocaleTimeString();

  return (
    <div className="border border-amber-500/30 bg-amber-500/5 rounded-lg p-3 mx-4 mb-2">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-amber-500 text-sm">⚠</span>
        <span className="text-sm font-medium text-amber-600 dark:text-amber-400">
          Reverted to {timeStr}
        </span>
      </div>
      <div className="flex items-center gap-2 flex-wrap">
        {diffs && diffs.length > 0 && (
          <button
            className="text-xs px-2.5 py-1 rounded-md border border-border hover:bg-accent transition-colors"
            onClick={() => setShowDiff(!showDiff)}
          >
            {showDiff ? 'Hide changes' : 'View changes'}
          </button>
        )}
        <button
          className="text-xs px-2.5 py-1 rounded-md border border-amber-500/30 text-amber-600 dark:text-amber-400 hover:bg-amber-500/10 transition-colors disabled:opacity-50"
          onClick={onUnrevert}
          disabled={isUnreverting}
        >
          {isUnreverting ? 'Restoring...' : 'Restore original state'}
        </button>
        <button
          className="text-xs px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          onClick={onContinue}
        >
          Continue here
        </button>
      </div>
      {showDiff && diffs && diffs.length > 0 && (
        <div className="mt-3">
          <DiffViewer diffs={diffs} />
        </div>
      )}
    </div>
  );
};
