// MCP (Model Context Protocol) type definitions

export interface McpServerConfig {
  name: string
  type: 'local' | 'remote'
  command?: string[]
  env?: Record<string, string>
  url?: string
  headers?: Record<string, string>
  timeoutSeconds?: number
  enabled: boolean
}

export type McpServerStatus = 'connected' | 'disabled' | 'failed' | 'connecting'

export interface McpToolInfo {
  name: string
  description: string
}

export interface McpPromptArgument {
  name: string
  description?: string
  required?: boolean
}

export interface McpPromptInfo {
  name: string
  description: string | null
  arguments: McpPromptArgument[]
}

export interface McpServerDto extends McpServerConfig {
  status: McpServerStatus
  error?: string
  tools: McpToolInfo[]
  prompts: McpPromptInfo[]
}

export interface McpServerCreateRequest {
  name: string
  type: 'local' | 'remote'
  command?: string[]
  env?: Record<string, string>
  url?: string
  headers?: Record<string, string>
  timeoutSeconds?: number
  enabled: boolean
}

/** Bulk import format matching Claude Desktop / Cursor JSON config */
export interface McpBulkImportRequest {
  mcpServers: Record<string, {
    command?: string[]
    args?: string[]
    env?: Record<string, string | null>
    url?: string
    headers?: Record<string, string | null>
    type?: string
    timeoutSeconds?: number
  }>
}
