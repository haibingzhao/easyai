/**
 * Shared ReactMarkdown component overrides for code syntax highlighting.
 * Uses Shiki-powered CodeBlock for fenced code blocks.
 */

import React from 'react';
import { CodeBlock } from './CodeBlock';

/**
 * ReactMarkdown `components` prop that intercepts <pre><code> blocks
 * and renders them with Shiki syntax highlighting via CodeBlock.
 *
 * Usage:
 *   <ReactMarkdown components={markdownCodeComponents}>...</ReactMarkdown>
 */
export const markdownCodeComponents = {
  pre({ children, node: _node, ...props }: React.ComponentPropsWithoutRef<'pre'> & { node?: unknown }) {
    let codeContent: string | null = null;
    let language: string | null = null;

    // Case 1: children is a code element
    const codeElement = React.Children.toArray(children)[0];
    if (React.isValidElement(codeElement)) {
      const { className, children: codeChildren } = codeElement.props as {
        className?: string;
        children?: React.ReactNode;
      };
      const match = /language-(\w+)/.exec(className || '');
      if (match) {
        language = match[1];
        codeContent = String(codeChildren);
      }
    }
    // Case 2: children is directly text (fallback)
    else if (typeof children === 'string') {
      codeContent = children;
    }

    // If we have code content, render CodeBlock
    if (codeContent) {
      const className = language ? `language-${language}` : undefined;
      return (
        <CodeBlock className={className}>
          {codeContent.replace(/\n$/, '')}
        </CodeBlock>
      );
    }

    // For non-code pre blocks, render normally
    return <pre {...props}>{children}</pre>;
  },

  code({ className, children, ...props }: React.ComponentPropsWithoutRef<'code'>) {
    const match = /language-(\w+)/.exec(className || '');
    // Inline code only (block code is handled by pre component)
    if (!match) {
      return (
        <code className={className} {...props}>
          {children}
        </code>
      );
    }
    // Block code should be handled by pre, but just in case
    return <>{children}</>;
  },
};
