import { create } from 'zustand';
import { categoryService } from '@/services/category-service';
import type { CategorySpec } from '@/types/category';
import { MEMORY_CATEGORIES } from '@/types/memory';

/**
 * Fallback memory categories used when the backend API is unavailable
 * (e.g. old backend without /api/system/categories endpoint).
 */
const FALLBACK_MEMORY_CATEGORIES: CategorySpec[] = MEMORY_CATEGORIES.map((c) => ({
  code: c.apiName,
  labelKey: c.labelKey,
  description: '',
}));

/**
 * Knowledge picker presets, defaulting to the framework's own coding domain.
 *
 * Knowledge categories are not a backend contract: an article's `category` is a free-form tag.
 * A host application replaces this list at startup with `registerKnowledgeCategories` so its
 * own taxonomy never has to live in the shared console.
 */
const CODING_KNOWLEDGE_CATEGORIES: CategorySpec[] = [
  { code: 'overview', labelKey: 'Overview', description: 'General project overview' },
  { code: 'architecture', labelKey: 'Architecture', description: 'Architecture design and module layout' },
  { code: 'tech_stack', labelKey: 'Tech Stack', description: 'Technology stack and dependencies' },
  { code: 'conventions', labelKey: 'Conventions', description: 'Coding conventions and standards' },
  { code: 'setup_commands', labelKey: 'Setup & Commands', description: 'Environment setup and build commands' },
  { code: 'other', labelKey: 'Other', description: 'Uncategorised documents' },
];

interface CategoryStore {
  domain: string;
  knowledgeCategories: CategorySpec[];
  memoryCategories: CategorySpec[];
  loaded: boolean;
  loading: boolean;

  /** Load categories from the backend. Falls back to built-in defaults on failure. */
  loadCategories: () => Promise<void>;
}

export const useCategoryStore = create<CategoryStore>((set) => ({
  domain: 'coding',
  knowledgeCategories: CODING_KNOWLEDGE_CATEGORIES,
  memoryCategories: FALLBACK_MEMORY_CATEGORIES,
  loaded: false,
  loading: false,

  loadCategories: async () => {
    set({ loading: true });
    try {
      const response = await categoryService.getCategories();
      set({
        domain: response.domain,
        memoryCategories: response.memory,
        loaded: true,
        loading: false,
      });
    } catch {
      // Fallback to built-in coding domain categories on any failure
      set({
        domain: 'coding',
        memoryCategories: FALLBACK_MEMORY_CATEGORIES,
        loaded: true,
        loading: false,
      });
    }
  },
}));

/**
 * Replace the knowledge picker's categories. Call once at host startup, before rendering any
 * knowledge surface; the backend never supplies these, it only stores the tag verbatim.
 */
export function registerKnowledgeCategories(categories: CategorySpec[]): void {
  useCategoryStore.setState({ knowledgeCategories: categories });
}

/**
 * Display label (an i18n key) of a memory category code.
 *
 * Resolution order is the backend taxonomy first, then the coding fallback, then the raw code:
 * an entry written under another application's taxonomy still has to render as something a
 * reader can identify rather than as a blank.
 */
export function memoryCategoryLabelKey(code: string): string {
  const { memoryCategories } = useCategoryStore.getState();
  const dynamic = memoryCategories.find((c) => c.code === code)?.labelKey;
  if (dynamic) return dynamic;
  return MEMORY_CATEGORIES.find((c) => c.apiName === code)?.labelKey ?? code;
}
