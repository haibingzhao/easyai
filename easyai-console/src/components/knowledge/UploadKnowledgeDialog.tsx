import React, { useState, useRef } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { Upload, FolderOpen, CheckCircle, XCircle } from 'lucide-react';
import { useCategoryStore } from '@/services/stores/category-store';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { i18n } from '@/utils/i18n';
import type { UploadResponseDto } from '@/services/knowledge-service';

interface UploadKnowledgeDialogProps {
  open: boolean;
  onClose: () => void;
}

const ACCEPTED_EXTENSIONS = '.md,.txt,.kt,.java,.py,.ts,.tsx,.js,.json,.yml,.yaml,.sql,.properties,.html,.csv,.sh';

export const UploadKnowledgeDialog: React.FC<UploadKnowledgeDialogProps> = ({ open, onClose }) => {
  const knowledgeCategories = useCategoryStore((s) => s.knowledgeCategories);
  const upload = useKnowledgeStore((s) => s.upload);
  const uploadResult = useKnowledgeStore((s) => s.uploadResult);
  const clearUploadResult = useKnowledgeStore((s) => s.clearUploadResult);

  const [files, setFiles] = useState<File[]>([]);
  const [paths, setPaths] = useState<string[]>([]);
  const [source, setSource] = useState('');
  const [category, setCategory] = useState('');
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<UploadResponseDto | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);

  const handleFileSelect = (fileList: FileList | null, useRelativePath: boolean) => {
    if (!fileList) return;
    const arr: File[] = [];
    const pathArr: string[] = [];
    for (let i = 0; i < fileList.length; i++) {
      const file = fileList[i];
      arr.push(file);
      if (useRelativePath && 'webkitRelativePath' in file && file.webkitRelativePath) {
        // Strip the leading segment (folder name): it is already used as source,
        // keeping it would produce duplicated keys like docs/docs/README.md.
        const segments = file.webkitRelativePath.split('/');
        pathArr.push(segments.length > 1 ? segments.slice(1).join('/') : file.name);
      } else {
        pathArr.push(file.name);
      }
    }
    setFiles(arr);
    setPaths(pathArr);
    // Auto-detect source from folder name (leading segment of webkitRelativePath)
    if (useRelativePath && arr.length > 0) {
      const firstPath = fileList[0].webkitRelativePath;
      const folderName = firstPath ? firstPath.split('/')[0] : undefined;
      if (folderName && folderName !== arr[0].name) {
        setSource(folderName);
      }
    }
  };

  const handleUpload = async () => {
    if (files.length === 0) return;
    setUploading(true);
    setResult(null);
    try {
      const resolvedSource = source.trim() || 'default';
      const res = await upload(files, paths, resolvedSource, category || undefined);
      setResult(res);
    } catch {
      // error already in store
    } finally {
      setUploading(false);
    }
  };

  const handleClose = () => {
    setFiles([]);
    setPaths([]);
    setSource('');
    setCategory('');
    setResult(null);
    clearUploadResult();
    onClose();
  };

  const displayResult = result ?? uploadResult;

  return (
    <Dialog open={open} onClose={handleClose} title={i18n('Upload Knowledge')}>
      <div className="space-y-4">
        {/* Source input */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Source')}</label>
          <input
            type="text"
            value={source}
            onChange={(e) => setSource(e.target.value)}
            placeholder="default"
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
          />
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
                  {!r.success && r.reason && (
                    <span className="text-destructive truncate max-w-40">{r.reason}</span>
                  )}
                </div>
              ))}
            </div>
          </div>
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
