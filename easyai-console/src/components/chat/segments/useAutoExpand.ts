import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Expand/collapse state that tracks `isActive` (e.g. the item currently executing
 * during streaming) until the user manually toggles it — manual choice always wins.
 */
export function useAutoExpand(isActive: boolean): [boolean, () => void] {
  const [expanded, setExpanded] = useState(isActive);
  const touchedRef = useRef(false);

  useEffect(() => {
    if (!touchedRef.current) {
      setExpanded(isActive);
    }
  }, [isActive]);

  const onToggle = useCallback(() => {
    touchedRef.current = true;
    setExpanded(prev => !prev);
  }, []);

  return [expanded, onToggle];
}
