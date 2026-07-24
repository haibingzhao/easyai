export interface NodePosition {
  id: string;
  x: number;
  y: number;
}

/**
 * Compute radial positions for a team sub-canvas.
 * Leader is placed at the center; members are evenly distributed on a circle.
 *
 * @param leaderId The leader agent ID
 * @param memberIds List of member agent IDs
 * @param centerX Center X coordinate (default 400)
 * @param centerY Center Y coordinate (default 300)
 * @returns Array of NodePosition for leader + members
 */
export function computeRadialPositions(
  leaderId: string,
  memberIds: string[],
  centerX = 400,
  centerY = 300,
): NodePosition[] {
  const positions: NodePosition[] = [];

  // Leader at center
  positions.push({ id: leaderId, x: centerX, y: centerY });

  // Members on circle
  const count = memberIds.length;
  if (count === 0) return positions;

  const radius = Math.max(200, count * 60);
  const angleStep = (2 * Math.PI) / count;
  // Start from top (-PI/2) for aesthetic layout
  const startAngle = -Math.PI / 2;

  for (let i = 0; i < count; i++) {
    const angle = startAngle + i * angleStep;
    positions.push({
      id: memberIds[i],
      x: centerX + radius * Math.cos(angle),
      y: centerY + radius * Math.sin(angle),
    });
  }

  return positions;
}
