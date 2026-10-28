import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { CategorySpec } from '@/types/category';

vi.mock('@/services/category-service', () => ({
  categoryService: {
    getCategories: vi.fn(),
  },
}));

import { useCategoryStore, memoryCategoryLabelKey, registerKnowledgeCategories } from '../category-store';
import { categoryService } from '@/services/category-service';

const tradingCategories: CategorySpec[] = [
  { code: 'user_preferences', labelKey: 'User Preferences', description: '' },
  { code: 'market_insights', labelKey: 'Market Insights', description: '' },
  { code: 'other', labelKey: 'Other', description: '' },
];

describe('useCategoryStore', () => {
  beforeEach(() => {
    useCategoryStore.setState({
      domain: 'coding',
      memoryCategories: [],
      knowledgeCategories: [],
      loaded: false,
      loading: false,
    });
    vi.mocked(categoryService.getCategories).mockReset();
  });

  it('takes the memory taxonomy from the backend', async () => {
    vi.mocked(categoryService.getCategories).mockResolvedValue({
      domain: 'trading',
      memory: tradingCategories,
    });

    await useCategoryStore.getState().loadCategories();

    const state = useCategoryStore.getState();
    expect(state.domain).toBe('trading');
    expect(state.memoryCategories.map((c) => c.code)).toEqual([
      'user_preferences',
      'market_insights',
      'other',
    ]);
  });

  it('labels a category from the loaded taxonomy, and falls back to the code', () => {
    useCategoryStore.setState({ memoryCategories: tradingCategories });

    expect(memoryCategoryLabelKey('market_insights')).toBe('Market Insights');
    // Another application's taxonomy: the code is all a reader has, and it is still honest.
    expect(memoryCategoryLabelKey('household_rules')).toBe('household_rules');
  });

  it('labels from the coding fallback when nothing has loaded', () => {
    expect(memoryCategoryLabelKey('experience_lessons')).toBe('Experience & Lessons');
  });

  it('never lets a failed load wipe the taxonomy', async () => {
    vi.mocked(categoryService.getCategories).mockRejectedValue(new Error('backend down'));

    await useCategoryStore.getState().loadCategories();

    expect(useCategoryStore.getState().memoryCategories.map((c) => c.code)).toContain(
      'development_standards',
    );
  });

  it('lets a host replace the knowledge presets', () => {
    const homeKnowledge: CategorySpec[] = [
      { code: 'family', labelKey: 'Family', description: '' },
      { code: 'other', labelKey: 'Other', description: '' },
    ];

    registerKnowledgeCategories(homeKnowledge);

    expect(useCategoryStore.getState().knowledgeCategories).toEqual(homeKnowledge);
  });
});
