import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Folder, Loader2 } from 'lucide-react';
import { Input } from '@/components/ui/Input';
import { useProjectStore } from '@/services/stores/project-store';
import { i18n } from '@/utils/i18n';

/**
 * Project picker.
 *
 * The desktop version embeds a server-side directory browser for creating projects; creating
 * one from a phone is not a realistic gesture, so this page only *selects*. The backend still
 * supplies the project list, and "start without a project" maps to the same temporary-workspace
 * mode the desktop uses.
 */
export const MobileProjectPage: React.FC = () => {
  const navigate = useNavigate();
  const { projects, projectsLoading, loadProjects, searchProjects, selectProject, startWithoutProject } =
    useProjectStore();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<typeof projects | null>(null);

  useEffect(() => {
    if (!query.trim()) {
      setResults(null);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      void searchProjects(query.trim()).then((found) => {
        if (!cancelled) setResults(found);
      });
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query]);

  useEffect(() => {
    if (projects.length === 0 && !projectsLoading) void loadProjects();
  }, [projects.length, projectsLoading, loadProjects]);

  const visible = results ?? projects;

  const choose = (project: (typeof projects)[number]) => {
    selectProject(project);
    navigate('/chat');
  };

  return (
    <div className="flex-1 min-h-0 flex flex-col mobile-app-header">
      <div className="px-4 py-3 border-b border-border">
        <h1 className="text-base font-medium mb-3">{i18n('Select a Project')}</h1>
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={i18n('Search projects...')}
          className="h-11"
        />
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto message-list-scroll">
        {projectsLoading && !results ? (
          <div className="flex items-center justify-center py-10 text-muted-foreground">
            <Loader2 className="w-5 h-5 animate-spin" />
          </div>
        ) : visible.length === 0 ? (
          <p className="text-sm text-muted-foreground px-4 py-10 text-center">{i18n('No projects yet')}</p>
        ) : (
          visible.map((project) => (
            <button key={project.id} className="mobile-row w-full text-left border-b border-border" onClick={() => choose(project)}>
              <Folder className="w-5 h-5 shrink-0 text-muted-foreground" />
              <span className="min-w-0 flex-1">
                <span className="block text-sm truncate">{project.name}</span>
                <span className="block text-[11px] text-muted-foreground truncate">{project.path}</span>
              </span>
            </button>
          ))
        )}
      </div>

      <button
        className="mobile-row w-full text-left border-t border-border text-sm text-muted-foreground justify-center"
        onClick={() => {
          startWithoutProject();
          navigate('/chat');
        }}
      >
        {i18n('Start without a project')}
      </button>
    </div>
  );
};
