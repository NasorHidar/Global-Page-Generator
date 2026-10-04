import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Vite configuration for the Global Page Generator frontend.
 *
 * Dev proxy:
 *   Any request to /api/** is forwarded to the Spring Boot backend running on
 *   localhost:8080, bypassing Vite's same-origin restriction during development.
 *   The `rewrite` strips the /api prefix so the backend's @RequestMapping paths
 *   remain clean (e.g., /api/v1/execute → http://localhost:8080/api/v1/execute
 *   — the rewrite is a no-op here because the backend already mounts at /api/v1).
 *
 * @see https://vitejs.dev/config/server-options.html#server-proxy
 */
export default defineConfig({
  plugins: [react()],

  server: {
    port: 5173,
    strictPort: true,     // Fail immediately if 5173 is taken — avoids silent port drift

    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        // Uncomment the line below if the backend is not mounted at /api:
        // rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },

  build: {
    // Generate source maps for production debugging via error-tracking tools
    sourcemap: true,
    // Inline assets smaller than 4 kB as base64 data URIs
    assetsInlineLimit: 4096,
    // Raise the chunk-size warning threshold slightly (default: 500 kB)
    chunkSizeWarningLimit: 600,
  },

  // Expose the API base URL as a typed env variable consumed by DynamicServicePage.jsx
  // Override with VITE_API_BASE_URL in production .env files
  define: {
    __DEV__: JSON.stringify(process.env.NODE_ENV !== 'production'),
  },
});
