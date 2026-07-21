/**
 * Shared DAG layout algorithms for workflow visualization.
 * Used by both the editor canvas (SwarmTaskEditor) and runtime canvas (DAGCanvas).
 */

export interface LayoutableTask {
  id: string;
  dependsOn?: string[];
}

export interface NodePosition {
  taskId: string;
  x: number;
  y: number;
  layer: number;
}

export interface LayoutOptions {
  nodeWidth?: number;
  nodeHeight?: number;
  layerGapY?: number;
  nodeGapX?: number;
  padding?: number;
}

export interface EdgeData {
  fromId: string;
  toId: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export const NODE_WIDTH = 180;
export const NODE_HEIGHT = 80;
export const LAYER_GAP_Y = 130;
export const NODE_GAP_X = 40;
export const CANVAS_PADDING = 40;

/**
 * Compute topological layers from dependsOn relationships.
 * Root nodes (no dependencies) are at layer 0.
 */
export function computeTopologicalLayers<T extends LayoutableTask>(tasks: T[]): Map<string, number> {
  const layerMap = new Map<string, number>();
  const taskMap = new Map(tasks.map((t) => [t.id, t]));

  const getLayer = (taskId: string, visited = new Set<string>()): number => {
    if (layerMap.has(taskId)) return layerMap.get(taskId)!;
    if (visited.has(taskId)) return 0; // cycle protection
    visited.add(taskId);

    const task = taskMap.get(taskId);
    if (!task || !task.dependsOn || task.dependsOn.length === 0) {
      layerMap.set(taskId, 0);
      return 0;
    }

    const maxParentLayer = Math.max(...task.dependsOn.map((dep) => getLayer(dep, visited)));
    const layer = maxParentLayer + 1;
    layerMap.set(taskId, layer);
    return layer;
  };

  tasks.forEach((t) => getLayer(t.id));
  return layerMap;
}

/**
 * Compute node positions using topological layering with centering.
 * Nodes in each layer are horizontally centered relative to the widest layer.
 */
export function computeNodePositions<T extends LayoutableTask>(
  tasks: T[],
  options?: LayoutOptions
): NodePosition[] {
  const nodeWidth = options?.nodeWidth ?? NODE_WIDTH;
  const layerGapY = options?.layerGapY ?? LAYER_GAP_Y;
  const nodeGapX = options?.nodeGapX ?? NODE_GAP_X;
  const padding = options?.padding ?? CANVAS_PADDING;

  const layerMap = computeTopologicalLayers(tasks);

  // Group tasks by layer
  const layerGroups = new Map<number, string[]>();
  layerMap.forEach((layer, taskId) => {
    if (!layerGroups.has(layer)) layerGroups.set(layer, []);
    layerGroups.get(layer)!.push(taskId);
  });

  const sortedLayers = Array.from(layerGroups.keys()).sort((a, b) => a - b);

  // Compute max layer width for centering
  const maxNodesInLayer = Math.max(
    ...sortedLayers.map((layer) => layerGroups.get(layer)!.length),
    1
  );
  const maxLayerWidth = maxNodesInLayer * nodeWidth + (maxNodesInLayer - 1) * nodeGapX;

  const positions: NodePosition[] = [];

  sortedLayers.forEach((layer) => {
    const taskIds = layerGroups.get(layer)!;
    const layerWidth = taskIds.length * nodeWidth + (taskIds.length - 1) * nodeGapX;
    const xOffset = (maxLayerWidth - layerWidth) / 2;

    taskIds.forEach((taskId, index) => {
      positions.push({
        taskId,
        x: padding + xOffset + index * (nodeWidth + nodeGapX),
        y: padding + layer * layerGapY,
        layer,
      });
    });
  });

  return positions;
}

/**
 * Compute SVG edge data (bezier curve endpoints) from task dependencies.
 */
export function computeEdges<T extends LayoutableTask>(
  tasks: T[],
  posMap: Map<string, NodePosition>,
  nodeWidth: number = NODE_WIDTH,
  nodeHeight: number = NODE_HEIGHT
): EdgeData[] {
  const edges: EdgeData[] = [];
  tasks.forEach((task) => {
    const toPos = posMap.get(task.id);
    if (!toPos) return;
    (task.dependsOn ?? []).forEach((depId) => {
      const fromPos = posMap.get(depId);
      if (!fromPos) return;
      edges.push({
        fromId: depId,
        toId: task.id,
        x1: fromPos.x + nodeWidth / 2,
        y1: fromPos.y + nodeHeight,
        x2: toPos.x + nodeWidth / 2,
        y2: toPos.y,
      });
    });
  });
  return edges;
}

/**
 * Compute the total canvas dimensions needed for the given node positions.
 */
export function computeCanvasSize(
  positions: NodePosition[],
  nodeWidth: number = NODE_WIDTH,
  nodeHeight: number = NODE_HEIGHT,
  padding: number = CANVAS_PADDING
): { width: number; height: number } {
  if (positions.length === 0) return { width: 400, height: 300 };
  const maxX = Math.max(...positions.map((p) => p.x)) + nodeWidth + padding;
  const maxY = Math.max(...positions.map((p) => p.y)) + nodeHeight + padding;
  return { width: Math.max(400, maxX), height: Math.max(300, maxY) };
}

/**
 * Generate a unique task ID that doesn't conflict with existing IDs.
 */
export function generateTaskId(existingIds: Set<string>): string {
  let n = 1;
  while (existingIds.has(`task-${n}`)) n++;
  return `task-${n}`;
}
