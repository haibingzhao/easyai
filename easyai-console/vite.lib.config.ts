import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import type { Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'
import pkg from './package.json'

/**
 * Vite configuration for building @easyai/console as a library.
 * Produces dist-lib/index.js + dist-lib/style.css, plus the source-level theme.css
 * that hosts compile together with their own Tailwind build.
 *
 * Usage: npm run build:lib
 */

// Hosts import theme.css instead of the pre-built style.css so utilities used by the
// library are generated once, in the host's Tailwind build, alongside the host's own.
function emitThemeCss(): Plugin {
  return {
    name: 'emit-theme-css',
    apply: 'build',
    generateBundle() {
      for (const file of ['theme.css', 'mobile/mobile.css']) {
        this.emitFile({
          type: 'asset',
          fileName: file,
          source: readFileSync(path.resolve(__dirname, 'src', file), 'utf-8'),
        })
      }
    },
  }
}

// Externalize all runtime dependencies + their sub-path imports (e.g. react/jsx-runtime)
// but NOT @/ aliases which should be resolved and bundled.
//
// The editor stack (@tiptap/*, tiptap-markdown, prosemirror-*) lives in devDependencies and is
// bundled into dist-lib on purpose: hosts consume the library without installing tiptap.
const depNames = new Set([
  ...Object.keys(pkg.dependencies || {}),
  ...Object.keys(pkg.peerDependencies || {}),
])
const external = (id: string) => {
  // Extract package name: "react/jsx-runtime" -> "react", "@dnd-kit/core/foo" -> "@dnd-kit/core"
  const pkgName = id.startsWith('@')
    ? id.split('/').slice(0, 2).join('/')
    : id.split('/')[0]
  return depNames.has(pkgName)
}

export default defineConfig({
  plugins: [react(), tailwindcss(), emitThemeCss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    lib: {
      entry: path.resolve(__dirname, 'src/lib.ts'),
      formats: ['es'],
      fileName: 'index',
    },
    outDir: 'dist-lib',
    rollupOptions: {
      external,
      output: {
        assetFileNames: 'style.[ext]',
      },
    },
  },
})
