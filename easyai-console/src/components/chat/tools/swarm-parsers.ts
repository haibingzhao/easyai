/**
 * Parsing utilities for the run_swarm tool renderer.
 * Extracts structured data from tool args and result markdown.
 */

/** Parsed swarm tool arguments */
export interface SwarmArgs {
  presetName: string;
  variables: Record<string, string>;
}

/**
 * Parse the run_swarm tool call arguments JSON string.
 * Returns presetName and variables map.
 */
export function parseSwarmArgs(args: string): SwarmArgs {
  try {
    const parsed = JSON.parse(args) as Record<string, unknown>;
    return {
      presetName: String(parsed.presetName ?? 'unknown'),
      variables: (parsed.variables as Record<string, string>) ?? {},
    };
  } catch {
    return { presetName: 'unknown', variables: {} };
  }
}

/**
 * Extract the run ID from the swarm result markdown summary.
 * Backend format: `**Run ID**: <id>`
 */
export function extractRunId(resultText: string): string | null {
  const match = resultText.match(/\*\*Run ID\*\*:\s*(\S+)/);
  return match?.[1] ?? null;
}

/**
 * Extract runId from streaming progress text (Phase 4 backend enhancement).
 * Backend format: `(runId: <id>)`
 */
export function extractRunIdFromStreaming(text: string): string | null {
  const match = text.match(/\(runId:\s*([^)]+)\)/);
  return match?.[1] ?? null;
}
