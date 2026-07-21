import { useState, useEffect, useRef, useCallback } from 'react';
import { useProjectStore } from '@/services/stores/project-store';
import { useChatStore } from '@/services/stores/chat-store';
import { ChevronDown, Search, FolderPlus, X, FolderOpen } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { DirectoryBrowser } from './DirectoryBrowser';
import type { Project } from '@/services/project-service';

export function ProjectSelector() {
  const { currentProject, selectProject, loadRecentProjects, searchProjects } = useProjectStore();
  const { isStreaming, isAwaitingAskQuestion } = useChatStore();
  const isLocked = isStreaming || isAwaitingAskQuestion();

  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<Project[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setShowCreateForm(false);
        setSearchQuery('');
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Focus search input when dropdown opens
  useEffect(() => {
    if (isOpen && !showCreateForm) {
      searchInputRef.current?.focus();
    }
  }, [isOpen, showCreateForm]);

  // Load recent projects when dropdown opens
  useEffect(() => {
    if (isOpen && !showCreateForm && !searchQuery) {
      loadRecentProjects(10);
    }
  }, [isOpen, showCreateForm, searchQuery, loadRecentProjects]);

  // Debounced search
  const handleSearchChange = useCallback((value: string) => {
    setSearchQuery(value);
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = setTimeout(async () => {
      if (value.trim()) {
        setIsSearching(true);
        const results = await searchProjects(value.trim());
        setSearchResults(results);
        setIsSearching(false);
      } else {
        setSearchResults([]);
      }
    }, 200);
  }, [searchProjects]);

  const handleSelect = (project: Project) => {
    selectProject(project);
    setIsOpen(false);
    setShowCreateForm(false);
    setSearchQuery('');
  };

  const displayedProjects = searchQuery.trim() ? searchResults : searchResults.length > 0 ? searchResults : (useProjectStore.getState().projects);

  const toggleOpen = () => {
    if (!isOpen) {
      setIsOpen(true);
    } else {
      setIsOpen(false);
      setShowCreateForm(false);
      setSearchQuery('');
    }
  };

  return (
    <div ref={containerRef} className="relative">
      {/* Trigger button */}
      <button
        onClick={toggleOpen}
        disabled={isLocked}
        className="flex items-center gap-2 px-3 py-1.5 rounded-md hover:bg-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed max-w-[200px]"
        title={isLocked ? i18n('Outputting, please wait...') : i18n('Switch Project')}
      >
        <div className="flex-1 min-w-0 text-left">
          <div className="text-sm font-medium truncate">
            {currentProject?.name ?? i18n('Select Project')}
          </div>
        </div>
        <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />
      </button>

      {/* Dropdown */}
      {isOpen && (
        <div className="absolute top-full right-0 mt-1 w-72 bg-popover border border-border rounded-md shadow-lg z-50">
          {showCreateForm ? (
            <CreateProjectForm
              onSuccess={() => {
                setShowCreateForm(false);
                setIsOpen(false);
                loadRecentProjects(10);
              }}
              onCancel={() => setShowCreateForm(false)}
            />
          ) : (
            <>
              {/* Search box */}
              <div className="p-2 border-b border-border">
                <div className="relative">
                  <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
                  <input
                    ref={searchInputRef}
                    type="text"
                    value={searchQuery}
                    onChange={(e) => handleSearchChange(e.target.value)}
                    placeholder={i18n('Search projects...')}
                    className="w-full pl-8 pr-8 py-1.5 text-sm bg-transparent border border-border rounded-md focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                  {searchQuery && (
                    <button
                      onClick={() => handleSearchChange('')}
                      className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    >
                      <X className="w-3 h-3" />
                    </button>
                  )}
                </div>
              </div>

              {/* Project list */}
              <div className="max-h-64 overflow-y-auto">
                {isSearching ? (
                  <div className="p-4 text-center text-sm text-muted-foreground">
                    {i18n('Searching...')}
                  </div>
                ) : displayedProjects.length === 0 && searchQuery ? (
                  <div className="p-4 text-center text-sm text-muted-foreground">
                    {i18n('No projects found')}
                  </div>
                ) : displayedProjects.length === 0 ? (
                  <div className="p-4 text-center text-sm text-muted-foreground">
                    {i18n('No projects yet')}
                  </div>
                ) : (
                  <ul className="py-1">
                    {displayedProjects.map((project) => (
                      <li
                        key={project.id}
                        onClick={() => handleSelect(project)}
                        className={`px-3 py-2 cursor-pointer hover:bg-muted transition-colors ${
                          currentProject?.id === project.id ? 'bg-accent' : ''
                        }`}
                      >
                        <div className="text-sm font-medium truncate">
                          {project.name}
                        </div>
                        <div className="text-xs text-muted-foreground truncate">
                          {project.path}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              {/* Create new project button */}
              <div className="border-t border-border">
                <button
                  onClick={() => setShowCreateForm(true)}
                  className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-muted transition-colors"
                >
                  <FolderPlus className="w-4 h-4" />
                  {i18n('Create New Project')}
                </button>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

// Inline create project form
function CreateProjectForm({
  onSuccess,
  onCancel,
}: {
  onSuccess: () => void;
  onCancel: () => void;
}) {
  const { createProject } = useProjectStore();
  const currentProject = useProjectStore((s) => s.currentProject);
  const [name, setName] = useState('');
  const [path, setPath] = useState('');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const [showBrowser, setShowBrowser] = useState(false);

  const handleCreate = async () => {
    if (!name.trim() || !path.trim()) {
      setError(i18n('Name and path are required'));
      return;
    }
    setCreating(true);
    setError('');
    try {
      await createProject(name.trim(), path.trim(), description.trim() || undefined);
      onSuccess();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !creating) {
      handleCreate();
    }
  };

  return (
    <div className="p-3 space-y-3">
      <h3 className="text-sm font-semibold">{i18n('New Project')}</h3>
      <div>
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={i18n('Name')}
          className="w-full px-2 py-1.5 text-sm border border-border rounded-md bg-transparent focus:outline-none focus:ring-1 focus:ring-ring"
        />
      </div>
      <div>
        {showBrowser ? (
          <DirectoryBrowser
            initialPath={currentProject?.path}
            onSelect={(selectedPath) => {
              setPath(selectedPath);
              setShowBrowser(false);
            }}
            onCancel={() => setShowBrowser(false)}
          />
        ) : (
          <div className="flex gap-1">
            <input
              type="text"
              value={path}
              readOnly
              placeholder={i18n('Path')}
              className="flex-1 px-2 py-1.5 text-sm border border-border rounded-md bg-transparent focus:outline-none"
            />
            <button
              onClick={() => setShowBrowser(true)}
              className="px-2 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
              title={i18n('Browse')}
            >
              <FolderOpen className="w-4 h-4" />
            </button>
          </div>
        )}
      </div>
      <div>
        <input
          type="text"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={i18n('Description (optional)')}
          className="w-full px-2 py-1.5 text-sm border border-border rounded-md bg-transparent focus:outline-none focus:ring-1 focus:ring-ring"
        />
      </div>
      {error && (
        <div className="text-xs text-destructive">{error}</div>
      )}
      <div className="flex gap-2">
        <button
          onClick={onCancel}
          className="flex-1 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
        >
          {i18n('Cancel')}
        </button>
        <button
          onClick={handleCreate}
          disabled={creating}
          className="flex-1 px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:bg-primary/90 disabled:opacity-50 transition-colors"
        >
          {creating ? i18n('Creating...') : i18n('Create')}
        </button>
      </div>
    </div>
  );
}
