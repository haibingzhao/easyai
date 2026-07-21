import React from 'react';
import type { Artifact } from '../../types/artifact';

interface HtmlArtifactProps {
  artifact: Artifact;
}

export const HtmlArtifact: React.FC<HtmlArtifactProps> = ({ artifact }) => {
  return (
    <div className="h-full">
      <h4 className="font-medium mb-2">{artifact.title}</h4>
      <iframe
        srcDoc={artifact.content}
        className="w-full h-[calc(100vh-200px)] border border-border rounded-md bg-white"
        sandbox="allow-scripts"
      />
    </div>
  );
};
