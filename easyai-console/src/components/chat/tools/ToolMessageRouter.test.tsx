import { describe, expect, it } from 'vitest';
import type { ReactElement } from 'react';
import { BashToolMessage } from './BashToolMessage';
import { GenericToolMessage } from './GenericToolMessage';
import { McpToolCard } from './McpToolCard';
import { registerToolRenderers, ToolMessageRouter } from './ToolMessageRouter';
import type { ToolMessageProps } from './types';

function props(toolName: string): ToolMessageProps {
  return {
    toolCall: { id: 'call-1', toolName, args: '{}' },
    status: 'COMPLETED',
  };
}

function rendererOf(toolName: string): unknown {
  return (ToolMessageRouter(props(toolName)) as ReactElement).type;
}

describe('ToolMessageRouter', () => {
  it('routes built-in tool names to their renderers', () => {
    expect(rendererOf('bash')).toBe(BashToolMessage);
  });

  it('falls back to the generic card for unknown tools', () => {
    expect(rendererOf('no_such_tool')).toBe(GenericToolMessage);
  });

  it('sends MCP tool names to the MCP card', () => {
    expect(rendererOf('futu__get_quote')).toBe(McpToolCard);
  });

  it('lets a host renderer take over an unregistered name, including an MCP-shaped one', () => {
    function HostCard() {
      return null;
    }

    registerToolRenderers({
      host_tool: HostCard,
      'host_server__tool': HostCard,
    });

    expect(rendererOf('host_tool')).toBe(HostCard);
    expect(rendererOf('host_server__tool')).toBe(HostCard);
  });
});
