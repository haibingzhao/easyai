import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

/**
 * Vite configuration for building @easyai/console as a library.
 * Produces dist-lib/index.js (ES module) + dist-lib/style.css.
 *
 * Usage: npm run build:lib
 */
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
      external: [
        'react',
        'react-dom',
        'react/jsx-runtime',
        'react-router-dom',
        'zustand',
      ],
      output: {
        assetFileNames: 'style.[ext]',
      },
    },
  },
})
