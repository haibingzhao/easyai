import React, { useMemo } from 'react';
import { ArrowLeft, Crown, User } from 'lucide-react';
import { computeRadialPositions } from '@/utils/radial-layout';
import { i18n } from '@/utils/i18n';

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
}

const NODE_W = 140;
const NODE_H = 60;

/**
 * Radial sub-canvas showing Leader at center and Members around it.
 * Used for both editing (double-click TEAM node in editor) and runtime visualization.
 */
export const TeamSubCanvas: React.FC<TeamSubCanvasProps> = React.memo(({
  teamSpec,
  agents,
  onBack,
  readOnly: _readOnly = false,
}) => {
  const positions = useMemo(
    () => computeRadialPositions(teamSpec.leader, teamSpec.members),
    [teamSpec.leader, teamSpec.members],
  );

  const leaderPos = positions[0];
  const memberPositions = positions.slice(1);

  const getAgent = (id: string) => agents.find((a) => a.id === id);

  return (
    <div className="relative w-full h-full min-h-[400px] bg-muted/30 rounded-lg overflow-hidden">
      {/* Breadcrumb */}
      <button
        type="button"
        onClick={onBack}
        className="absolute top-3 left-3 z-20 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors bg-card/80 rounded px-2 py-1 border border-border"
      >
        <ArrowLeft className="w-3 h-3" />
        {i18n('Back to Workflow')}
      </button>

      {/* SVG connections */}
      <svg className="absolute inset-0 w-full h-full pointer-events-none z-0">
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
        return (
          <div
            key={pos.id}
            className="absolute z-10 flex flex-col items-center justify-center rounded-lg border border-border bg-card shadow-sm hover:shadow-md transition-shadow"
            style={{
              left: pos.x - NODE_W / 2,
              top: pos.y - NODE_H / 2,
              width: NODE_W,
              height: NODE_H,
            }}
          >
            <User className="w-3.5 h-3.5 text-muted-foreground mb-0.5" />
            <span className="text-xs font-medium truncate max-w-[120px]">
              {agent?.role || pos.id}
            </span>
            <span className="text-[10px] text-muted-foreground">Member</span>
          </div>
        );
      })}

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
