import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'
import pkg from './package.json'

/**
 * Vite configuration for building @easyai/console as a library.
 * Produces dist-lib/index.js (ES module) + dist-lib/style.css.
 *
 * Usage: npm run build:lib
 */

// Externalize all dependencies + their sub-path imports (e.g. react/jsx-runtime)
// but NOT @/ aliases which should be resolved and bundled
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
  plugins: [react(), tailwindcss()],
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
