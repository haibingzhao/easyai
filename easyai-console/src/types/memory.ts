export interface MemoryEntry {
  name: string;
  description: string;
  type: string;
  scope: string;
  content: string;
  keywords: string[];
  created?: string;
  updated?: string;
}

export interface MemoryConfig {
  enabled: boolean;
  globalDir: string;
  projectDir: string;
}

export interface CreateMemoryRequest {
  name: string;
  description: string;
  type: string;
  scope: string;
  content: string;
  keywords: string[];
}
