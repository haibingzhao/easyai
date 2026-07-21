import { useState, useEffect } from 'react';
import { useProjectStore } from '@/services/stores/project-store';
import { FolderPlus, Trash2, Check, ArrowRight, FolderOpen } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { DirectoryBrowser } from './DirectoryBrowser';

export function ProjectSelectPage() {
  const { projects, currentProject, projectsLoading, createProject, selectProject, deleteProject, loadProjects } = useProjectStore();
  const [showForm, setShowForm] = useState(false);
  const [name, setName] = useState('');
  const [path, setPath] = useState('');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const [showBrowser, setShowBrowser] = useState(false);

  // Ensure projects are loaded when this page mounts
  useEffect(() => {
    if (projects.length === 0 && !projectsLoading) {
      loadProjects();
    }
  }, [projects.length, projectsLoading, loadProjects]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !creating) {
      handleCreate();
    }
  };

  const handleCreate = async () => {
    if (!name.trim() || !path.trim()) {
      setError(i18n('Name and path are required'));
      return;
    }
    setCreating(true);
    setError('');
    try {
      await createProject(name.trim(), path.trim(), description.trim() || undefined);
      setName('');
      setPath('');
      setDescription('');
      setShowForm(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const handleSelect = (id: string) => {
    const project = projects.find((p) => p.id === id);
    if (project) {
      selectProject(project);
    }
  };

  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (window.confirm(i18n('Are you sure you want to delete this project?'))) {
      await deleteProject(id);
    }
  };

  return (
    <div className="flex items-center justify-center min-h-screen bg-gray-50 dark:bg-gray-950">
      <div className="w-full max-w-md p-6">
        <h1 className="text-2xl font-bold text-center text-gray-900 dark:text-white mb-6">
          {i18n('Select a Project')}
        </h1>

        {/* Project List */}
        <div className="bg-white dark:bg-gray-900 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 overflow-hidden mb-4">
          {projectsLoading ? (
            <div className="p-4 text-center text-gray-500 dark:text-gray-400">{i18n('Loading...')}</div>
          ) : projects.length === 0 ? (
            <div className="p-4 text-center text-gray-500 dark:text-gray-400">
              <p>{i18n('No projects yet')}</p>
              <p className="text-xs mt-1 text-gray-400 dark:text-gray-500">{i18n('Add a project to get started')}</p>
            </div>
          ) : (
            <ul className="divide-y divide-gray-100 dark:divide-gray-800">
              {projects.map((project) => (
                <li
                  key={project.id}
                  className={`flex items-center justify-between p-3 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors ${
                    currentProject?.id === project.id ? 'bg-blue-50 dark:bg-blue-900/20' : ''
                  }`}
                  onClick={() => handleSelect(project.id)}
                >
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-gray-900 dark:text-white truncate">
                      {project.name}
                    </div>
                    <div className="text-sm text-gray-500 dark:text-gray-400 truncate">
                      {project.path}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 ml-4">
                    {currentProject?.id === project.id && (
                      <Check className="w-4 h-4 text-blue-600 dark:text-blue-400" />
                    )}
                    <button
                      onClick={(e) => handleDelete(project.id, e)}
                      className="p-1 text-gray-400 dark:text-gray-500 hover:text-red-500 dark:hover:text-red-400 transition-colors"
                      title={i18n('Delete project')}
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* Create Project Button */}
        {!showForm ? (
          <button
            onClick={() => setShowForm(true)}
            className="w-full flex items-center justify-center gap-2 p-3 bg-gray-100 dark:bg-gray-800 hover:bg-gray-200 dark:hover:bg-gray-700 rounded-lg text-gray-600 dark:text-gray-400 transition-colors"
          >
            <FolderPlus className="w-4 h-4" />
            {i18n('Add Project')}
          </button>
        ) : (
          <div className="bg-white dark:bg-gray-900 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-4 space-y-3">
            <h2 className="font-semibold text-gray-900 dark:text-white">{i18n('New Project')}</h2>
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                {i18n('Name')}
              </label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="My Project"
                className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                {i18n('Path')}
              </label>
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
                    placeholder="/Users/xxx/workspace or C:\\Users\\xxx\\workspace"
                    className="flex-1 px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none"
                  />
                  <button
                    onClick={() => setShowBrowser(true)}
                    className="px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-800 hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400 transition-colors"
                    title={i18n('Browse')}
                  >
                    <FolderOpen className="w-4 h-4" />
                  </button>
                </div>
              )}
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                {i18n('Description (optional)')}
              </label>
              <input
                type="text"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={i18n('Project description')}
                className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400"
              />
            </div>
            {error && (
              <div className="text-sm text-red-600 dark:text-red-400">{error}</div>
            )}
            <div className="flex gap-2">
              <button
                onClick={() => { setShowForm(false); setError(''); }}
                className="flex-1 px-4 py-2 text-gray-700 dark:text-gray-300 bg-gray-100 dark:bg-gray-800 hover:bg-gray-200 dark:hover:bg-gray-700 rounded-md transition-colors"
              >
                {i18n('Cancel')}
              </button>
              <button
                onClick={handleCreate}
                disabled={creating}
                className="flex-1 flex items-center justify-center gap-1 px-4 py-2 text-white bg-blue-600 hover:bg-blue-700 rounded-md disabled:opacity-50 transition-colors"
              >
                {creating ? i18n('Creating...') : (
                  <>
                    {i18n('Create')} <ArrowRight className="w-4 h-4" />
                  </>
                )}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
