import { useState, useEffect } from 'react';
import { integrationService } from '@/services/integration-service';

/**
 * Hook that checks whether the web search tool is configured (has API keys).
 * Returns `configured` state: true if at least one provider key is set.
 *
 * Used by ToolSelector, InlineAgentEditor, and SwarmAgentEditor to show
 * a warning when the user selects the `websearch` tool without configuring keys.
 */
export function useWebSearchStatus() {
  const [configured, setConfigured] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    integrationService.getStatus()
      .then((status) => {
        if (!cancelled) setConfigured(status.webSearch.configured);
      })
      .catch(() => {
        if (!cancelled) setConfigured(null);
      });
    return () => { cancelled = true; };
  }, []);

  return { configured };
}
