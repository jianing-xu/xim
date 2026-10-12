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
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 5173, strictPort: true, proxy },
  };
});
