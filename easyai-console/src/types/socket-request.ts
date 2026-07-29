export interface ChatAttachment {
  name: string;
  mimeType: string;
  /** Base64-encoded content. Present for clipboard images, absent for local files. */
  data?: string;
  /** Absolute local file path. Present for uploaded files, absent for clipboard images. */
  filePath?: string;
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