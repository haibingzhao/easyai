import { fetchJson } from './api-client';
import type { CategoryResponse } from '@/types/category';

class CategoryService {
  private baseUrl = '/api/system';

  /** Fetch the category taxonomy for the active domain. */
  async getCategories(): Promise<CategoryResponse> {
    return fetchJson<CategoryResponse>(`${this.baseUrl}/categories`);
  }
}

export const categoryService = new CategoryService();
