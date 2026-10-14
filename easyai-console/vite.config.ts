/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    // Two entries into one dist/ so the whole-directory copy of the console build
    // (`copy-console-dist` in the easyai-apps poms) serves both same-origin
    // with no backend change.
    rollupOptions: {
      input: {
        main: path.resolve(__dirname, 'index.html'),
        mobile: path.resolve(__dirname, 'mobile.html'),
      },
    },
  },
  test: {
    setupFiles: ['./src/test-setup.ts'],
  },
  server: {
    // 经 tailscale serve 用 https://mac-mini.tailae6c41.ts.net 访问时，
    // Vite 的 Host 头校验会拦掉该域名（防 DNS rebinding），需显式放行。
    allowedHosts: ['mac-mini.tailae6c41.ts.net', '.tailae6c41.ts.net'],
    watch: {
      ignored: ['**/easyai/**', '**/.git/**', '**/node_modules/**', '**/.git_disabled/**'],
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
