import { authFetch, fetchJson } from './api-client';

export interface KnowledgeEntryDto {
  key: string;
  source: string;
  relativePath: string;
  title: string;
  description: string;
  category: string;
  ext: string;
  contentPreview: string;
  updatedAt: number | null;
  chunksCount: number | null;
}

export interface KnowledgeDetailDto {
  entry: KnowledgeEntryDto;
  fullContent: string;
  toc: string[];
  parent: string | null;
  children: string[];
  related: string[];
}

export interface UploadResultDto {
  relativePath: string;
  success: boolean;
  key: string | null;
  reason: string | null;
}

export interface UploadResponseDto {
  results: UploadResultDto[];
  totalFiles: number;
  successCount: number;
  failedCount: number;
}

export interface KnowledgeArticleDto {
  id: string;
  knowledgeKey: string;
  title: string;
  description: string | null;
  category: string;
  source: string;
  relativePath: string;
  ext: string;
  visibility: string;
  origin: string;
  canWrite: boolean;
  canManage: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface SaveArticleRequest {
  title: string;
  content: string;
  source?: string;
  relativePath?: string;
  category?: string;
  visibility?: string;
  origin?: string;
  originSessionId?: string;
  originMessageId?: string;
  composedFrom?: string;
}

export interface UpdateArticleRequest {
  key: string;
  content: string;
  title?: string;
  category?: string;
}

export interface AiRewriteRequest {
  key: string;
  content?: string;
  instruction: string;
  modelConfigId: string;
}

export interface AiModifySelectionRequest {
  key: string;
  content?: string;
  selectedText: string;
  instruction: string;
  modelConfigId: string;
}

export interface AiResultDto {
  content: string;
}

export interface AiComposeRequest {
  sourceKeys: string[];
  guidance: string;
  style: string;
  modelConfigId: string;
}

export interface KnowledgeAccessDto {
  id: string;
  articleId: string;
  granteeId: string;
  permission: string;
  grantedBy: string;
  grantedAt: number;
}

export interface GrantAccessRequest {
  articleId: string;
  granteeId: string;
  permission: string;
}

export interface UpdateVisibilityRequest {
  articleId: string;
  visibility: string;
}

class KnowledgeService {
  private baseUrl = '/api/knowledge';

  async list(
    source?: string,
    category?: string,
    q?: string
  ): Promise<KnowledgeEntryDto[]> {
    const params = new URLSearchParams();
    if (source) params.set('source', source);
    if (category) params.set('category', category);
    if (q) params.set('q', q);
    const qs = params.toString();
    return fetchJson<KnowledgeEntryDto[]>(`${this.baseUrl}${qs ? `?${qs}` : ''}`);
  }

  async sources(): Promise<string[]> {
    return fetchJson<string[]>(`${this.baseUrl}/sources`);
  }

  async detail(key: string): Promise<KnowledgeDetailDto> {
    return fetchJson<KnowledgeDetailDto>(`${this.baseUrl}/detail?key=${encodeURIComponent(key)}`);
  }

  async deleteEntry(key: string): Promise<{ deleted: boolean }> {
    return fetchJson<{ deleted: boolean }>(`${this.baseUrl}?key=${encodeURIComponent(key)}`, {
      method: 'DELETE',
    });
  }

  async deleteSource(source: string): Promise<{ deleted: number }> {
    return fetchJson<{ deleted: number }>(`${this.baseUrl}/source/${encodeURIComponent(source)}`, {
      method: 'DELETE',
    });
  }

  async saveToKnowledge(request: SaveArticleRequest): Promise<KnowledgeArticleDto> {
    return fetchJson<KnowledgeArticleDto>(`${this.baseUrl}/save-text`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async listArticles(source?: string, category?: string): Promise<KnowledgeArticleDto[]> {
    const params = new URLSearchParams();
    if (source) params.set('source', source);
    if (category) params.set('category', category);
    const qs = params.toString();
    return fetchJson<KnowledgeArticleDto[]>(`${this.baseUrl}/articles${qs ? `?${qs}` : ''}`);
  }

  async getArticleContent(key: string): Promise<string> {
    const result = await fetchJson<{ content: string }>(
      `${this.baseUrl}/article/content?key=${encodeURIComponent(key)}`
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

  async deleteArticle(key: string): Promise<{ deleted: boolean }> {
    return fetchJson<{ deleted: boolean }>(
      `${this.baseUrl}/article?key=${encodeURIComponent(key)}`,
      { method: 'DELETE' }
    );
  }

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

  async listAccess(articleId: string): Promise<KnowledgeAccessDto[]> {
    return fetchJson<KnowledgeAccessDto[]>(`${this.baseUrl}/access/${encodeURIComponent(articleId)}`);
  }

  async grantAccess(request: GrantAccessRequest): Promise<{ success: boolean }> {
    return fetchJson<{ success: boolean }>(`${this.baseUrl}/access`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async revokeAccess(articleId: string, granteeId: string): Promise<{ success: boolean }> {
    return fetchJson<{ success: boolean }>(
      `${this.baseUrl}/access?articleId=${encodeURIComponent(articleId)}&granteeId=${encodeURIComponent(granteeId)}`,
      { method: 'DELETE' }
    );
  }

  async updateVisibility(request: UpdateVisibilityRequest): Promise<{ success: boolean }> {
    return fetchJson<{ success: boolean }>(`${this.baseUrl}/visibility`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  async upload(
    files: File[],
    paths: string[],
    source: string,
    category?: string
  ): Promise<UploadResponseDto> {
    const formData = new FormData();
    for (const file of files) {
      formData.append('files', file);
    }
    formData.append('paths', JSON.stringify(paths));
    formData.append('source', source);
    if (category) {
      formData.append('category', category);
    }
    const response = await authFetch(`${this.baseUrl}/upload`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      throw new Error(`Upload failed: ${response.statusText}`);
    }
    return response.json() as Promise<UploadResponseDto>;
  }
}

export const knowledgeService = new KnowledgeService();
