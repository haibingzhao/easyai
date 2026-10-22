import { lazy, Suspense } from 'react'
import type { FC } from 'react'
import { Loader2 } from 'lucide-react'

// The editor drags in tiptap/ProseMirror; keep it out of the static graph so hosts that never
// open the knowledge editor do not download it.
const ArticleEditorPageContent = lazy(() =>
  import('./ArticleEditorPage').then((module) => ({ default: module.ArticleEditorPage })),
)

export const ArticleEditorPage: FC = () => (
  <Suspense
    fallback={
      <div className="w-full h-full flex items-center justify-center bg-background">
        <Loader2 className="w-8 h-8 animate-spin text-muted-foreground" />
      </div>
    }
  >
    <ArticleEditorPageContent />
  </Suspense>
)
