import { useEffect, useRef } from 'react';

/**
 * Polls `fetchFn` at a fixed interval while `isActive` is true.
 * Automatically cleans up the interval when inactive or on unmount.
 */
export function usePolling(fetchFn: () => void, isActive: boolean, intervalMs = 3000): void {
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!isActive) {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }
    pollRef.current = setInterval(fetchFn, intervalMs);
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [isActive, fetchFn, intervalMs]);
}
