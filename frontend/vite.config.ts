import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = {
    '/ws': { target: env.XIM_BACKEND_URL || 'http://127.0.0.1:8080', ws: true },
    '/api': { target: env.XIM_BACKEND_URL || 'http://127.0.0.1:8080' },
  };
  return {
    plugins: [react()],
    build: { outDir: mode === 'app' ? 'dist-app' : 'dist', target: ['es2022', 'safari15'] },
    clearScreen: false,
    server: { host: process.env.TAURI_DEV_HOST || '127.0.0.1', port: 5173, strictPort: true, proxy, ...(process.env.TAURI_DEV_HOST ? { hmr: { protocol: 'ws', host: process.env.TAURI_DEV_HOST, port: 1421 } } : {}) },
    preview: { port: 5173, strictPort: true, proxy },
  };
});
