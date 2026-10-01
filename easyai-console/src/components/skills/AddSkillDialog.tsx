import React, { useEffect, useRef, useState } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { DirectoryBrowser } from '@/components/project/DirectoryBrowser';
import { useSkillStore } from '@/services/stores/skill-store';
import { i18n } from '@/utils/i18n';
import { FolderOpen, FileArchive, Loader2, X } from 'lucide-react';

/** The server accepts a path-safe slug; refuse anything else before the round-trip. */
const SKILL_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

type SourceTab = 'directory' | 'upload';
type UploadKind = 'folder' | 'zip';

interface AddSkillDialogProps {
  open: boolean;
  onClose: () => void;
  /** The system identity publishes into the shared layer; everyone else installs for themselves. */
  canPublishShared: boolean;
}

interface PickedFiles {
  files: File[];
  paths: string[];
  suggestedName: string;
}

export const AddSkillDialog: React.FC<AddSkillDialogProps> = ({ open, onClose, canPublishShared }) => {
  const installFromDirectory = useSkillStore((state) => state.installFromDirectory);
  const installFromUpload = useSkillStore((state) => state.installFromUpload);

  const [tab, setTab] = useState<SourceTab>('directory');
  const [uploadKind, setUploadKind] = useState<UploadKind>('folder');
  const [name, setName] = useState('');
  const [shared, setShared] = useState(false);
  const [sourcePath, setSourcePath] = useState('');
  const [browsing, setBrowsing] = useState(false);
  const [picked, setPicked] = useState<PickedFiles | null>(null);
  const [archive, setArchive] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 409 keeps the dialog open with the name field selected: the fix is a different name. */
  const [needsNewName, setNeedsNewName] = useState(false);

  const folderInputRef = useRef<HTMLInputElement>(null);
  const archiveInputRef = useRef<HTMLInputElement>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);

  // A conflict is fixed by renaming, so put the cursor where the user has to act.
  useEffect(() => {
    if (needsNewName) nameInputRef.current?.select();
  }, [needsNewName]);

  /** A file input keeps its value after the state is cleared, so re-picking the same file would be a no-op. */
  const clearPicked = () => {
    setPicked(null);
    if (folderInputRef.current) folderInputRef.current.value = '';
  };

  const clearArchive = () => {
    setArchive(null);
    if (archiveInputRef.current) archiveInputRef.current.value = '';
  };

  const reset = () => {
    setTab('directory');
    setUploadKind('folder');
    setName('');
    setShared(false);
    setSourcePath('');
    setBrowsing(false);
    setError(null);
    setNeedsNewName(false);
    clearPicked();
    clearArchive();
  };

  const close = () => {
    if (submitting) return;
    reset();
    onClose();
  };

  /** A folder upload carries every file under one top-level folder; drop that prefix from the paths. */
  const handleFolderSelect = (list: FileList | null) => {
    if (!list?.length) return;
    const files = Array.from(list);
    const paths = files.map((file) => {
      const relative = 'webkitRelativePath' in file ? file.webkitRelativePath : '';
      const segments = relative.split('/');
      return segments.length > 1 ? segments.slice(1).join('/') : file.name;
    });
    const topFolder = ('webkitRelativePath' in files[0] ? files[0].webkitRelativePath : '')
      .split('/')[0];
    setPicked({ files, paths, suggestedName: topFolder || files[0].name });
    clearArchive();
    setError(null);
    if (!name) setName(topFolder);
  };

  const handleArchiveSelect = (list: FileList | null) => {
    const file = list?.[0];
    if (!file) return;
    setArchive(file);
    clearPicked();
    setError(null);
    if (!name) setName(file.name.replace(/\.zip$/i, ''));
  };

  const submit = async () => {
    setError(null);
    if (!SKILL_NAME.test(name)) {
      setError(i18n('Letters, numbers, dot, dash and underscore only, starting with a letter or digit'));
      return;
    }
    if (tab === 'directory' && !sourcePath.trim()) {
      setError(i18n('Select the server directory that holds SKILL.md.'));
      return;
    }
    if (tab === 'upload' && uploadKind === 'folder' && !picked) {
      setError(i18n('Select the skill folder to upload.'));
      return;
    }
    if (tab === 'upload' && uploadKind === 'zip' && !archive) {
      setError(i18n('Select the zip package to upload.'));
      return;
    }

    setSubmitting(true);
    try {
      const result = tab === 'directory'
        ? await installFromDirectory({ name, sourcePath: sourcePath.trim(), shared })
        : archive
          ? await installFromUpload({ name, shared, archive })
          : await installFromUpload({ name, shared, files: picked?.files ?? [], paths: picked?.paths ?? [] });

      if (result.skill) {
        reset();
        onClose();
        return;
      }
      setNeedsNewName(result.status === 409);
      setError(result.message ?? i18n('The skill could not be installed.'));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={close} title={i18n('Add Skill')}>
      <div className="space-y-4">
        {error && (
          <div className="p-3 bg-destructive/10 text-destructive rounded-md text-sm">{error}</div>
        )}

        <div className="flex gap-1 border-b border-border">
          {(['directory', 'upload'] as SourceTab[]).map((value) => (
            <button
              key={value}
              onClick={() => { setTab(value); setError(null); }}
              className={`px-3 py-2 text-sm border-b-2 -mb-px transition-colors ${
                tab === value ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              {value === 'directory' ? i18n('Server directory') : i18n('Upload from computer')}
            </button>
          ))}
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Skill Name')}</label>
          <input
            ref={nameInputRef}
            type="text"
            value={name}
            onChange={(e) => { setName(e.target.value); setNeedsNewName(false); }}
            placeholder="pdf-report"
            className="flex-1 px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none font-mono"
          />
          <p className="text-xs text-muted-foreground mt-1">
            {needsNewName ? i18n('That name is taken. Choose another one.') : i18n('Letters, numbers, dot, dash and underscore only')}
          </p>
        </div>

        {tab === 'directory' && (
          <div className="space-y-2">
            <label className="block text-sm font-medium">{i18n('Source Directory')}</label>
            {browsing ? (
              <DirectoryBrowser
                initialPath={sourcePath || undefined}
                onSelect={(selected) => { setSourcePath(selected); setBrowsing(false); }}
                onCancel={() => setBrowsing(false)}
              />
            ) : (
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={sourcePath}
                  onChange={(e) => setSourcePath(e.target.value)}
                  placeholder="/Users/you/my-skill"
                  className="flex-1 px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none font-mono"
                />
                <button
                  onClick={() => setBrowsing(true)}
                  className="flex items-center gap-1.5 px-3 py-2 rounded-md border border-input text-sm hover:bg-muted transition-colors"
                >
                  <FolderOpen className="w-4 h-4" />
                  {i18n('Browse')}
                </button>
              </div>
            )}
            <p className="text-xs text-muted-foreground">{i18n('The directory must contain a SKILL.md file on this server.')}</p>
          </div>
        )}

        {tab === 'upload' && (
          <div className="space-y-3">
            <div className="flex gap-2">
              {(['folder', 'zip'] as UploadKind[]).map((kind) => (
                <button
                  key={kind}
                  onClick={() => { setUploadKind(kind); setError(null); }}
                  className={`px-3 py-1.5 text-sm rounded-md border transition-colors ${
                    uploadKind === kind ? 'border-primary text-primary bg-primary/5' : 'border-border text-muted-foreground'
                  }`}
                >
                  {kind === 'folder' ? i18n('Folder') : i18n('Zip archive')}
                </button>
              ))}
            </div>

            {uploadKind === 'folder' ? (
              <div className="space-y-2">
                <button
                  onClick={() => folderInputRef.current?.click()}
                  className="flex items-center gap-2 px-3 py-2 rounded-md border border-input text-sm hover:bg-muted transition-colors"
                >
                  <FolderOpen className="w-4 h-4" />
                  {i18n('Choose folder')}
                </button>
                <input
                  ref={folderInputRef}
                  type="file"
                  {...{ webkitdirectory: '', directory: '' } as React.InputHTMLAttributes<HTMLInputElement>}
                  className="hidden"
                  onChange={(e) => handleFolderSelect(e.target.files)}
                />
                {picked && (
                  <div className="rounded-md border border-border p-3 space-y-1">
                    <div className="flex items-center justify-between text-sm">
                      <span>{i18n('Files')}: {picked.files.length}</span>
                      <button onClick={clearPicked} className="text-muted-foreground hover:text-foreground">
                        <X className="w-4 h-4" />
                      </button>
                    </div>
                    <ul className="text-xs text-muted-foreground font-mono max-h-32 overflow-y-auto">
                      {picked.paths.slice(0, 20).map((path) => <li key={path}>{path}</li>)}
                      {picked.paths.length > 20 && <li>…</li>}
                    </ul>
                  </div>
                )}
                <p className="text-xs text-muted-foreground">{i18n('Select the folder that contains SKILL.md; its files are uploaded together.')}</p>
              </div>
            ) : (
              <div className="space-y-2">
                <button
                  onClick={() => archiveInputRef.current?.click()}
                  className="flex items-center gap-2 px-3 py-2 rounded-md border border-input text-sm hover:bg-muted transition-colors"
                >
                  <FileArchive className="w-4 h-4" />
                  {i18n('Choose zip file')}
                </button>
                <input
                  ref={archiveInputRef}
                  type="file"
                  accept=".zip,application/zip"
                  className="hidden"
                  onChange={(e) => handleArchiveSelect(e.target.files)}
                />
                {archive && (
                  <div className="flex items-center justify-between rounded-md border border-border p-3 text-sm">
                    <span className="font-mono truncate">{archive.name}</span>
                    <button onClick={clearArchive} className="text-muted-foreground hover:text-foreground">
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                )}
                <p className="text-xs text-muted-foreground">{i18n('The archive must hold SKILL.md at its root or in one top-level folder.')}</p>
              </div>
            )}
          </div>
        )}

        {canPublishShared && (
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={shared}
              onChange={(e) => setShared(e.target.checked)}
              className="mt-0.5 w-4 h-4 rounded border-input text-primary focus:ring-primary"
            />
            <span>
              <span className="font-medium">{i18n('Publish to the shared layer')}</span>
              <span className="block text-xs text-muted-foreground">
                {i18n('Every user can load it but only you can change or delete it.')}
              </span>
            </span>
          </label>
        )}

        <div className="flex items-center justify-end gap-2 pt-2">
          <button
            onClick={close}
            disabled={submitting}
            className="px-4 py-2 rounded-md border border-input text-sm hover:bg-muted transition-colors disabled:opacity-50"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={submit}
            disabled={submitting}
            className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
            {submitting ? i18n('Installing...') : i18n('Install')}
          </button>
        </div>
      </div>
    </Dialog>
  );
};
