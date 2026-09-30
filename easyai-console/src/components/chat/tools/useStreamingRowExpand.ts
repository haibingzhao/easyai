import { useEffect, useRef } from 'react';
import type { ToolCallStatus } from '@/types/socket-event';

type BooleanUpdater = (updater: (prev: boolean) => boolean) => void;

/**
 * Auto-expands a tool row while the tool is streaming and collapses it once the
 * stream ends. Returns a marker that pins the state to the user's manual choice.
 */
export function useStreamingRowExpand(
  status: ToolCallStatus | undefined,
  setExpanded: BooleanUpdater,
): () => void {
  const touchedRef = useRef(false);
  const isStreaming = status === 'RUNNING' || status === 'PENDING';

  useEffect(() => {
    if (touchedRef.current) return;
    setExpanded(() => isStreaming);
  }, [isStreaming, setExpanded]);

  return () => {
    touchedRef.current = true;
  };
}
