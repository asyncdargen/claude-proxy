import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const serviceTarget = process.env.SERVICE_URL ?? 'http://127.0.0.1:8787';
const gatewayTarget = process.env.GATEWAY_URL ?? 'http://127.0.0.1:9000';

// Dev: proxy management calls to Kotlin and all datapath routes to the Go gateway.
// Build: emit to ./dist — the nginx image serves it (no longer baked into the jar).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': serviceTarget,
      '/healthz': serviceTarget,
      '/gateway': { target: gatewayTarget, rewrite: (p) => p.replace(/^\/gateway/, '') },
      '/v1': gatewayTarget,
      '/routing/openai': gatewayTarget,
      '/routing/anthropic': gatewayTarget,
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
