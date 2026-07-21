import React from 'react';
import type { Artifact } from '../../types/artifact';

interface ImageArtifactProps {
  artifact: Artifact;
}

export const ImageArtifact: React.FC<ImageArtifactProps> = ({ artifact }) => {
  return (
    <div className="h-full">
      <h4 className="font-medium mb-2">{artifact.title}</h4>
      <div className="flex items-center justify-center">
        <img
          src={`data:${artifact.mimeType};base64,${artifact.content}`}
          alt={artifact.title}
          className="max-w-full max-h-[calc(100vh-200px)] object-contain"
        />
      </div>
    </div>
  );
};
