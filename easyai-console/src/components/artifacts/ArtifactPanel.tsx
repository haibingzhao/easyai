import React, { useState } from 'react';
import type { Artifact } from '../../types/artifact';
import { TextArtifact } from './TextArtifact';
import { HtmlArtifact } from './HtmlArtifact';
import { ImageArtifact } from './ImageArtifact';
import { MarkdownArtifact } from './MarkdownArtifact';
import { PdfArtifact } from './PdfArtifact';
import { ExcelArtifact } from './ExcelArtifact';
import { i18n } from '../../utils/i18n';
import { X } from 'lucide-react';

interface ArtifactPanelProps {
  collapsed?: boolean;
  overlay?: boolean;
  onClose?: () => void;
  artifacts?: Map<string, Artifact>;
}

export const ArtifactPanel: React.FC<ArtifactPanelProps> = ({ 
  collapsed = false, 
  overlay = false, 
  onClose,
  artifacts 
}) => {
  const [selectedArtifact, setSelectedArtifact] = useState<string | null>(null);

  if (collapsed) return null;

  const artifactList = artifacts ? Array.from(artifacts.values()) : [];

  if (artifactList.length === 0) {
    return (
      <div className={`h-full bg-background border-l border-border flex flex-col ${overlay ? 'shadow-lg' : ''}`}>
        <div className="flex items-center justify-between p-4 border-b border-border">
          <h3 className="font-semibold">{i18n('Artifacts')}</h3>
          {overlay && (
            <button onClick={onClose} className="p-1 hover:bg-muted rounded-md">
              <X className="w-4 h-4" />
            </button>
          )}
        </div>
        <div className="flex-1 flex items-center justify-center text-muted-foreground">
          {i18n('No artifacts yet')}
        </div>
      </div>
    );
  }

  const selected = selectedArtifact ? artifacts?.get(selectedArtifact) : null;

  return (
    <div className={`h-full bg-background border-l border-border flex flex-col ${overlay ? 'shadow-lg' : ''}`}>
      <div className="flex items-center justify-between p-4 border-b border-border">
        <h3 className="font-semibold">{i18n('Artifacts')}</h3>
        {overlay && (
          <button onClick={onClose} className="p-1 hover:bg-muted rounded-md">
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      <div className="flex-1 flex overflow-hidden">
        {/* Artifact list */}
        <div className="w-48 border-r border-border overflow-y-auto">
          {artifactList.map((artifact) => (
            <button
              key={artifact.id}
              onClick={() => setSelectedArtifact(artifact.id)}
              className={`w-full text-left px-3 py-2 text-sm hover:bg-muted transition-colors border-b border-border ${
                selectedArtifact === artifact.id ? 'bg-muted' : ''
              }`}
            >
              <div className="truncate font-medium">{artifact.title}</div>
              <div className="text-xs text-muted-foreground truncate">{artifact.filename}</div>
            </button>
          ))}
        </div>

        {/* Artifact content */}
        <div className="flex-1 overflow-y-auto p-4">
          {selected && <ArtifactContent artifact={selected} />}
        </div>
      </div>
    </div>
  );
};

const ArtifactContent: React.FC<{ artifact: Artifact }> = ({ artifact }) => {
  const mimeType = artifact.mimeType || '';

  if (mimeType.startsWith('image/')) {
    return <ImageArtifact artifact={artifact} />;
  }
  if (mimeType === 'text/html') {
    return <HtmlArtifact artifact={artifact} />;
  }
  if (mimeType === 'text/markdown' || artifact.filename.endsWith('.md')) {
    return <MarkdownArtifact artifact={artifact} />;
  }
  if (mimeType === 'application/pdf') {
    return <PdfArtifact artifact={artifact} />;
  }
  if (mimeType.includes('excel') || artifact.filename.endsWith('.xlsx')) {
    return <ExcelArtifact artifact={artifact} />;
  }

  return <TextArtifact artifact={artifact} />;
};
