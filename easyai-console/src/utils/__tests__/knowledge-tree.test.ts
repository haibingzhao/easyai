import { describe, expect, it } from 'vitest';
import type { KnowledgeArticleDto, KnowledgeFolderDto } from '@/services/knowledge-service';
import {
  articlesUnderFolder,
  buildFolderTree,
  collectSubtreeIds,
  folderDisplayPath,
  folderLabel,
  foldersById,
} from '@/utils/knowledge-tree';

function folder(
  id: string,
  parentId: string | null,
  name: string,
  isDefault = false,
): KnowledgeFolderDto {
  return { id, parentId, name, ownerId: 'alice', isDefault, writable: true, createdAt: 1, updatedAt: 1 };
}

function article(id: string, folderId: string): KnowledgeArticleDto {
  return {
    id,
    folderId,
    folderPath: '',
    ownerId: 'alice',
    fileName: null,
    title: id,
    description: null,
    category: 'other',
    ext: 'md',
    origin: 'manual',
    status: 'published',
    ragEnabled: true,
    indexPending: false,
    canWrite: true,
    canManage: true,
    createdAt: 1,
    updatedAt: 1,
  };
}

const tree = [
  folder('root', null, 'Family'),
  folder('recipes', 'root', 'Recipes'),
  folder('work', null, 'Work'),
  folder('deep', 'recipes', 'Weeknight'),
];

describe('buildFolderTree', () => {
  it('nests children under their parents and keeps roots at the top', () => {
    const forest = buildFolderTree(tree);
    expect(forest.map((n) => n.folder.id)).toEqual(['root', 'work']);
    const family = forest[0];
    expect(family.children.map((n) => n.folder.id)).toEqual(['recipes']);
    expect(family.children[0].children.map((n) => n.folder.id)).toEqual(['deep']);
  });

  it('sorts siblings by name', () => {
    const forest = buildFolderTree([
      folder('b', null, 'Beta'),
      folder('a', null, 'Alpha'),
    ]);
    expect(forest.map((n) => n.folder.name)).toEqual(['Alpha', 'Beta']);
  });

  it('drops a self-referencing node instead of recursing forever', () => {
    const forest = buildFolderTree([folder('loop', 'loop', 'Loop')]);
    expect(forest).toEqual([]);
  });
});

describe('collectSubtreeIds', () => {
  it('returns the folder and every descendant', () => {
    expect(collectSubtreeIds('root', tree).sort()).toEqual(['deep', 'recipes', 'root']);
  });

  it('returns just the id for a leaf', () => {
    expect(collectSubtreeIds('work', tree)).toEqual(['work']);
  });
});

describe('folderDisplayPath', () => {
  it('joins ancestor names', () => {
    const byId = foldersById(tree);
    expect(folderDisplayPath('deep', byId)).toBe('Family/Recipes/Weeknight');
    expect(folderDisplayPath('root', byId)).toBe('Family');
  });

  it('is empty for a null or unknown id', () => {
    const byId = foldersById(tree);
    expect(folderDisplayPath(null, byId)).toBe('');
    expect(folderDisplayPath('missing', byId)).toBe('');
  });

  it('uses the supplied label for each ancestor', () => {
    const byId = foldersById(tree);
    expect(folderDisplayPath('deep', byId, (f) => f.name.toUpperCase())).toBe('FAMILY/RECIPES/WEEKNIGHT');
  });
});

describe('folderLabel', () => {
  it('localizes the default folder and leaves named folders untouched', () => {
    const translate = (key: string) => (key === 'Uncategorized' ? '未分类' : key);
    expect(folderLabel(folder('def', null, '未分类', true), translate)).toBe('未分类');
    expect(folderLabel(folder('named', null, 'Family'), translate)).toBe('Family');
  });
});

describe('articlesUnderFolder', () => {
  const articles = [article('a1', 'root'), article('a2', 'deep'), article('a3', 'work')];

  it('returns everything when no folder is selected', () => {
    expect(articlesUnderFolder(articles, null, tree)).toHaveLength(3);
  });

  it('includes descendant folders', () => {
    expect(articlesUnderFolder(articles, 'root', tree).map((a) => a.id).sort()).toEqual(['a1', 'a2']);
  });

  it('excludes other branches', () => {
    expect(articlesUnderFolder(articles, 'work', tree).map((a) => a.id)).toEqual(['a3']);
  });
});
