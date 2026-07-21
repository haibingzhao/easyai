import React from 'react';
import type { Artifact } from '../../types/artifact';

interface PdfArtifactProps {
  artifact: Artifact;
}

export const PdfArtifact: React.FC<PdfArtifactProps> = ({ artifact }) => {
  return (
    <div className="h-full">
      <h4 className="font-medium mb-2">{artifact.title}</h4>
      <div className="text-sm text-muted-foreground">
        PDF preview not implemented. 
        <a
          href={`data:${artifact.mimeType};base64,${artifact.content}`}
          download={artifact.filename}
          className="text-primary hover:underline ml-1"
        >
          Download
        </a>
      </div>
    </div>
  );
};
