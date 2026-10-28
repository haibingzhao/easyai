import React, { useState, useRef, useEffect, useMemo } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { Upload, FolderOpen, CheckCircle, XCircle } from 'lucide-react';
import { useCategoryStore } from '@/services/stores/category-store';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { readErrorMessage } from '@/services/api-client';
import { folderDisplayPath, folderLabel, foldersById } from '@/utils/knowledge-tree';
import { i18n } from '@/utils/i18n';
import type { UploadResponseDto } from '@/services/knowledge-service';

interface UploadKnowledgeDialogProps {
  open: boolean;
  /** Folder selected in the tree; used as the destination when it accepts writes. */
  initialFolderId?: string | null;
  onClose: () => void;
}

const ACCEPTED_EXTENSIONS = '.md,.txt,.kt,.java,.py,.ts,.tsx,.js,.json,.yml,.yaml,.sql,.properties,.html,.csv,.sh';

export const UploadKnowledgeDialog: React.FC<UploadKnowledgeDialogProps> = ({
  open,
  initialFolderId,
  onClose,
}) => {
  const knowledgeCategories = useCategoryStore((s) => s.knowledgeCategories);
  const upload = useKnowledgeStore((s) => s.upload);
  const uploadResult = useKnowledgeStore((s) => s.uploadResult);
  const clearUploadResult = useKnowledgeStore((s) => s.clearUploadResult);
  const folders = useKnowledgeStore((s) => s.folders);
  const loadFolders = useKnowledgeStore((s) => s.loadFolders);

  const [files, setFiles] = useState<File[]>([]);
  const [paths, setPaths] = useState<string[]>([]);
  const [folderId, setFolderId] = useState('');
  const [category, setCategory] = useState('');
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<UploadResponseDto | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    void loadFolders().catch(() => undefined);
    // Seed once per open from the tree selection. Only a writable, non-default folder is a valid
    // explicit target; anything else falls back to the implicit default.
    const selected = useKnowledgeStore.getState().folders.find((f) => f.id === initialFolderId);
    setFolderId(selected && selected.writable && !selected.isDefault ? selected.id : '');
  }, [open, initialFolderId, loadFolders]);

  const byId = useMemo(() => foldersById(folders), [folders]);
  const folderOptions = useMemo(
    () =>
      folders
        // The empty option already targets the default folder, and the other visible bucket is
        // read-only for writes — offering either here only produces a 400.
        .filter((folder) => folder.writable && !folder.isDefault)
        .map((folder) => ({
          id: folder.id,
          path: folderDisplayPath(folder.id, byId, (f) => folderLabel(f, i18n)),
        }))
        .sort((a, b) => a.path.localeCompare(b.path)),
    [folders, byId],
  );

  const handleFileSelect = (fileList: FileList | null, useRelativePath: boolean) => {
    if (!fileList) return;
    const arr: File[] = [];
    const pathArr: string[] = [];
    for (let i = 0; i < fileList.length; i++) {
      const file = fileList[i];
      arr.push(file);
      if (useRelativePath && 'webkitRelativePath' in file && file.webkitRelativePath) {
        // Keep the full relative path: every directory segment becomes a subfolder under the
        // chosen destination, preserving the uploaded folder's structure.
        pathArr.push(file.webkitRelativePath);
      } else {
        pathArr.push(file.name);
      }
    }
    setFiles(arr);
    setPaths(pathArr);
  };

  const handleUpload = async () => {
    if (files.length === 0) return;
    setUploading(true);
    setResult(null);
    setUploadError(null);
    try {
      const res = await upload(files, paths, folderId || undefined, category || undefined);
      setResult(res);
    } catch (e) {
      setUploadError(readErrorMessage(e, i18n('Upload failed')));
    } finally {
      setUploading(false);
    }
  };

  const handleClose = () => {
    setFiles([]);
    setPaths([]);
    setFolderId('');
    setCategory('');
    setResult(null);
    setUploadError(null);
    clearUploadResult();
    onClose();
  };

  const displayResult = result ?? uploadResult;

  return (
    <Dialog open={open} onClose={handleClose} title={i18n('Upload Knowledge')}>
      <div className="space-y-4">
        {/* Destination folder */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Folder')}</label>
          <select
            value={folderId}
            onChange={(e) => setFolderId(e.target.value)}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
          >
            <option value="">{i18n('Default folder')}</option>
            {folderOptions.map((option) => (
              <option key={option.id} value={option.id}>
                {option.path}
              </option>
            ))}
          </select>
        </div>

        {/* Category select */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Category')}</label>
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
          >
            <option value="">{i18n('All Categories')}</option>
            {knowledgeCategories.map((c) => (
              <option key={c.code} value={c.code}>
                {i18n(c.labelKey)}
              </option>
            ))}
          </select>
        </div>

        {/* Upload buttons */}
        {!displayResult && (
          <div className="flex gap-3">
            <div className="flex-1">
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept={ACCEPTED_EXTENSIONS}
                className="hidden"
                onChange={(e) => handleFileSelect(e.target.files, false)}
              />
              <button
                onClick={() => fileInputRef.current?.click()}
                className="w-full flex items-center justify-center gap-2 px-3 py-3 rounded-md border border-border hover:bg-muted transition-colors text-sm"
              >
                <Upload className="size-4" />
                {i18n('Upload Files')}
              </button>
            </div>
            <div className="flex-1">
              <input
                ref={folderInputRef}
                type="file"
                {...{ webkitdirectory: '', directory: '' } as React.InputHTMLAttributes<HTMLInputElement>}
                className="hidden"
                onChange={(e) => handleFileSelect(e.target.files, true)}
              />
              <button
                onClick={() => folderInputRef.current?.click()}
                className="w-full flex items-center justify-center gap-2 px-3 py-3 rounded-md border border-border hover:bg-muted transition-colors text-sm"
              >
                <FolderOpen className="size-4" />
                {i18n('Upload Folder')}
              </button>
            </div>
          </div>
        )}

        {/* File list preview */}
        {files.length > 0 && !displayResult && (
          <div className="max-h-40 overflow-y-auto border border-border rounded-md p-2">
            <div className="text-xs text-muted-foreground mb-1">
              {files.length} {i18n('files selected')}
            </div>
            {files.slice(0, 20).map((_, i) => (
              <div key={i} className="text-xs text-foreground truncate">
                {paths[i]}
              </div>
            ))}
            {files.length > 20 && (
              <div className="text-xs text-muted-foreground">...and {files.length - 20} more</div>
            )}
          </div>
        )}

        {/* Upload result */}
        {displayResult && (
          <div className="border border-border rounded-md p-3">
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium">
                {displayResult.successCount} {i18n('files uploaded')}
                {displayResult.failedCount > 0 && (
                  <span className="text-destructive ml-2">
                    {displayResult.failedCount} {i18n('files failed')}
                  </span>
                )}
              </span>
            </div>
            <div className="max-h-40 overflow-y-auto space-y-1">
              {displayResult.results.map((r, i) => (
                <div key={i} className="flex items-center gap-2 text-xs">
                  {r.success ? (
                    <CheckCircle className="size-3.5 text-green-500 shrink-0" />
                  ) : (
                    <XCircle className="size-3.5 text-destructive shrink-0" />
                  )}
                  <span className="truncate flex-1">{r.relativePath}</span>
                  {r.success && r.indexPending && (
                    <span className="text-amber-600 dark:text-amber-400 truncate max-w-40">
                      {i18n('Indexing pending')}
                    </span>
                  )}
                  {!r.success && r.reason && (
                    <span className="text-destructive truncate max-w-40">{r.reason}</span>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {uploadError && !displayResult && (
          <div className="text-sm text-destructive">{uploadError}</div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-2 pt-2">
          {displayResult ? (
            <button
              onClick={handleClose}
              className="px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              {i18n('Close')}
            </button>
          ) : (
            <>
              <button
                onClick={handleClose}
                className="px-4 py-2 rounded-md text-sm border border-border hover:bg-muted transition-colors"
              >
                {i18n('Cancel')}
              </button>
              <button
                onClick={handleUpload}
                disabled={uploading || files.length === 0}
                className="px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {uploading ? i18n('Uploading...') : i18n('Upload')}
              </button>
            </>
          )}
        </div>
      </div>
    </Dialog>
  );
};
