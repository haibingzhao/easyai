/**
 * Category taxonomy served by the backend's `/api/system/categories`.
 *
 * Memory categories are a storage contract: the backend validates every write against the
 * active domain's list, so the UI cannot offer a category the deployment does not accept.
 * Knowledge categories are deliberately absent — article `category` is a free-form tag, and
 * the knowledge picker is a host-console preset (see `registerKnowledgeCategories`).
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
  memory: CategorySpec[];
}
