/**
 * Types for AI-powered config generation.
 */

/**
 * Request to generate a config via LLM.
 * Uses AgentLoop with tools for multi-step generation with self-validation.
 */
export interface AiConfigGenerateRequest {
  description: string;
  configType: 'agent' | 'swarm';
  modelConfigId?: string;
  existingConfig?: Record<string, unknown>;
}

/**
 * Response from AI config generation.
 */
export interface AiConfigGenerateResponse {
  generatedConfig: Record<string, unknown>;
  validation: ConfigValidationResult;
  explanation: string;
  retryCount: number;
}

/**
 * Result of config validation.
 */
export interface ConfigValidationResult {
  valid: boolean;
  errors: ConfigValidationError[];
}

/**
 * A single validation error or warning.
 */
export interface ConfigValidationError {
  field: string;
  message: string;
  severity: 'error' | 'warning';
}

/**
 * A single configuration block event from chunked generation.
 * Emitted via SSE 'config_block' event during swarm config generation.
 */
export interface ConfigBlockEvent {
  blockType: 'meta' | 'agent' | 'task' | 'variable';
  blockIndex: number;
  data: Record<string, unknown>;
}
