import type { KnowledgeArticleDto, KnowledgeFolderDto } from '@/services/knowledge-service';

/** A folder plus its nested children, ready to render as a tree. */
export interface FolderTreeNode {
  folder: KnowledgeFolderDto;
  children: FolderTreeNode[];
}

/**
 * Build the forest of root folders (`parentId === null`), children sorted by name.
 *
 * A `seen` guard drops any node whose parent chain loops, so a corrupt row can never recurse
 * forever — the backend already avoids cycles, this is belt-and-braces for a partial refresh.
 */
export function buildFolderTree(folders: KnowledgeFolderDto[]): FolderTreeNode[] {
  const byParent = new Map<string | null, KnowledgeFolderDto[]>();
  for (const folder of folders) {
    const key = folder.parentId ?? null;
    const siblings = byParent.get(key);
    if (siblings) siblings.push(folder);
    else byParent.set(key, [folder]);
  }

  const build = (parentId: string | null, seen: Set<string>): FolderTreeNode[] => {
    const children = (byParent.get(parentId) ?? [])
      .filter((folder) => !seen.has(folder.id))
      .sort((a, b) => a.name.localeCompare(b.name));
    return children.map((folder) => {
      const next = new Set(seen);
      next.add(folder.id);
      return { folder, children: build(folder.id, next) };
    });
  };

  return build(null, new Set<string>());
}

/** The given folder's id plus every descendant id. */
export function collectSubtreeIds(folderId: string, folders: KnowledgeFolderDto[]): string[] {
  const childrenOf = new Map<string, string[]>();
  for (const folder of folders) {
    if (folder.parentId == null) continue;
    const list = childrenOf.get(folder.parentId);
    if (list) list.push(folder.id);
    else childrenOf.set(folder.parentId, [folder.id]);
  }

  const out: string[] = [];
  const seen = new Set<string>();
  const stack = [folderId];
  while (stack.length > 0) {
    const id = stack.pop() as string;
    if (seen.has(id)) continue;
    seen.add(id);
    out.push(id);
    for (const child of childrenOf.get(id) ?? []) stack.push(child);
  }
  return out;
}

/**
 * Display name for one folder. The default folder is persisted under a fixed name, so it is shown
 * through the active locale instead of the stored string.
 */
export function folderLabel(
  folder: KnowledgeFolderDto,
  translate: (key: string) => string,
): string {
  return folder.isDefault ? translate('Uncategorized') : folder.name;
}

/** Display path (`Family/Recipes`) derived from the ancestor chain; `''` for null/unknown. */
export function folderDisplayPath(
  folderId: string | null,
  foldersById: Map<string, KnowledgeFolderDto>,
  label: (folder: KnowledgeFolderDto) => string = (folder) => folder.name,
): string {
  const parts: string[] = [];
  const seen = new Set<string>();
  let current = folderId;
  while (current && !seen.has(current)) {
    seen.add(current);
    const folder = foldersById.get(current);
    if (!folder) break;
    parts.unshift(label(folder));
    current = folder.parentId;
  }
  return parts.join('/');
}

/** Index folders by id for O(1) path/parent lookups. */
export function foldersById(folders: KnowledgeFolderDto[]): Map<string, KnowledgeFolderDto> {
  const map = new Map<string, KnowledgeFolderDto>();
  for (const folder of folders) map.set(folder.id, folder);
  return map;
}

/**
 * Articles visible under a selected folder: everything when `folderId` is null, otherwise the
 * folder's subtree. Matching is on `folderId`, so a folder rename never changes the result.
 */
export function articlesUnderFolder(
  articles: KnowledgeArticleDto[],
  folderId: string | null,
  folders: KnowledgeFolderDto[],
): KnowledgeArticleDto[] {
  if (folderId == null) return articles;
  const subtree = new Set(collectSubtreeIds(folderId, folders));
  return articles.filter((article) => subtree.has(article.folderId));
}
