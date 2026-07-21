import React from 'react';
import type { Artifact } from '../../types/artifact';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface MarkdownArtifactProps {
  artifact: Artifact;
}

export const MarkdownArtifact: React.FC<MarkdownArtifactProps> = ({ artifact }) => {
  return (
    <div className="h-full">
      <h4 className="font-medium mb-2">{artifact.title}</h4>
      <div className="prose prose-sm dark:prose-invert max-w-none p-4 bg-muted rounded-md overflow-auto max-h-[calc(100vh-200px)]">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
          {artifact.content}
        </ReactMarkdown>
      </div>
    </div>
  );
};
