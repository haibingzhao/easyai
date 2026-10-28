import { authFetch, fetchJson } from './api-client';

export type ArticleStatus = 'draft' | 'published';

/**
 * A knowledge article. `id` is the stable identity used for every operation; `folderPath` is a
 * derived display string only — renaming or moving folders never changes the id.
 */
export interface KnowledgeArticleDto {
  id: string;
  folderId: string;
  folderPath: string;
  ownerId: string;
  fileName: string | null;
  title: string;
  description: string | null;
  category: string;
  ext: string;
  origin: string;
  status: ArticleStatus;
  /** Whether the assistant may retrieve this article. */
  ragEnabled: boolean;
  /** Published content saved but the index write failed; publish again to repair. */
  indexPending: boolean;
  canWrite: boolean;
  canManage: boolean;
  createdAt: number;
  updatedAt: number;
}

/** A node of the folder tree; `parentId === null` marks a root. */
export interface KnowledgeFolderDto {
  id: string;
  parentId: string | null;
  name: string;
  ownerId: string;
  isDefault: boolean;
  /** True for the bucket new content lands in; the other visible bucket is read-only for writes. */
  writable: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface UploadResultDto {
  relativePath: string;
  success: boolean;
  id: string | null;
  reason: string | null;
  indexPending: boolean;
}

export interface UploadResponseDto {
  results: UploadResultDto[];
  totalFiles: number;
  successCount: number;
  failedCount: number;
}

export interface SaveArticleRequest {
  title: string;
  content: string;
  folderId?: string;
  category?: string;
  origin?: string;
  originSessionId?: string;
  originMessageId?: string;
  composedFrom?: string;
}

export interface UpdateArticleRequest {
  id: string;
  content: string;
  title?: string;
  category?: string;
}

export interface PublishArticleRequest {
  id: string;
  category?: string;
  title?: string;
}

export interface SetArticleRagRequest {
  id: string;
  enabled: boolean;
}

export interface MoveArticleRequest {
  id: string;
  folderId: string;
}

export interface CreateFolderRequest {
  parentId?: string | null;
  name: string;
}

export interface AiRewriteRequest {
  id: string;
  content?: string;
  instruction: string;
  modelConfigId: string;
}

export interface AiModifySelectionRequest {
  id: string;
  content?: string;
  selectedText: string;
  instruction: string;
  modelConfigId: string;
}

export interface AiComposeRequest {
  sourceIds: string[];
  guidance: string;
  style: string;
  modelConfigId: string;
}

export interface AiResultDto {
  content: string;
}

export interface IndexStatusDto {
  counts: Record<string, number>;
}

class KnowledgeService {
  private baseUrl = '/api/knowledge';

  // ── Articles ──────────────────────────────────────────────────────

  async listArticles(category?: string, status?: ArticleStatus): Promise<KnowledgeArticleDto[]> {
    const params = new URLSearchParams();
    if (category) params.set('category', category);
    if (status) params.set('status', status);
    const qs = params.toString();
    return fetchJson<KnowledgeArticleDto[]>(`${this.baseUrl}/articles${qs ? `?${qs}` : ''}`);
  }

  async saveToKnowledge(request: SaveArticleRequest): Promise<KnowledgeArticleDto> {
    return fetchJson<KnowledgeArticleDto>(`${this.baseUrl}/save-text`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async getArticleContent(id: string): Promise<string> {
    const result = await fetchJson<{ content: string }>(
      `${this.baseUrl}/article/content?id=${encodeURIComponent(id)}`
    );
    return result.content;
  }

  async updateArticle(request: UpdateArticleRequest): Promise<KnowledgeArticleDto> {
    return fetchJson<KnowledgeArticleDto>(`${this.baseUrl}/article`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async publishArticle(request: PublishArticleRequest): Promise<KnowledgeArticleDto> {
    return fetchJson<KnowledgeArticleDto>(`${this.baseUrl}/article/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async unpublishArticle(id: string): Promise<KnowledgeArticleDto> {
    return fetchJson<KnowledgeArticleDto>(
      `${this.baseUrl}/article/unpublish?id=${encodeURIComponent(id)}`,
      { method: 'POST' }
    );
  }

  async setArticleRag(request: SetArticleRagRequest): Promise<KnowledgeArticleDto> {
    return fetchJson<KnowledgeArticleDto>(`${this.baseUrl}/article/rag`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async moveArticle(request: MoveArticleRequest): Promise<KnowledgeArticleDto> {
    return fetchJson<KnowledgeArticleDto>(`${this.baseUrl}/article/move`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async deleteArticle(id: string): Promise<{ deleted: boolean }> {
    return fetchJson<{ deleted: boolean }>(
      `${this.baseUrl}/article?id=${encodeURIComponent(id)}`,
      { method: 'DELETE' }
    );
  }

  async indexStatus(ids: string[]): Promise<IndexStatusDto> {
    const params = new URLSearchParams();
    for (const id of ids) params.append('ids', id);
    return fetchJson<IndexStatusDto>(`${this.baseUrl}/index-status?${params.toString()}`);
  }

  // ── Folders ───────────────────────────────────────────────────────

  async listFolders(): Promise<KnowledgeFolderDto[]> {
    return fetchJson<KnowledgeFolderDto[]>(`${this.baseUrl}/folders`);
  }

  async createFolder(request: CreateFolderRequest): Promise<KnowledgeFolderDto> {
    return fetchJson<KnowledgeFolderDto>(`${this.baseUrl}/folders`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async renameFolder(id: string, name: string): Promise<KnowledgeFolderDto> {
    return fetchJson<KnowledgeFolderDto>(`${this.baseUrl}/folders/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    });
  }

  async deleteFolder(id: string): Promise<{ deleted: boolean }> {
    return fetchJson<{ deleted: boolean }>(
      `${this.baseUrl}/folders/${encodeURIComponent(id)}`,
      { method: 'DELETE' }
    );
  }

  // ── AI ────────────────────────────────────────────────────────────

  async aiRewrite(request: AiRewriteRequest): Promise<AiResultDto> {
    return fetchJson<AiResultDto>(`${this.baseUrl}/ai-rewrite`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async aiModifySelection(request: AiModifySelectionRequest): Promise<AiResultDto> {
    return fetchJson<AiResultDto>(`${this.baseUrl}/ai-modify-selection`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async aiCompose(request: AiComposeRequest): Promise<AiResultDto> {
    return fetchJson<AiResultDto>(`${this.baseUrl}/ai-compose`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  // ── Upload ────────────────────────────────────────────────────────

  async upload(
    files: File[],
    paths: string[],
    folderId?: string,
    category?: string
  ): Promise<UploadResponseDto> {
    const formData = new FormData();
    for (const file of files) {
      formData.append('files', file);
    }
    formData.append('paths', JSON.stringify(paths));
    if (folderId) formData.append('folderId', folderId);
    if (category) formData.append('category', category);
    const response = await authFetch(`${this.baseUrl}/upload`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      // The backend's reason (e.g. "Folder belongs to another space") is far more actionable than
      // the status text; keep it when it is a plain message rather than the JSON error envelope.
      const body = await response.text().catch(() => '');
      const detail = body.trim();
      throw new Error(
        detail && !detail.startsWith('{')
          ? detail
          : `Upload failed: ${response.status} ${response.statusText}`
      );
    }
    return response.json() as Promise<UploadResponseDto>;
  }
}

export const knowledgeService = new KnowledgeService();
