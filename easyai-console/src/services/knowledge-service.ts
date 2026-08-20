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
