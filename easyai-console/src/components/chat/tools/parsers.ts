/**
 * Tool output parsing functions
 */

import type { GrepMatch, FileEntry, ParsedToolParams, ToolMessageProps } from './types';

/**
 * Format file path display (pure string operation, no Node.js module dependency).
 * Only performs working-directory relativization; truncation is handled by outer CSS / CopyableText.
 * @param filePath original file path
 * @param workDir project working directory
 * @returns formatted path string
 */
export function formatFilePath(filePath: string, workDir: string = ''): string {
  if (!filePath) return '';

  // Ensure workDir ends with /
  const normalizedWorkDir = workDir.endsWith('/') ? workDir : workDir + '/';

  // If file is under the working directory, convert to relative path (pure string operation)
  if (workDir && filePath.startsWith(normalizedWorkDir)) {
    return './' + filePath.slice(normalizedWorkDir.length);
  }

  return filePath;
}

/**
 * Extract output text from ToolMessage props
 */
export function extractOutput(props: Pick<ToolMessageProps, 'result' | 'streamingOutput'>): string {
  const { streamingOutput, result } = props;
  
  // Prefer streaming output (check if it has actual content)
  if (streamingOutput !== undefined && streamingOutput !== '') {
    return streamingOutput;
  }
  
  if (!result) return '';
  
  // Use parsed content blocks
  if (result.contentBlocks && result.contentBlocks.length > 0) {
    return result.contentBlocks
      .map((block) => {
        if (block.type === 'toolResult') return block.output;
        if (block.type === 'text') return block.text;
        return '';
      })
      .join('');
  }
  
  // Fall back to raw result string
  return result.result;
}

/**
 * Try to parse and format text as JSON; returns null if not valid JSON.
 * Used to automatically switch to CodeBlock syntax highlighting in tool args/output areas.
 */
export function tryFormatJson(text: string): string | null {
  if (!text) return null;
  const trimmed = text.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null;
  try {
    const parsed = JSON.parse(trimmed);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return null;
  }
}

/**
 * Parse grep output (adapted to backend formatted output).
 * Backend output format:
 * - "Found N matches" header
 * - "filepath:" file header line
 * - "  Line N: content" match line
 * - "(Results truncated...)" truncation notice
 */
export function parseGrepOutput(output: string): GrepMatch[] {
  if (!output || output === 'No matches found') return [];

  const matches: GrepMatch[] = [];
  let currentFile = '';

  for (const line of output.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('Found ') || trimmed.startsWith('(Results')) continue;

    // File header line: "filepath:"
    if (trimmed.endsWith(':') && !trimmed.startsWith('Line ')) {
      currentFile = trimmed.slice(0, -1);
      continue;
    }

    // Match line: "Line N: content"
    const lineMatch = trimmed.match(/^Line\s+(\d+):\s*(.*)$/);
    if (lineMatch && currentFile) {
      matches.push({
        filePath: currentFile,
        lineNum: parseInt(lineMatch[1], 10),
        content: lineMatch[2]
      });
    }
  }
  return matches;
}

/**
 * Parse ls output (relative names with [D] prefix)
 */
export function parseLsOutput(output: string): FileEntry[] {
  if (!output) return [];
  
  return output
    .split('\n')
    .filter(line => line.trim())
    .map(line => {
      const isDirectory = line.startsWith('[D] ');
      const name = isDirectory ? line.slice(4) : line.trim();
      return {
        name,
        isDirectory,
        path: name
      };
    });
}

/**
 * Parse glob output (full file paths)
 */
export function parseGlobOutput(output: string): FileEntry[] {
  if (!output || output === 'No files found') return [];
  
  return output
    .split('\n')
    .filter(line => line.trim() && !line.startsWith('('))
    .map(fullPath => {
      const name = fullPath.split('/').pop() || fullPath;
      return {
        name,
        isDirectory: false,
        path: fullPath
      };
    });
}

/**
 * Parse read output (split by lines)
 */
export function parseReadOutput(output: string): { lines: string[]; totalLines: number } {
  if (!output) return { lines: [], totalLines: 0 };
  
  const lines = output.split('\n');
  return {
    lines,
    totalLines: lines.length
  };
}

/**
 * Parse tool arguments
 */
export function parseToolArgs(toolName: string, argsString: string): ParsedToolParams {
  try {
    const parsed = JSON.parse(argsString);
    return { [toolName]: parsed } as ParsedToolParams;
  } catch {
    return {};
  }
}

/**
 * Get the path argument of a tool
 */
export function getToolPath(toolName: string, argsString: string): string {
  const params = parseToolArgs(toolName, argsString);
  const toolParams = params[toolName as keyof ParsedToolParams];
  if (toolParams && 'path' in toolParams) {
    return (toolParams as { path: string }).path || '';
  }
  return '';
}

/**
 * Get the search pattern of the grep tool
 */
export function getGrepPattern(argsString: string): string {
  try {
    const parsed = JSON.parse(argsString);
    return parsed.pattern || '';
  } catch {
    return '';
  }
}

/**
 * Get the search path of a tool
 */
export function getSearchPath(argsString: string): string {
  try {
    const parsed = JSON.parse(argsString);
    return parsed.path || '';
  } catch {
    return '';
  }
}

/**
 * Get the match pattern of the glob tool
 */
export function getGlobPattern(argsString: string): string {
  try {
    const parsed = JSON.parse(argsString);
    return parsed.pattern || '';
  } catch {
    return '';
  }
}