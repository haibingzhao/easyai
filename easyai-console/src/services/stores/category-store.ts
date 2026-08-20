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
 * Fallback knowledge categories (coding domain defaults).
 */
const FALLBACK_KNOWLEDGE_CATEGORIES: CategorySpec[] = [
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
  knowledgeCategories: FALLBACK_KNOWLEDGE_CATEGORIES,
  memoryCategories: FALLBACK_MEMORY_CATEGORIES,
  loaded: false,
  loading: false,

  loadCategories: async () => {
    set({ loading: true });
    try {
      const response = await categoryService.getCategories();
      set({
        domain: response.domain,
        knowledgeCategories: response.knowledge,
        memoryCategories: response.memory,
        loaded: true,
        loading: false,
      });
    } catch {
      // Fallback to built-in coding domain categories on any failure
      set({
        domain: 'coding',
        knowledgeCategories: FALLBACK_KNOWLEDGE_CATEGORIES,
        memoryCategories: FALLBACK_MEMORY_CATEGORIES,
        loaded: true,
        loading: false,
      });
    }
  },
}));
