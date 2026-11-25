import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite 构建与本地开发代理配置（开发时将 /api 与 /ws 代理到 Go 后端 8080 端口）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://127.0.0.1:8080',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
  },
});
