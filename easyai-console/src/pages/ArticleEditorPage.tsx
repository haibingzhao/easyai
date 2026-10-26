import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useEditor, EditorContent } from '@tiptap/react';
import { BubbleMenu } from '@tiptap/react/menus';
import StarterKit from '@tiptap/starter-kit';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import Image from '@tiptap/extension-image';
import { Table, TableRow, TableCell, TableHeader } from '@tiptap/extension-table';
import Placeholder from '@tiptap/extension-placeholder';
import { Markdown } from 'tiptap-markdown';
import type { Editor } from '@tiptap/core';
import type { MarkdownStorage } from 'tiptap-markdown';
import {
  ArrowLeft, Save, Loader2, Sparkles,
  Bold, Italic, Strikethrough,
  Heading1, Heading2, List, ListOrdered,
  Quote, Code, Link2, Minus,
  Check, X, Wand2,
} from 'lucide-react';
import { useKnowledgeStore } from '@/services/stores/knowledge-store';
import { knowledgeService } from '@/services/knowledge-service';
import { useEnabledModels } from '@/hooks/useEnabledModels';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { NEW_ARTICLE_KEY } from '@/components/knowledge/constants';
import { readErrorMessage } from '@/services/api-client';
import { i18n } from '@/utils/i18n';

function getMarkdown(editor: Editor | null): string | null {
  if (!editor) return null;
  const markdown = (editor.storage as { markdown?: MarkdownStorage }).markdown;
  if (!markdown) {
    console.warn('tiptap-markdown storage is unavailable');
    return null;
  }
  return markdown.getMarkdown();
}

