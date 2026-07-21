import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { useSwarmStore } from '@/services/stores/swarm-store';
import { DAGCanvas } from '@/components/workflow/DAGCanvas';

export const WorkflowRunPage: React.FC = () => {
  const { name } = useParams<{ name: string }>();
  const navigate = useNavigate();
  const { presets, loading, loadPresets } = useSwarmStore();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (presets.length === 0) {
      loadPresets().then(() => setReady(true));
    } else {
      setReady(true);
    }
  }, [loadPresets, presets.length]);

  if (loading || !ready) {
    return (
      <div className="w-full h-full flex items-center justify-center">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  const decodedName = name ? decodeURIComponent(name) : '';
  const preset = presets.find(p => p.name === decodedName);

  if (!preset) {
    return (
      <div className="w-full h-full flex items-center justify-center text-muted-foreground text-sm">
        Preset not found: {decodedName}
      </div>
    );
  }

  return <DAGCanvas preset={preset} onBack={() => navigate('/workflow')} />;
};
