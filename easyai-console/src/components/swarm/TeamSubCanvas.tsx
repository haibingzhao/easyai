import React, { useMemo, useRef, useState, useLayoutEffect, useCallback } from 'react';
import { ArrowLeft, Crown, User, Loader2, CheckCircle2, AlertTriangle, PauseCircle, ArrowRightLeft, XCircle, PlayCircle } from 'lucide-react';
import { computeRadialPositions, computeRadialRadius } from '@/utils/radial-layout';
import { i18n } from '@/utils/i18n';
import type { MemberStatusDto } from '@/services/swarm-service';

/** Minimal team spec needed for radial visualization */
export interface TeamSpecMinimal {
  leader: string;
  members: string[];
}

/** Minimal agent info needed for node labels */
interface AgentMinimal {
  id: string;
  role?: string;
}

interface TeamSubCanvasProps {
  teamSpec: TeamSpecMinimal;
  agents: AgentMinimal[];
  onBack: () => void;
  /** Read-only mode (runtime canvas) vs editable mode */
  readOnly?: boolean;
  /** Runtime status per member (latest execution). When provided, nodes are colored by status. */
  memberStatuses?: Record<string, MemberStatusDto>;
}

const NODE_W = 140;
const NODE_H = 60;

/** Visual style per member runtime status. */
const MEMBER_STATUS_STYLE: Record<MemberStatusDto, { border: string; icon: React.ReactNode; label: string; labelColor: string }> = {
  RUNNING: {
    border: 'border-blue-400',
    icon: <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />,
    label: 'Running',
    labelColor: 'text-blue-500',
  },
  COMPLETED: {
    border: 'border-green-400',
    icon: <CheckCircle2 className="w-3.5 h-3.5 text-green-500" />,
    label: 'Completed',
    labelColor: 'text-green-500',
  },
  ESCALATED: {
    border: 'border-orange-400',
    icon: <AlertTriangle className="w-3.5 h-3.5 text-orange-500" />,
    label: 'Escalated',
    labelColor: 'text-orange-500',
  },
  ERROR: {
    border: 'border-red-400',
    icon: <XCircle className="w-3.5 h-3.5 text-red-500" />,
    label: 'Error',
    labelColor: 'text-red-500',
  },
  SUSPENDED: {
    border: 'border-amber-400',
    icon: <PauseCircle className="w-3.5 h-3.5 text-amber-500" />,
    label: 'Suspended',
    labelColor: 'text-amber-500',
  },
  RESUMED: {
    border: 'border-teal-400',
    icon: <PlayCircle className="w-3.5 h-3.5 text-teal-500" />,
    label: 'Resumed',
    labelColor: 'text-teal-500',
  },
  REASSIGNED: {
    border: 'border-gray-400',
    icon: <ArrowRightLeft className="w-3.5 h-3.5 text-gray-500" />,
    label: 'Reassigned',
    labelColor: 'text-gray-500',
  },
};

/**
 * Radial sub-canvas showing Leader at center and Members around it.
 * Used for both editing (double-click TEAM node in editor) and runtime visualization.
 */
