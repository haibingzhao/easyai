import React from 'react';
import type { Artifact } from '../../types/artifact';

interface TextArtifactProps {
  artifact: Artifact;
}

export const TextArtifact: React.FC<TextArtifactProps> = ({ artifact }) => {
  return (
    <div className="h-full">
      <h4 className="font-medium mb-2">{artifact.title}</h4>
      <pre className="text-sm bg-muted p-4 rounded-md overflow-auto max-h-[calc(100vh-200px)] whitespace-pre-wrap">
        {artifact.content}
      </pre>
    </div>
  );
};
