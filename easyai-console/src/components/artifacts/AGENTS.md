# easyai-console/src/components/artifacts/ AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Artifact rendering: routes MIME-typed content to specialized viewers (text, markdown, HTML, PDF, Excel, image).

## STRUCTURE

```
artifacts/
├── ArtifactPanel.tsx     # MIME router → selects appropriate renderer
├── TextArtifact.tsx      # Plain text display with monospace formatting
├── MarkdownArtifact.tsx  # Markdown rendering via react-markdown
├── HtmlArtifact.tsx      # HTML sandbox rendering (iframe)
├── PdfArtifact.tsx       # PDF viewer (pdfjs-dist)
├── ExcelArtifact.tsx     # Spreadsheet viewer (SheetJS/xlsx)
└── ImageArtifact.tsx     # Image display with zoom/pan
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Add new artifact type | Create `*Artifact.tsx`, register in `ArtifactPanel` | Follow `TextArtifact.tsx` pattern |
| MIME routing | `ArtifactPanel.tsx` | Content type → component mapping |
| Excel support | `ExcelArtifact.tsx` | Uses xlsx from SheetJS CDN (not npm) |

## CONVENTIONS
- Each artifact: isolated `*Artifact.tsx` component
- Props: `{ content: string, mimeType?: string }`
- `ArtifactPanel` is the router — add new types here
- XLSX loaded from CDN URL in package.json, not npm registry

## ANTI-PATTERNS
- No `any` types
- No inline imports
- Don't embed MIME logic outside `ArtifactPanel` — keep routing centralized
- XLSX type: check node_modules — CDN package may lack .d.ts