export const TeamSubCanvas: React.FC<TeamSubCanvasProps> = React.memo(({
  teamSpec,
  agents,
  onBack,
  readOnly: _readOnly = false,
  memberStatuses,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);

  // World is sized to fit the whole ring; leader sits at world center.
  const radius = computeRadialRadius(teamSpec.members.length);
  const worldSize = 2 * (radius + NODE_W);
  const center = worldSize / 2;

  const positions = useMemo(
    () => computeRadialPositions(teamSpec.leader, teamSpec.members, center, center),
    [teamSpec.leader, teamSpec.members, center],
  );

  const leaderPos = positions[0];
  const memberPositions = positions.slice(1);

  const getAgent = (id: string) => agents.find((a) => a.id === id);

  // --- Drag to pan the canvas ---
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [isPanning, setIsPanning] = useState(false);
  const panStartRef = useRef({ mouseX: 0, mouseY: 0, panX: 0, panY: 0 });
  const centeredRef = useRef(false);

  // Center the leader in the viewport on first mount
  useLayoutEffect(() => {
    if (centeredRef.current) return;
    const el = containerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    setPan({ x: rect.width / 2 - center, y: rect.height / 2 - center });
    centeredRef.current = true;
  }, [center]);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    setIsPanning(true);
    panStartRef.current = { mouseX: e.clientX, mouseY: e.clientY, panX: pan.x, panY: pan.y };
  }, [pan]);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (!isPanning) return;
    const dx = e.clientX - panStartRef.current.mouseX;
    const dy = e.clientY - panStartRef.current.mouseY;
    setPan({ x: panStartRef.current.panX + dx, y: panStartRef.current.panY + dy });
  }, [isPanning]);

  const handleMouseUp = useCallback(() => setIsPanning(false), []);

  return (
    <div
      ref={containerRef}
      className={[
        'relative w-full h-full min-h-[400px] bg-muted/30 rounded-lg overflow-hidden select-none',
        isPanning ? 'cursor-grabbing' : 'cursor-grab',
      ].join(' ')}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
    >
      {/* Breadcrumb */}
      <button
        type="button"
        onClick={onBack}
        onMouseDown={(e) => e.stopPropagation()}
        className="absolute top-3 left-3 z-30 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors bg-card/80 rounded px-2 py-1 border border-border"
      >
        <ArrowLeft className="w-3 h-3" />
        {i18n('Back to Workflow')}
      </button>

      {/* Pannable world (leader + members + connections) */}
      <div
        className="absolute z-0"
        style={{
          width: worldSize,
          height: worldSize,
          transform: `translate(${pan.x}px, ${pan.y}px)`,
        }}
      >
        {/* SVG connections */}
        <svg width={worldSize} height={worldSize} className="absolute inset-0 pointer-events-none z-0">
          {memberPositions.map((pos) => (
            <line
              key={`line-${pos.id}`}
              x1={leaderPos.x}
              y1={leaderPos.y}
              x2={pos.x}
              y2={pos.y}
              className="stroke-border"
              strokeWidth={1.5}
              strokeDasharray="4 2"
            />
          ))}
        </svg>

        {/* Leader node */}
        <div
          className="absolute z-10 flex flex-col items-center justify-center rounded-lg border-2 border-primary/50 bg-card shadow-sm"
          style={{
            left: leaderPos.x - NODE_W / 2,
            top: leaderPos.y - NODE_H / 2,
            width: NODE_W,
            height: NODE_H,
          }}
        >
          <Crown className="w-4 h-4 text-primary mb-0.5" />
          <span className="text-xs font-medium truncate max-w-[120px]">
            {getAgent(teamSpec.leader)?.role || teamSpec.leader}
          </span>
          <span className="text-[10px] text-muted-foreground">Leader</span>
        </div>

      {/* Member nodes */}
      {memberPositions.map((pos) => {
        const agent = getAgent(pos.id);
        const status = memberStatuses?.[pos.id];
        const style = status ? MEMBER_STATUS_STYLE[status] : null;
        return (
          <div
            key={pos.id}
            className={[
              'absolute z-10 flex flex-col items-center justify-center rounded-lg border bg-card shadow-sm hover:shadow-md transition-shadow',
              style ? style.border : 'border-border',
            ].join(' ')}
            style={{
              left: pos.x - NODE_W / 2,
              top: pos.y - NODE_H / 2,
              width: NODE_W,
              height: NODE_H,
            }}
          >
            {style ? style.icon : <User className="w-3.5 h-3.5 text-muted-foreground mb-0.5" />}
            <span className="text-xs font-medium truncate max-w-[120px]">
              {agent?.role || pos.id}
            </span>
            <span className={`text-[10px] ${style ? style.labelColor : 'text-muted-foreground'}`}>
              {style ? i18n(style.label) : 'Member'}
            </span>
          </div>
        );
      })}
      </div>

      {/* Status legend (runtime mode only) */}
      {memberStatuses && Object.keys(memberStatuses).length > 0 && (
        <div className="absolute bottom-3 left-3 z-20 flex items-center gap-3 bg-card/80 border border-border rounded px-2 py-1">
          {(Object.entries(MEMBER_STATUS_STYLE) as [MemberStatusDto, typeof MEMBER_STATUS_STYLE[MemberStatusDto]][]).map(([key, s]) => (
            <span key={key} className="flex items-center gap-1 text-[10px] text-muted-foreground">
              <span className={s.labelColor}>{s.icon}</span>
              {i18n(s.label)}
            </span>
          ))}
        </div>
      )}

      {/* Empty state */}
      {teamSpec.members.length === 0 && (
        <div className="absolute inset-0 flex items-center justify-center text-sm text-muted-foreground">
          {i18n('No members configured')}
        </div>
      )}
    </div>
  );
});

TeamSubCanvas.displayName = 'TeamSubCanvas';
