/**
 * Frontend DAG cycle detection utility.
 * Mirrors the backend DagAlgorithms.validateDag() logic for real-time validation.
 */

export interface TaskDependency {
  id: string;
  dependsOn?: string[];
}

export interface DagValidationResult {
  valid: boolean;
  /** Task IDs involved in cycles (empty when valid) */
  cycleMembers: string[];
  /** Human-readable error message (empty when valid) */
  error: string;
}

/**
 * Validates that the given tasks form a valid DAG (no cycles).
 * Uses DFS with white/gray/black coloring to detect back edges.
 */
export function validateDag(tasks: TaskDependency[]): DagValidationResult {
  const taskMap = new Map<string, TaskDependency>();
  for (const t of tasks) {
    taskMap.set(t.id, t);
  }

  // Check for references to non-existent tasks
  const allIds = new Set(taskMap.keys());
  for (const t of tasks) {
    for (const dep of t.dependsOn ?? []) {
      if (!allIds.has(dep)) {
        return {
          valid: false,
          cycleMembers: [],
          error: `Task '${t.id}' depends on unknown task '${dep}'`,
        };
      }
    }
  }

  // DFS cycle detection: 0=white (unvisited), 1=gray (in progress), 2=black (done)
  const color = new Map<string, number>();
  for (const id of allIds) {
    color.set(id, 0);
  }

  const cycleMembers = new Set<string>();

  const dfs = (id: string, path: string[]): void => {
    color.set(id, 1); // gray
    path.push(id);

    const deps = taskMap.get(id)?.dependsOn ?? [];
    for (const dep of deps) {
      const depColor = color.get(dep) ?? 0;
      if (depColor === 1) {
        // Back edge found — cycle detected; collect members but keep exploring
        const cycleStart = path.indexOf(dep);
        for (let i = cycleStart; i < path.length; i++) {
          cycleMembers.add(path[i]);
        }
      } else if (depColor === 0) {
        dfs(dep, path);
      }
    }

    path.pop();
    color.set(id, 2); // black
  };

  for (const id of allIds) {
    if (color.get(id) === 0) {
      dfs(id, []);
    }
  }

  if (cycleMembers.size > 0) {
    return {
      valid: false,
      cycleMembers: Array.from(cycleMembers),
      error: `Circular dependency detected among tasks: ${Array.from(cycleMembers).join(', ')}`,
    };
  }

  return { valid: true, cycleMembers: [], error: '' };
}
