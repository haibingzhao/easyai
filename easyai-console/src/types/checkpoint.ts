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
  /**
   * false = this entry's diff or author attribution failed, so `filesChanged` is not
   * trustworthy. An empty list then means "unknown", NOT "no files changed" — edits that
   * depend on the rollback scope must be blocked.
   */
  diffsComplete?: boolean;
}

/** `status` of a checkpoint listing: distinct states, never collapsed into an empty array. */
export type CheckpointStatus = 'ok' | 'disabled' | 'failed';

export interface CheckpointListResponse {
  /** ok = query succeeded and the list is complete; disabled = snapshots off; failed = error. */
  status: CheckpointStatus;
  checkpoints: CheckpointInfo[];
  error?: string;
}

/** Client-side state of the checkpoint data. `unknown` is NOT equivalent to `ok`. */
export type CheckpointState = 'unknown' | 'loading' | 'ok' | 'disabled' | 'failed';

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
