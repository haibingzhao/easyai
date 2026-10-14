import React, { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArtifactPanel } from '@/components/artifacts/ArtifactPanel';
import { useChatStore } from '@/services/stores/chat-store';
import { storageService } from '@/services/storage-service';
import type { Artifact } from '@/types/artifact';
import { i18n } from '@/utils/i18n';
import { MobileHeader } from '../components/MobileHeader';

/**
 * Full-screen artifact viewer.
 *
 * Fed from `storageService.getSessionArtifacts`, i.e. exactly the same source the desktop
 * `ArtifactPanel` reads. Note that nothing writes that store today: the backend's `artifact`
 * events only set `hasArtifacts` / `artifactCount`, so both surfaces show the empty state until a
 * producer persists artifact bodies. In-chat visuals (render_visual, media results) are rendered
 * inline by their own tool cards and are unaffected.
 */
export const MobileArtifactPage: React.FC = () => {
  const navigate = useNavigate();
  const sessionId = useChatStore((s) => s.sessionId);

  const artifacts = useMemo(() => {
    if (!sessionId) return new Map<string, Artifact>();
    return new Map(storageService.getSessionArtifacts(sessionId).map((a) => [a.id, a]));
  }, [sessionId]);

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <MobileHeader onBack={() => navigate(-1)} title={i18n('Artifacts')} />
      <div className="flex-1 min-h-0">
        <ArtifactPanel artifacts={artifacts} />
      </div>
    </div>
  );
};