export const ArticleEditorPage: React.FC = () => {
  const { articleKey } = useParams<{ articleKey: string }>();
  const navigate = useNavigate();
  // React Router already decodes path params once — decoding again would corrupt keys containing %xx.
  const routeKey = articleKey ?? '';
  const routeIsNew = routeKey === NEW_ARTICLE_KEY;

  const loadArticleContent = useKnowledgeStore((s) => s.loadArticleContent);
  const updateArticle = useKnowledgeStore((s) => s.updateArticle);
  const saveToKnowledge = useKnowledgeStore((s) => s.saveToKnowledge);
  const articles = useKnowledgeStore((s) => s.articles);
  const loadArticles = useKnowledgeStore((s) => s.loadArticles);
  const { models, selectedModelId, setSelectedModelId, error: modelsError } = useEnabledModels();

  // Set once the first save of a new draft succeeds, so later saves update instead of creating.
  const [createdKey, setCreatedKey] = useState<string | null>(null);
  const activeKey = createdKey ?? routeKey;
  const isNewDraft = createdKey === null && routeIsNew;

  const [title, setTitle] = useState('');
  const [initialContent, setInitialContent] = useState<string | null>(routeIsNew ? '' : null);
  const [contentRequestKey, setContentRequestKey] = useState<string | null>(routeIsNew ? NEW_ARTICLE_KEY : null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [aiPanelOpen, setAiPanelOpen] = useState(false);
  const [aiInstruction, setAiInstruction] = useState('');
  const [aiRunning, setAiRunning] = useState(false);
  const [aiMode, setAiMode] = useState<'rewrite' | 'selection'>('rewrite');
  const [selectedText, setSelectedText] = useState('');
  const [aiError, setAiError] = useState<string | null>(null);
  const [linkPanelOpen, setLinkPanelOpen] = useState(false);
  const [linkUrl, setLinkUrl] = useState('');
  const [leaveConfirmOpen, setLeaveConfirmOpen] = useState(false);
  const initializedRef = useRef(false);
  const appliedContentKeyRef = useRef<string | null>(null);
  const titleKeyRef = useRef<string | null>(null);
  const titleTouchedRef = useRef(false);
  const docVersionRef = useRef(0);
  // The route key whose content has already been loaded (or created in place).
  const loadedKeyRef = useRef<string | null>(routeIsNew ? NEW_ARTICLE_KEY : null);

  const currentArticle = useMemo(
    () => (activeKey && !isNewDraft ? articles.find((a) => a.knowledgeKey === activeKey) ?? null : null),
    [articles, activeKey, isNewDraft],
  );
  const readOnly = !!currentArticle && !currentArticle.canWrite;

  const load = useCallback(async (key: string) => {
    setLoadError(null);
    setInitialContent(null);
    try {
      const [content] = await Promise.all([
        loadArticleContent(key),
        loadArticles(),
      ]);
      setContentRequestKey(key);
      setInitialContent(content);
    } catch (e) {
      setLoadError(readErrorMessage(e, i18n('Failed to load article')));
    }
  }, [loadArticleContent, loadArticles]);

  useEffect(() => {
    if (routeIsNew) return;
    // A save of a new draft navigates to its fresh key; the editor already holds that content.
    if (loadedKeyRef.current === routeKey) return;
    loadedKeyRef.current = routeKey;
    // A new route means a new document: allow its title to be prefilled from the list.
    titleTouchedRef.current = false;
    titleKeyRef.current = null;
    void load(routeKey);
  }, [routeIsNew, routeKey, load]);

  useEffect(() => {
    if (isNewDraft || titleTouchedRef.current) return;
    if (titleKeyRef.current === activeKey) return;
    const match = articles.find((a) => a.knowledgeKey === activeKey);
    if (match) {
      setTitle(match.title);
      titleKeyRef.current = activeKey;
    }
  }, [articles, activeKey, isNewDraft]);

  const editor = useEditor({
    extensions: [
      // StarterKit 3 already provides Link and Underline. Disable Underline: it has no
      // tiptap-markdown serializer and would be silently dropped on save.
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
        underline: false,
        link: { openOnClick: false },
      }),
      TaskList,
      TaskItem.configure({ nested: true }),
      Image,
      Table.configure({ resizable: true }),
      TableRow,
      TableCell,
      TableHeader,
      Placeholder.configure({ placeholder: i18n('Start writing...') }),
      Markdown.configure({
        html: false,
        transformPastedText: true,
        transformCopiedText: true,
      }),
    ],
    content: '',
    onUpdate: () => {
      docVersionRef.current += 1;
      setDirty(true);
    },
    onSelectionUpdate: ({ editor: ed }) => {
      const sel = ed.state.selection;
      if (sel.empty) {
        setSelectedText('');
      } else {
        setSelectedText(ed.state.doc.textBetween(sel.from, sel.to, ' '));
      }
    },
  });

  useEffect(() => {
    if (!editor || initialContent === null || contentRequestKey === null) return;
    if (appliedContentKeyRef.current === contentRequestKey) return;
    appliedContentKeyRef.current = contentRequestKey;
    const firstLoad = !initializedRef.current;
    initializedRef.current = true;
    // setContent with emitUpdate=false keeps programmatic loads out of the dirty/version tracking.
    // tiptap-markdown parses this as block markdown, unlike insertContentAt.
    editor.commands.setContent(initialContent, { emitUpdate: false });
    if (!firstLoad) {
      docVersionRef.current = 0;
      setDirty(false);
    }
  }, [editor, initialContent, contentRequestKey]);

  useEffect(() => {
    editor?.setEditable(!readOnly);
  }, [editor, readOnly]);

  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const handleBack = useCallback(() => {
    if (dirty) {
      setLeaveConfirmOpen(true);
    } else {
      navigate('/knowledge?tab=articles');
    }
  }, [dirty, navigate]);

  const handleSave = useCallback(async () => {
    if (!editor || saving || readOnly) return;
    const markdown = getMarkdown(editor);
    if (markdown === null) {
      setSaveError(i18n('Failed to read editor content'));
      return;
    }
    const versionAtSave = docVersionRef.current;
    setSaving(true);
    setSaveError(null);
    try {
      let targetKey = activeKey;
      let savedTitle = title.trim() || i18n('Untitled');

      if (isNewDraft) {
        const article = await saveToKnowledge({
          title: savedTitle,
          content: markdown,
          source: 'manual',
          origin: 'manual',
        });
        targetKey = article.knowledgeKey;
        savedTitle = article.title;
        setCreatedKey(targetKey);
        if (docVersionRef.current !== versionAtSave) {
          // The document kept changing while the create request was in flight —
          // push the latest snapshot before leaving the draft route.
          const latest = getMarkdown(editor);
          if (latest !== null) {
            const updated = await updateArticle({ key: targetKey, content: latest, title: title.trim() || undefined });
            savedTitle = updated.title;
          }
        }
        loadedKeyRef.current = targetKey;
        navigate(`/knowledge/editor/${encodeURIComponent(targetKey)}`, { replace: true });
      } else {
        const article = await updateArticle({
          key: targetKey,
          content: markdown,
          title: title.trim() || undefined,
        });
        // Backend keeps the previous title when `title` is omitted — mirror that here.
        savedTitle = article.title;
      }

      if (docVersionRef.current === versionAtSave) {
        setTitle(savedTitle);
        setDirty(false);
      }
    } catch (e) {
      setSaveError(readErrorMessage(e, i18n('Save failed')));
    } finally {
      setSaving(false);
    }
  }, [editor, saving, readOnly, activeKey, isNewDraft, title, saveToKnowledge, updateArticle, navigate]);

  const handleAiApply = useCallback(async () => {
    if (!editor || !selectedModelId || !aiInstruction.trim() || aiRunning || readOnly) return;
    const markdown = getMarkdown(editor);
    if (markdown === null) {
      setAiError(i18n('Failed to read editor content'));
      return;
    }
    // Snapshot the target range before awaiting: the document/selection may move meanwhile.
    const range = aiMode === 'selection'
      ? { from: editor.state.selection.from, to: editor.state.selection.to }
      : null;
    const expectedText = range
      ? editor.state.doc.textBetween(range.from, range.to, ' ')
      : '';
    setAiRunning(true);
    setAiError(null);
    try {
      const result = aiMode === 'rewrite'
        ? (await knowledgeService.aiRewrite({
            key: activeKey || NEW_ARTICLE_KEY,
            content: markdown,
            instruction: aiInstruction,
            modelConfigId: selectedModelId,
          })).content
        : (await knowledgeService.aiModifySelection({
            key: activeKey || NEW_ARTICLE_KEY,
            content: markdown,
            selectedText,
            instruction: aiInstruction,
            modelConfigId: selectedModelId,
          })).content;

      if (!result.trim()) {
        throw new Error(i18n('AI returned no content'));
      }

      if (aiMode === 'rewrite') {
        // tiptap-markdown overrides setContent to parse block-level markdown;
        // insertContentAt would be forced to inline parsing and flatten the document.
        editor.commands.setContent(result, { emitUpdate: false });
        docVersionRef.current += 1;
        setDirty(true);
      } else if (range) {
        const docSize = editor.state.doc.content.size;
        const currentText = range.to <= docSize
          ? editor.state.doc.textBetween(range.from, range.to, ' ')
          : null;
        if (currentText !== expectedText) {
          setAiError(i18n('The selection changed while AI was running. Please try again.'));
          return;
        }
        editor.commands.insertContentAt(range, result);
      }

      setAiPanelOpen(false);
      setAiInstruction('');
    } catch (e) {
      setAiError(readErrorMessage(e, i18n('AI operation failed')));
    } finally {
      setAiRunning(false);
    }
  }, [editor, selectedModelId, aiInstruction, aiRunning, readOnly, aiMode, activeKey, selectedText]);

  const openLinkPanel = useCallback(() => {
    setLinkUrl(editor?.getAttributes('link').href ?? '');
    setLinkPanelOpen(true);
  }, [editor]);

  const applyLink = useCallback(() => {
    if (!editor) return;
    const href = linkUrl.trim();
    if (!href) return;
    editor.chain().focus().extendMarkRange('link').setLink({ href }).run();
    setLinkPanelOpen(false);
  }, [editor, linkUrl]);

  const removeLink = useCallback(() => {
    if (!editor) return;
    editor.chain().focus().extendMarkRange('link').unsetLink().run();
    setLinkPanelOpen(false);
  }, [editor]);

  const toolbarBtnClass = (active: boolean) =>
    `p-1.5 rounded transition-colors ${active ? 'bg-primary/10 text-primary' : 'hover:bg-muted text-muted-foreground hover:text-foreground'}`;

  if (loadError) {
    return (
      <div className="h-full flex flex-col items-center justify-center gap-3 px-6 text-center">
        <p className="text-sm text-destructive">{loadError}</p>
        <div className="flex items-center gap-2">
          <button
            onClick={() => void load(routeKey)}
            className="px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            {i18n('Retry')}
          </button>
          <button
            onClick={() => navigate('/knowledge?tab=articles')}
            className="px-3 py-1.5 rounded-md text-xs border border-border hover:bg-muted transition-colors"
          >
            {i18n('Back')}
          </button>
        </div>
      </div>
    );
  }

  if (!editor || initialContent === null) {
    return (
      <div className="h-full flex items-center justify-center">
        <Loader2 className="size-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col bg-background">
      {/* Top bar */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-border shrink-0">
        <button
          onClick={handleBack}
          className="p-1.5 rounded-md hover:bg-muted transition-colors"
        >
          <ArrowLeft className="size-4" />
        </button>
        <input
          type="text"
          value={title}
          readOnly={readOnly}
          onChange={(e) => {
            titleTouchedRef.current = true;
            setTitle(e.target.value);
            docVersionRef.current += 1;
            setDirty(true);
          }}
          placeholder={i18n('Article title')}
          className="flex-1 text-sm font-medium bg-transparent border-none focus:outline-none placeholder:text-muted-foreground/50"
        />
        <button
          onClick={handleSave}
          disabled={saving || !dirty || readOnly}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
        >
          {saving ? <Loader2 className="size-3.5 animate-spin" /> : <Save className="size-3.5" />}
          {i18n('Save')}
        </button>
      </div>

      {readOnly && (
        <div className="px-4 py-1.5 border-b border-border bg-muted/40 text-xs text-muted-foreground shrink-0">
          {i18n('You do not have permission to edit this article')}
        </div>
      )}

      {saveError && (
        <div className="flex items-center justify-between px-4 py-2 border-b border-destructive/50 bg-destructive/5 text-xs text-destructive shrink-0">
          <span className="truncate">{saveError}</span>
          <button onClick={() => setSaveError(null)} className="text-destructive hover:opacity-70 shrink-0">
            <X className="size-3.5" />
          </button>
        </div>
      )}

      {/* Formatting toolbar */}
      {!readOnly && (
        <div className="flex items-center gap-0.5 px-4 py-1.5 border-b border-border shrink-0 overflow-x-auto">
          <button onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()} className={toolbarBtnClass(editor.isActive('heading', { level: 1 }))} title={i18n('Heading 1')}>
            <Heading1 className="size-4" />
          </button>
          <button onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} className={toolbarBtnClass(editor.isActive('heading', { level: 2 }))} title={i18n('Heading 2')}>
            <Heading2 className="size-4" />
          </button>
          <div className="w-px h-5 bg-border mx-1" />
          <button onClick={() => editor.chain().focus().toggleBold().run()} className={toolbarBtnClass(editor.isActive('bold'))} title={i18n('Bold')}>
            <Bold className="size-4" />
          </button>
          <button onClick={() => editor.chain().focus().toggleItalic().run()} className={toolbarBtnClass(editor.isActive('italic'))} title={i18n('Italic')}>
            <Italic className="size-4" />
          </button>
          <button onClick={() => editor.chain().focus().toggleStrike().run()} className={toolbarBtnClass(editor.isActive('strike'))} title={i18n('Strikethrough')}>
            <Strikethrough className="size-4" />
          </button>
          <div className="w-px h-5 bg-border mx-1" />
          <button onClick={() => editor.chain().focus().toggleBulletList().run()} className={toolbarBtnClass(editor.isActive('bulletList'))} title={i18n('Bullet list')}>
            <List className="size-4" />
          </button>
          <button onClick={() => editor.chain().focus().toggleOrderedList().run()} className={toolbarBtnClass(editor.isActive('orderedList'))} title={i18n('Ordered list')}>
            <ListOrdered className="size-4" />
          </button>
          <button onClick={() => editor.chain().focus().toggleTaskList().run()} className={toolbarBtnClass(editor.isActive('taskList'))} title={i18n('Task list')}>
            <Check className="size-4" />
          </button>
          <div className="w-px h-5 bg-border mx-1" />
          <button onClick={() => editor.chain().focus().toggleBlockquote().run()} className={toolbarBtnClass(editor.isActive('blockquote'))} title={i18n('Quote')}>
            <Quote className="size-4" />
          </button>
          <button onClick={() => editor.chain().focus().toggleCodeBlock().run()} className={toolbarBtnClass(editor.isActive('codeBlock'))} title={i18n('Code block')}>
            <Code className="size-4" />
          </button>
          <button onClick={() => editor.chain().focus().setHorizontalRule().run()} className={toolbarBtnClass(false)} title={i18n('Horizontal rule')}>
            <Minus className="size-4" />
          </button>
          <button
            onClick={openLinkPanel}
            className={toolbarBtnClass(editor.isActive('link') || linkPanelOpen)}
            title={i18n('Link')}
          >
            <Link2 className="size-4" />
          </button>
          <div className="w-px h-5 bg-border mx-1" />
          <button
            onClick={() => { setAiMode('rewrite'); setAiError(null); setAiPanelOpen(!aiPanelOpen); }}
            className={`flex items-center gap-1 px-2 py-1 rounded text-xs transition-colors ${aiPanelOpen && aiMode === 'rewrite' ? 'bg-primary/10 text-primary' : 'hover:bg-muted text-muted-foreground'}`}
            title={i18n('AI Rewrite')}
          >
            <Sparkles className="size-3.5" />
            {i18n('AI Rewrite')}
          </button>
        </div>
      )}

      {/* Link popover */}
      {linkPanelOpen && !readOnly && (
        <div className="flex items-center gap-2 px-4 py-2 border-b border-border bg-muted/30 shrink-0">
          <Link2 className="size-3.5 text-muted-foreground shrink-0" />
          <input
            type="text"
            value={linkUrl}
            onChange={(e) => setLinkUrl(e.target.value)}
            placeholder={i18n('Enter URL')}
            className="flex-1 px-3 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
            onKeyDown={(e) => {
              if (e.key === 'Enter') applyLink();
              if (e.key === 'Escape') setLinkPanelOpen(false);
            }}
            autoFocus
          />
          <button
            onClick={applyLink}
            disabled={!linkUrl.trim()}
            className="px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {i18n('Apply')}
          </button>
          <button
            onClick={removeLink}
            disabled={!editor.isActive('link')}
            className="px-3 py-1.5 rounded-md text-xs border border-border hover:bg-muted transition-colors disabled:opacity-50"
          >
            {i18n('Remove link')}
          </button>
          <button onClick={() => setLinkPanelOpen(false)} className="p-1 rounded hover:bg-muted transition-colors">
            <X className="size-3.5" />
          </button>
        </div>
      )}

      {/* AI panel */}
      {aiPanelOpen && !readOnly && (
        <div className="px-4 py-2 border-b border-border bg-muted/30 shrink-0 space-y-1.5">
          <div className="flex items-center gap-2">
            <select
              value={selectedModelId}
              onChange={(e) => setSelectedModelId(e.target.value)}
              className="px-2 py-1 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary shrink-0"
            >
              {models.map((m) => (
                <option key={m.id} value={m.id}>{m.name || m.modelId}</option>
              ))}
            </select>
            <input
              type="text"
              value={aiInstruction}
              onChange={(e) => setAiInstruction(e.target.value)}
              placeholder={aiMode === 'rewrite' ? i18n('Describe how to rewrite...') : i18n('Describe how to modify selection...')}
              className="flex-1 px-3 py-1.5 rounded-md border border-border bg-background text-xs focus:outline-none focus:ring-2 focus:ring-primary"
              onKeyDown={(e) => { if (e.key === 'Enter') handleAiApply(); }}
            />
            <button
              onClick={handleAiApply}
              disabled={
                aiRunning
                || !aiInstruction.trim()
                || !selectedModelId
                || (aiMode === 'rewrite' ? editor.isEmpty : !selectedText.trim())
              }
              className="flex items-center gap-1 px-3 py-1.5 rounded-md text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              {aiRunning ? <Loader2 className="size-3.5 animate-spin" /> : <Wand2 className="size-3.5" />}
              {i18n('Apply')}
            </button>
            <button onClick={() => setAiPanelOpen(false)} className="p-1 rounded hover:bg-muted transition-colors">
              <X className="size-3.5" />
            </button>
          </div>
          {modelsError && <div className="text-xs text-destructive">{modelsError}</div>}
          {!modelsError && models.length === 0 && (
            <div className="text-xs text-muted-foreground">{i18n('No available models')}</div>
          )}
          {aiError && <div className="text-xs text-destructive">{aiError}</div>}
        </div>
      )}

      {/* Editor */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="max-w-3xl mx-auto p-6">
          <EditorContent
            editor={editor}
            className="prose prose-sm dark:prose-invert max-w-none min-h-[50vh] focus:outline-none [&_.tiptap]:outline-none [&_.tiptap]:min-h-[50vh] [&_.tiptap_p.is-editor-empty:first-child::before]:text-muted-foreground/40 [&_.tiptap_p.is-editor-empty:first-child::before]:content-[attr(data-placeholder)] [&_.tiptap_p.is-editor-empty:first-child::before]:float-left [&_.tiptap_p.is-editor-empty:first-child::before]:h-0 [&_.tiptap_p.is-editor-empty:first-child::before]:pointer-events-none"
          />
        </div>
      </div>

      {/* Bubble menu for selection */}
      {editor && selectedText && !readOnly && (
        <BubbleMenu editor={editor} updateDelay={100}>
          <div className="flex items-center gap-0.5 px-1 py-0.5 bg-popover border border-border rounded-md shadow-lg">
            <button onClick={() => editor.chain().focus().toggleBold().run()} className={toolbarBtnClass(editor.isActive('bold'))}>
              <Bold className="size-3.5" />
            </button>
            <button onClick={() => editor.chain().focus().toggleItalic().run()} className={toolbarBtnClass(editor.isActive('italic'))}>
              <Italic className="size-3.5" />
            </button>
            <button onClick={() => editor.chain().focus().toggleCode().run()} className={toolbarBtnClass(editor.isActive('code'))}>
              <Code className="size-3.5" />
            </button>
            <div className="w-px h-4 bg-border mx-0.5" />
            <button
              onClick={() => {
                setAiMode('selection');
                setAiError(null);
                setAiPanelOpen(true);
              }}
              className="flex items-center gap-1 px-1.5 py-0.5 rounded text-xs hover:bg-muted transition-colors text-muted-foreground hover:text-primary"
              title={i18n('AI modify selection')}
            >
              <Sparkles className="size-3" />
              AI
            </button>
          </div>
        </BubbleMenu>
      )}

      <ConfirmDialog
        open={leaveConfirmOpen}
        message={i18n('Leave without saving? Your changes will be lost.')}
        confirmLabel={i18n('Discard')}
        cancelLabel={i18n('Keep editing')}
        danger
        onConfirm={() => {
          setLeaveConfirmOpen(false);
          setDirty(false);
          navigate('/knowledge?tab=articles');
        }}
        onCancel={() => setLeaveConfirmOpen(false)}
      />
    </div>
  );
};
