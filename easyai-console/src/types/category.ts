/**
 * Category specification returned by the backend /api/system/categories endpoint.
 */
export interface CategorySpec {
  code: string;
  labelKey: string;
  description: string;
}

/**
 * Full category response from the backend.
 */
export interface CategoryResponse {
  domain: string;
  knowledge: CategorySpec[];
  memory: CategorySpec[];
}
