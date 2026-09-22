export interface ChatAttachment {
  name: string;
  mimeType: string;
  /** Stable storage reference or absolute local file path after upload. */
  filePath: string;
}

export interface ChatRequest {
  sessionId?: string;
  projectId?: string;
  message?: string;
  agentId: string;
  modelProviderConfigId?: string;
  model?: string;
  inputData?: Record<string, unknown>;
  attachments?: ChatAttachment[];
}

export interface SessionResponse {
  sessionId: string;
  message?: string;
}

// Re-export for backward compatibility
export type SocketRequest = ChatRequest;