/** File diff information from the backend */
export interface FileDiff {
  path: string;
  patch?: string;
  additions: number;
  deletions: number;
  status: 'added' | 'modified' | 'deleted' | 'renamed';
  changedBy?: 'llm' | 'user';
  /** Team member ID when change was made by a member agent */
  memberId?: string;
}

/** Per-file change info within a checkpoint */
export interface FileChangeInfo {
  path: string;
  additions: number;
  deletions: number;
  status: 'added' | 'modified' | 'deleted' | 'renamed';
  changedBy?: 'llm' | 'user';
  /** Team member ID when change was made by a member agent */
  memberId?: string;
}

/** Checkpoint info for a single turn */
export interface CheckpointInfo {
  /** User message ID for revert API calls (null for tool-level checkpoints) */
  messageId?: string;
  /** Assistant message ID for store key lookup */
  assistantMessageId?: string;
  snapshotHash?: string;
  filesChanged: FileChangeInfo[];
  additions: number;
  deletions: number;
  createdAt: number;
}

/** Revert state from the backend */
export interface RevertStateInfo {
  messageId: string;
  commitHash?: string;
  additions: number;
  deletions: number;
  filesCount: number;
  timestamp: number;
}

/** Response from revert API */
export interface RevertResponse {
  messageId: string;
  filesCount: number;
  additions: number;
  deletions: number;
}

/** Response from unrevert API */
export interface UnrevertResponse {
  messageId: string;
  filesCount: number;
  additions: number;
  deletions: number;
}

/** File change item for the FileChangesPanel */
export interface FileChangeItem {
  path: string;
  status: 'added' | 'modified' | 'deleted' | 'renamed';
  additions?: number;
  deletions?: number;
  reviewStatus: 'pending' | 'accepted' | 'rejected' | 'applied';
  changedBy?: 'llm' | 'user';
  hasBothAuthors?: boolean;
  /** Team member ID when change was made by a member agent */
  memberId?: string;
}

/** Panel state for file changes */
export type FileChangesPanelState = 'generating' | 'pending_review' | 'applied';

/** Response from file accept/reject API */
export interface FileReviewResponse {
  path: string;
  action: 'accepted' | 'rejected';
}

/** Response from batch file accept/reject API */
export interface BatchFileReviewResponse {
  results: FileReviewResponse[];
}

/** Response from file review state query API */
export interface FileReviewStateResponse {
  reviews: Record<string, 'accepted' | 'rejected'>;
}

/** Response from edit message API */
export interface EditMessageResponse {
  deletedMessageCount: number;
  rollback: { filesCount: number; additions: number; deletions: number } | null;
}

/** Commit history entry for per-commit view */
export interface CommitChangeInfo {
  commitHash: string;
  author: 'llm' | 'user';
  message: string;
  timestamp: number;
  files: FileDiff[];
  /** Agent ID that made this commit (LLM commits only; undefined for user/legacy commits). */
  agentId?: string;
}
